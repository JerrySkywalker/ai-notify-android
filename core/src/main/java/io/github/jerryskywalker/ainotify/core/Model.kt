package io.github.jerryskywalker.ainotify.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Frontend(val id: String, val label: String)

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
    val frontend: Frontend? = null,
) {
    val toolLabel: String get() = when (tool) {
        "codex" -> "Codex"
        "claude-code" -> "Claude Code"
        "manual" -> "AINotify"
        else -> tool
    }

    val frontendKey: String get() = "$tool:" + frontend?.id.orEmpty()
    val heading: String get() = listOfNotNull(deviceName, frontend?.label ?: toolLabel, project).joinToString(" · ")
    val statusLabel: String get() = if (kind == "test") "Test message" else "Reply complete"
}

@Serializable
data class Feed(
    val events: List<Entry>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("stream_id") val streamId: String? = null,
    @SerialName("reset_cursor") val resetCursor: Boolean = false,
)

@Serializable
data class Settings(val relay: String, val token: String)

@Serializable
data class Mailbox(
    val cursor: Long = 0,
    val events: List<Entry> = emptyList(),
    val relay: String? = null,
    val fullHistory: Boolean = false,
    val notifyAfter: Long = 0,
    @SerialName("stream_id") val streamId: String? = null,
)

const val WATCH_PATH = "/ainotify/v1/inbox"
const val WATCH_PAYLOAD = "preview_json"
const val EVENT_EXTRA = "ainotify_event"

@Serializable
data class DeviceRegistration(
    val token: String,
    @SerialName("device_type") val deviceType: String = "android",
    @SerialName("app_id") val appId: String = "io.github.jerryskywalker.ainotify",
)

@Serializable
data class DeviceUnregistration(
    val token: String,
)

@Serializable
data class PushDevice(
    val token: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_type") val deviceType: String,
    @SerialName("app_id") val appId: String,
    @SerialName("created_at_ms") val createdAt: Long,
    @SerialName("updated_at_ms") val updatedAt: Long,
)

@Serializable
data class PushDevicesResponse(val devices: List<PushDevice>)

@Serializable
data class PushDelivery(
    val id: Long,
    @SerialName("event_id") val eventId: String,
    val token: String,
    val status: String,
    @SerialName("attempted_at_ms") val attemptedAt: Long,
    val error: String? = null,
)

@Serializable
data class PushDeliveriesResponse(val deliveries: List<PushDelivery>)
