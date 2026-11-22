package io.github.jerryskywalker.ainotify.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jerryskywalker.ainotify.core.EVENT_EXTRA
import io.github.jerryskywalker.ainotify.core.Settings
import io.github.jerryskywalker.ainotify.core.validateSettings
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var eventId by mutableStateOf<String?>(null)
    private val inbox get() = Watch.inbox(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eventId = selected(intent)
        checkPermission()
        if (inbox.settings() != null && Watch.status.value == "Waiting for phone") Watch.status.value = "Receiver off"
        if (BuildConfig.DEBUG && intent.getBooleanExtra("ainotify_start", false) && inbox.settings() != null) startReceiver()
        setContent {
            MaterialTheme {
                val mailbox by inbox.mailbox.collectAsState()
                val status by Watch.status.collectAsState()
                val current = mailbox.events.find { it.id == eventId }
                BackHandler(eventId != null) { eventId = null }
                ScalingLazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 17.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item { Text("AINotify", style = MaterialTheme.typography.title2) }
                    if (current != null) {
                        item { Text(current.heading, textAlign = TextAlign.Center) }
                        item { Text(current.preview ?: current.statusLabel, textAlign = TextAlign.Center) }
                        item { Chip(onClick = { eventId = null }, label = { Text("Back to inbox") }) }
                    } else {
                        item { Text(status, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                        if (mailbox.events.isEmpty()) item { Text("No AI replies yet", textAlign = TextAlign.Center) }
                        items(mailbox.events.take(20), key = { it.id }) { entry ->
                            Chip(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { eventId = entry.id },
                                label = { Text(entry.deviceName, maxLines = 1) },
                                secondaryLabel = { Text(entry.toolLabel, maxLines = 1) },
                            )
                        }
                        item { Chip(onClick = ::relaySettings, label = { Text("Relay settings") }) }
                        if (status == "Receiver off") {
                            item { Chip(onClick = ::startReceiver, label = { Text("Start receiving") }) }
                        } else if (inbox.settings() != null) {
                            item { Chip(onClick = { stopService(Intent(this@MainActivity, DirectReceiver::class.java)) }, label = { Text("Stop receiving") }) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        eventId = selected(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("ainotify_start", false) && inbox.settings() != null) startReceiver()
    }

    private fun selected(intent: Intent): String? = intent.getStringExtra(EVENT_EXTRA)?.let {
        runCatching { UUID.fromString(it).toString() }.getOrNull()
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun startReceiver() {
        checkPermission()
        startForegroundService(Intent(this, DirectReceiver::class.java))
    }

    // Paired mode needs no credential entry. The prototype's direct mode uses
    // a masked relay bearer token until it has a short device-enrollment flow.
    @SuppressLint("WearPasswordInput")
    private fun relaySettings() {
        val existing = inbox.settings()
        val address = EditText(this).apply {
            hint = "HTTPS relay address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(existing?.relay ?: "")
        }
        val token = EditText(this).apply {
            hint = "Reader token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(existing?.token ?: "")
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 0)
            addView(address)
            addView(token)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Relay settings")
            .setView(ScrollView(this).apply { addView(content) })
            .setNeutralButton("Use phone") { _, _ ->
                inbox.saveSettings(null)
                stopService(Intent(this, DirectReceiver::class.java))
                Watch.status.value = "Waiting for phone"
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    inbox.saveSettings(validateSettings(Settings(address.text.toString(), token.text.toString()), BuildConfig.DEBUG))
                    dialog.dismiss()
                    startReceiver()
                } catch (error: Exception) {
                    address.error = error.message ?: "Could not save"
                }
            }
        }
        dialog.show()
    }
}
