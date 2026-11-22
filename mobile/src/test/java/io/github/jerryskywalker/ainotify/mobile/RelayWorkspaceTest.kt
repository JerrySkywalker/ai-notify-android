package io.github.jerryskywalker.ainotify.mobile

import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.Folder
import io.github.jerryskywalker.ainotify.core.Frontend
import io.github.jerryskywalker.ainotify.core.MailAction
import io.github.jerryskywalker.ainotify.core.MailChanges
import io.github.jerryskywalker.ainotify.core.MailState
import io.github.jerryskywalker.ainotify.core.wireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWorkspaceTest {
    @Test
    fun frontendAndDeviceFiltersIntersectAndPinsStillRespectBoth() {
        val desktop = entry("e1", 1, "work", Frontend("desktop", "Codex Desktop"))
        val cli = entry("e2", 2, "farm", Frontend("cli", "Codex CLI"))
        val legacy = entry("e3", 3, "farm", null)
        val outside = entry("e4", 4, "other", Frontend("cli", "Codex CLI"))
        val workspace = RelayWorkspace(
            groups = listOf(DeviceGroup("g", "Build farm", setOf("farm", "work"))),
            filters = Filters(setOf("work"), setOf("g"), setOf(desktop.frontendKey, cli.frontendKey)),
            confirmed = mapOf("e1" to MailState("e1", 1, pinned = true), "e4" to MailState("e4", 2, pinned = true)),
        )
        assertEquals(setOf("farm", "work"), workspace.devicesCovered())
        assertEquals(listOf(desktop, cli), workspace.entries(listOf(outside, legacy, cli, desktop)))
        assertEquals("codex:", legacy.frontendKey)
    }

    @Test
    fun pendingPatchesSurviveSerializationAndDoNotEraseOtherFields() {
        val pin = MailAction("operation-1", "event", pinned = true)
        val archive = MailAction("operation-2", "event", folder = Folder.ARCHIVE)
        val initial = RelayWorkspace(mode = SyncMode.SYNC).queue(pin).queue(archive)
        val restored = wireJson.decodeFromString<RelayWorkspace>(wireJson.encodeToString(initial))
        val acknowledged = restored.acknowledge(pin, MailState("event", 8, pinned = true))
        val oldPage = acknowledged.receive(MailChanges(listOf(MailState("event", 4, Folder.TRASH)), 4, true))
        assertTrue(oldPage.isPending("event"))
        assertEquals(4L, oldPage.cursor)
        assertEquals(MailState("event", 8, Folder.ARCHIVE, true), oldPage.state("event"))
        val readOnly = oldPage.readOnly()
        assertFalse(readOnly.isPending("event"))
        assertEquals(MailState("event", 8, Folder.ARCHIVE, true), readOnly.state("event"))
        val reconnected = readOnly.receive(MailChanges(emptyList(), 4, true))
        assertEquals(MailState("event", 8, Folder.INBOX, true), reconnected.state("event"))
    }

    private fun entry(id: String, seq: Long, device: String, frontend: Frontend?) =
        Entry(id, seq, device, device, "codex", "turn.completed", occurredAt = 1, frontend = frontend)
}
