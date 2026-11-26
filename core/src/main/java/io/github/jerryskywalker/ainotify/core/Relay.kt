package io.github.jerryskywalker.ainotify.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

class Relay(private val settings: Settings, allowLocalHttp: Boolean) {
    private val base = validateSettings(settings, allowLocalHttp).relay

    fun feed(mailbox: Mailbox): Feed {
        val stream = mailbox.streamId
        // A v0.2.0 cache can identify its cursor by the last event it received.
        val anchor = if (stream == null && mailbox.cursor > 0) {
            mailbox.events.firstOrNull { it.seq == mailbox.cursor }?.id
        } else null
        val cursorIdentity = when {
            stream != null -> "&stream_id=" + URLEncoder.encode(stream, Charsets.UTF_8.name())
            anchor != null -> "&cursor_event_id=" + URLEncoder.encode(anchor, Charsets.UTF_8.name())
            else -> ""
        }
        return wireJson.decodeFromString(get("/v1/events?after=${mailbox.cursor}&limit=50$cursorIdentity"))
    }

    fun detail(id: String): Entry {
        val normalized = UUID.fromString(id).toString()
        return wireJson.decodeFromString(get("/v1/events/$normalized"))
    }

    fun inboxChanges(after: Long): MailChanges =
        wireJson.decodeFromString(get("/v1/inbox/changes?after=$after&limit=50"))

    fun apply(action: MailAction): MailState =
        wireJson.decodeFromString(get("/v1/inbox/actions", wireJson.encodeToString(action)))

    fun registerDevice(token: String, deviceType: String = "android", appId: String = "io.github.jerryskywalker.ainotify"): Boolean {
        val payload = wireJson.encodeToString(DeviceRegistration(token, deviceType, appId))
        get("/v1/devices/register", payload)
        return true
    }

    fun unregisterDevice(token: String): Boolean {
        val payload = wireJson.encodeToString(DeviceUnregistration(token))
        get("/v1/devices/unregister", payload)
        return true
    }

    fun devices(): List<PushDevice> =
        wireJson.decodeFromString<PushDevicesResponse>(get("/v1/devices")).devices

    fun pushDeliveries(): List<PushDelivery> =
        wireJson.decodeFromString<PushDeliveriesResponse>(get("/v1/push/deliveries")).deliveries

    class HttpError(val code: Int) : IOException("Relay HTTP $code")

    private fun get(path: String, body: String? = null): String {
        val request = URI.create(base + path).toURL().openConnection() as HttpURLConnection
        try {
            request.connectTimeout = 4_000
            request.readTimeout = 6_000
            request.instanceFollowRedirects = false
            request.setRequestProperty("Authorization", "Bearer " + settings.token)
            request.setRequestProperty("Accept", "application/json")
            if (body != null) {
                request.requestMethod = "POST"
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8)
                request.setFixedLengthStreamingMode(bytes.size)
                request.outputStream.use { it.write(bytes) }
            }
            val code = request.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpError(code)
            return request.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 1024 * 1024) throw IOException("Relay response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } finally {
            request.disconnect()
        }
    }
}

fun validateSettings(settings: Settings, allowLocalHttp: Boolean): Settings {
    val url = settings.relay.trim().trimEnd('/')
    val uri = runCatching { URI.create(url) }.getOrNull()
    val local = uri?.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
    require(
        uri?.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.scheme == "https" || (allowLocalHttp && local && uri.scheme == "http")),
    ) { "Enter an HTTPS relay address (local HTTP is available in debug builds)." }
    val token = settings.token.trim()
    require(token.length in 24..512 && token.all { it in '!'..'~' }) { "Enter a valid relay reader token." }
    return Settings(url, token)
}
