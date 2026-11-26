package io.github.jerryskywalker.ainotify.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.jerryskywalker.ainotify.core.EVENT_EXTRA
import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.Inbox
import io.github.jerryskywalker.ainotify.core.Relay
import io.github.jerryskywalker.ainotify.core.Settings
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var selected by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Preferences.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        selected = if (savedInstanceState != null) savedInstanceState.getString(EVENT_EXTRA) else eventId(intent)
        val inbox = Phone.inbox(this)
        val workspace = Phone.workspace(this)
        val preferences = Preferences(this)
        Phone.updatePushStatus(this)
        startIfRequested(intent, inbox)
        setContent {
            val prefs by preferences.current.collectAsState()
            PhoneTheme(prefs, ::setLightBars) {
                App(inbox, workspace, preferences, prefs, selected, { selected = it }, ::startReceiver, ::stopReceiver)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Phone.updatePushStatus(this)
        lifecycleScope.launch(Dispatchers.IO) {
            PhoneSync.performSync(this@MainActivity, notify = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EVENT_EXTRA, selected)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selected = eventId(intent)
        startIfRequested(intent, Phone.inbox(this))
    }

    private fun startIfRequested(intent: Intent, inbox: Inbox) {
        if (BuildConfig.DEBUG && intent.getBooleanExtra("ainotify_start", false) && inbox.settings() != null) startReceiver()
    }

    private fun setLightBars(light: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun eventId(intent: Intent): String? =
        (intent.getStringExtra(EVENT_EXTRA) ?: intent.data?.lastPathSegment)?.let {
            runCatching { UUID.fromString(it).toString() }.getOrNull()
        }

    private fun startReceiver() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, ReceiveService::class.java))
    }

    private fun stopReceiver() {
        stopService(Intent(this, ReceiveService::class.java))
        Phone.updatePushStatus(this)
    }
}

@Composable
private fun App(
    inbox: Inbox,
    workspace: PhoneWorkspace,
    preferences: Preferences,
    prefs: PhonePreferences,
    selected: String?,
    select: (String?) -> Unit,
    start: () -> Unit,
    stop: () -> Unit,
) {
    val mailbox by inbox.mailbox.collectAsState()
    val status by Phone.status.collectAsState()
    val workspaces by workspace.workspaces.collectAsState()
    var settings by remember { mutableStateOf(inbox.settings()) }
    val inboxState = rememberSaveableStateHolder()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val relay = settings?.relay
    val current = remember(workspaces, relay) { workspaces[relay] ?: RelayWorkspace() }
    val update: ((RelayWorkspace) -> RelayWorkspace) -> Unit = { change ->
        if (relay != null) workspace.update(relay, change)
    }

    BackHandler(selected != null || settingsOpen) {
        if (selected != null) select(null) else settingsOpen = false
    }

    when {
        selected != null -> Detail(selected, mailbox.events.find { it.id == selected }, settings) { select(null) }
        settingsOpen -> SettingsScreen(
            settings, current, mailbox.events, status, prefs, preferences, update,
            save = { newSettings ->
                inbox.saveSettings(newSettings)
                settings = newSettings
                settingsOpen = false
                start()
            },
            start = start, stop = stop, back = { settingsOpen = false },
        )
        else -> inboxState.SaveableStateProvider(relay ?: "unconfigured") {
            InboxScreen(
                mailbox.events, current, settings != null, status, prefs, update,
                showSettings = { settingsOpen = true }, openReply = select, start = start,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Detail(id: String, preview: Entry?, settings: Settings?, back: () -> Unit) {
    var entry by remember(id) { mutableStateOf<Entry?>(null) }
    var waiting by remember(id) { mutableStateOf(true) }
    var failed by remember(id) { mutableStateOf(false) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, settings, retry) {
        waiting = true
        failed = false
        try {
            entry = if (settings == null) null else withContext(Dispatchers.IO) { Relay(settings, BuildConfig.DEBUG).detail(id) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            waiting = false
        }
    }
    val displayed = entry ?: preview
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.reply)) }, navigationIcon = {
            TextButton(onClick = back) { Text(stringResource(R.string.back)) }
        })
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            displayed?.let {
                Text(it.heading, style = MaterialTheme.typography.headlineSmall)
                it.sessionId?.let { session -> Text(stringResource(R.string.session, session.takeLast(8))) }
            }
            if (waiting) Text(stringResource(R.string.loading))
            if (failed) {
                Text(stringResource(R.string.reply_error))
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.try_again)) }
            }
            if (entry?.messageTruncated == true) Text(stringResource(R.string.truncated))
            SelectionContainer { Text(displayed?.message ?: preview?.preview ?: stringResource(R.string.no_body)) }
        }
    }
}
