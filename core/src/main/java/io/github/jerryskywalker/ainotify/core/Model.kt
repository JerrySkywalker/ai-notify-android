package io.github.jerryskywalker.ainotify.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Entry(
    val id: String,
    val seq: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val tool: String,
    val kind: String,
    val project: String? = null,
    @SerialName("occurred_at_ms") val occurredAt: Long,
    @SerialName("session_id") val sessionId: String? = null,
    val preview: String? = null,
    val message: String? = null,
    @SerialName("message_truncated") val messageTruncated: Boolean = false,
) {
    val toolLabel: String get() = when (tool) {
        "codex" -> "Codex"
        "claude-code" -> "Claude Code"
        else -> "AINotify"
    }

    val heading: String get() = listOfNotNull(deviceName, toolLabel, project).joinToString(" · ")
    val statusLabel: String get() = if (kind == "test") "Test message" else "Reply complete"
}

@Serializable
data class Feed(val events: List<Entry>, @SerialName("next_cursor") val nextCursor: Long)

@Serializable
data class Settings(val relay: String, val token: String)

@Serializable
data class Mailbox(val cursor: Long = 0, val events: List<Entry> = emptyList(), val relay: String? = null)

const val WATCH_PATH = "/ainotify/v1/inbox"
const val WATCH_PAYLOAD = "preview_json"
const val EVENT_EXTRA = "ainotify_event"
