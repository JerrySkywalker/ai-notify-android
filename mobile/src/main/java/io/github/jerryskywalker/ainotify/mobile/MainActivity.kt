package io.github.jerryskywalker.ainotify.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.ainotify.core.EVENT_EXTRA
import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.Inbox
import io.github.jerryskywalker.ainotify.core.Relay
import io.github.jerryskywalker.ainotify.core.Settings
import io.github.jerryskywalker.ainotify.core.validateSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var selected by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = eventId(intent)
        val inbox = Phone.inbox(this)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("ainotify_start", false) && inbox.settings() != null) startReceiver()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff155d52), background = Color(0xfff3f7f5))) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    App(inbox, selected, { selected = it }, ::startReceiver, ::stopReceiver)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selected = eventId(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("ainotify_start", false) && Phone.inbox(this).settings() != null) startReceiver()
    }

    private fun eventId(intent: Intent): String? = intent.getStringExtra(EVENT_EXTRA)?.let {
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
    }
}

@Composable
private fun App(
    inbox: Inbox,
    selected: String?,
    select: (String?) -> Unit,
    start: () -> Unit,
    stop: () -> Unit,
) {
    val mailbox by inbox.mailbox.collectAsState()
    val status by Phone.status.collectAsState()
    var editing by remember { mutableStateOf(inbox.settings() == null) }
    val current = inbox.settings()
    BackHandler(selected != null || (editing && current != null)) {
        if (selected != null) select(null) else editing = false
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("AINotify", style = MaterialTheme.typography.headlineMedium)
        if (selected != null) {
            TextButton(onClick = { select(null) }) { Text("Back to inbox") }
            Detail(selected, mailbox.events.find { it.id == selected }, current)
        } else if (editing) {
            SettingsForm(current, onSave = { settings ->
                inbox.saveSettings(settings)
                editing = false
                start()
            }, cancel = { if (current != null) editing = false })
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Inbox", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { editing = true }) { Text("Settings") }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (status == "Receiver off") Button(onClick = start) { Text("Start receiving") }
            else TextButton(onClick = stop) { Text("Stop receiving") }
            if (mailbox.events.isEmpty()) Text("AI replies will appear here.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(mailbox.events, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth().clickable { select(entry.id) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.heading, style = MaterialTheme.typography.titleSmall)
                            entry.sessionId?.let { session ->
                                Text("Session " + session.takeLast(8), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(entry.preview ?: entry.statusLabel, maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsForm(initial: Settings?, onSave: (Settings) -> Unit, cancel: () -> Unit) {
    var address by remember { mutableStateOf(initial?.relay ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Relay settings", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(address, { address = it }, label = { Text("Relay address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(token, { token = it }, label = { Text("Reader token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = {
            try {
                onSave(validateSettings(Settings(address, token), BuildConfig.DEBUG))
            } catch (failure: Exception) {
                error = failure.message ?: "Could not save relay settings."
            }
        }) { Text("Save and receive") }
        if (initial != null) TextButton(onClick = cancel) { Text("Cancel") }
    }
}

@Composable
private fun Detail(id: String, preview: Entry?, settings: Settings?) {
    var entry by remember(id) { mutableStateOf<Entry?>(null) }
    var waiting by remember(id) { mutableStateOf(true) }
    var failed by remember(id) { mutableStateOf(false) }
    var retry by remember(id) { mutableStateOf(0) }
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
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(displayed?.heading ?: "AI reply", style = MaterialTheme.typography.titleLarge)
        if (waiting) Text("Loading reply…")
        if (failed) {
            Text("Could not load the full reply.")
            TextButton(onClick = { retry++ }) { Text("Try again") }
        }
        if (entry?.messageTruncated == true) Text("The collector stored the first 128 KiB.")
        SelectionContainer { Text(displayed?.message ?: preview?.preview ?: "No reply text was sent.") }
    }
}
