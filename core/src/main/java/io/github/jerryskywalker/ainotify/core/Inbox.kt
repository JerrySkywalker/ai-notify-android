package io.github.jerryskywalker.ainotify.core

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class Inbox(context: Context, profile: String = "phone") {
    private val settingsFile = AtomicFile(File(context.filesDir, "$profile-settings.json"))
    private val boxFile = AtomicFile(File(context.filesDir, "$profile-inbox.json"))
    private var credentials: Settings? = read(settingsFile)
    private val state = MutableStateFlow(read<Mailbox>(boxFile)?.takeIf { it.relay == credentials?.relay } ?: Mailbox())
    val mailbox = state.asStateFlow()

    @Synchronized
    fun settings(): Settings? = credentials

    @Synchronized
    fun saveSettings(settings: Settings?) {
        if (credentials == settings) return
        val changedRelay = credentials?.relay != settings?.relay
        if (settings == null) settingsFile.delete()
        else write(settingsFile, wireJson.encodeToString(settings))
        credentials = settings
        if (changedRelay) {
            boxFile.delete()
            state.value = Mailbox()
        }
    }

    @Synchronized
    fun merge(feed: Feed, expectedSettings: Settings? = settings()): List<Entry> {
        if (credentials != expectedSettings) return emptyList()
        val before = state.value
        val known = before.events.associateBy { it.id }
        val fresh = feed.events.distinctBy { it.id }.filter { it.id !in known && it.seq > before.cursor }
        val combined = (before.events + fresh).sortedByDescending { it.seq }.take(100)
        val next = Mailbox(maxOf(before.cursor, feed.nextCursor), combined, credentials?.relay)
        if (next != before) {
            write(boxFile, wireJson.encodeToString(next))
            state.value = next
        }
        return fresh
    }

    private inline fun <reified T> read(file: AtomicFile): T? = try {
        wireJson.decodeFromString(file.readFully().toString(Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        null
    }

    private fun write(file: AtomicFile, json: String) {
        val stream = file.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
