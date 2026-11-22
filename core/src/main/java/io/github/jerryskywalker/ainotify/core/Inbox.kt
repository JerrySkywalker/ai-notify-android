package io.github.jerryskywalker.ainotify.core

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class Inbox(context: Context, profile: String = "phone", private val fullHistory: Boolean = false) {
    private val settingsFile = AtomicFile(File(context.filesDir, "$profile-settings.json"))
    private val boxFile = AtomicFile(File(context.filesDir, "$profile-inbox.json"))
    private var credentials: Settings? = read(settingsFile)
    private val state = MutableStateFlow(restore())
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
            state.value = Mailbox(fullHistory = fullHistory)
        }
    }

    @Synchronized
    fun merge(feed: Feed, expectedSettings: Settings? = settings()): List<Entry> {
        if (credentials != expectedSettings) return emptyList()
        val before = state.value
        val known = before.events.associateBy { it.id }
        val received = feed.events.distinctBy { it.id }.filter { it.id !in known && it.seq > before.cursor }
        val fresh = received.filter { it.seq > before.notifyAfter }
        val sorted = (before.events + received).sortedByDescending { it.seq }
        val combined = if (fullHistory) sorted else sorted.take(100)
        val next = before.copy(cursor = maxOf(before.cursor, feed.nextCursor), events = combined, relay = credentials?.relay)
        if (next != before) {
            write(boxFile, wireJson.encodeToString(next))
            state.value = next
        }
        return fresh
    }

    private fun restore(): Mailbox {
        val saved = read<Mailbox>(boxFile)?.takeIf { it.relay == credentials?.relay }
            ?: return Mailbox(fullHistory = fullHistory)
        // v0.1 phones only cached their latest 100 entries. Replay older pages without re-notifying.
        return if (fullHistory && !saved.fullHistory) {
            saved.copy(cursor = 0, fullHistory = true, notifyAfter = saved.cursor)
        } else {
            saved
        }
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
