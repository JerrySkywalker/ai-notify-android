package io.github.jerryskywalker.ainotify.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ContractTest {
    @Test fun debugHttpIsLimitedToLocalAddresses() {
        val token = "sample-reader-token-for-testing-only"
        assertEquals("http://10.0.2.2:8787", validateSettings(Settings("http://10.0.2.2:8787/", token), true).relay)
        assertThrows(IllegalArgumentException::class.java) {
            validateSettings(Settings("http://example.com", token), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateSettings(Settings("http://10.0.2.2:8787", token), false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateSettings(Settings("https://user@example.com", token), false)
        }
    }

    @Test fun eventDecodesChinesePreviewAndIgnoresFutureFields() {
        val feed = wireJson.decodeFromString<Feed>(
            """{"events":[{"id":"93d42e8e-8a1d-4b11-9322-4c282125a377","seq":7,"device_id":"win","device_name":"工作站","tool":"codex","kind":"turn.completed","occurred_at_ms":1,"preview":"完成","future":true}],"next_cursor":7}""",
        )
        assertEquals(7L, feed.nextCursor)
        assertEquals("工作站 · Codex", feed.events.single().heading)
        assertEquals("完成", feed.events.single().preview)
        assertNull(feed.streamId)
    }

    @Test fun pairedWatchReceivesSequenceOneAfterReplacingAnInitializedStream() {
        val old = Mailbox(cursor = 5, events = listOf(entry("old", 5)), streamId = "before")
        val (empty, clearedAlerts) = old.mergeFeed(Feed(emptyList(), 0, "after"), null)
        assertEquals(0L, empty.cursor)
        assertEquals(emptyList<Entry>(), empty.events)
        assertEquals(emptyList<Entry>(), clearedAlerts)

        val replacement = Feed(listOf(entry("new", 1)), 1, "after")
        val (current, fresh) = empty.mergeFeed(replacement, null)
        assertEquals(1L, current.cursor)
        assertEquals(replacement.events, fresh)
        val restored = wireJson.decodeFromString<Mailbox>(wireJson.encodeToString(current))
        assertEquals(emptyList<Entry>(), restored.mergeFeed(replacement, null).second)
    }

    @Test fun legacyWatchKeepsOverlappingHistoryAndCanDetectAnEmptyReplacement() {
        val previous = entry("previous", 5)
        val legacy = Mailbox(cursor = 5, events = listOf(previous, entry("older", 4)))
        val next = entry("next", 6)
        val (migrated, fresh) = legacy.mergeFeed(Feed(listOf(next, previous), 6, "known"), null)
        assertEquals("known", migrated.streamId)
        assertEquals(listOf("next", "previous", "older"), migrated.events.map { it.id })
        assertEquals(listOf(next), fresh)

        val (cleared, _) = legacy.mergeFeed(Feed(emptyList(), 0, "replacement"), null)
        val first = entry("first", 1)
        assertEquals(listOf(first), cleared.mergeFeed(Feed(listOf(first), 1, "replacement"), null).second)
    }

    @Test fun phoneCanResetAnUnscopedCursorAtTheSameRelayAddress() {
        val relay = "http://10.0.2.2:8787"
        val old = Mailbox(cursor = 5, events = listOf(entry("old", 5)), relay = relay, fullHistory = true)
        val first = entry("first", 1)
        val (new, fresh) = old.mergeFeed(Feed(listOf(first), 1, "replacement", resetCursor = true), relay)
        assertEquals(Mailbox(1, listOf(first), relay, fullHistory = true, streamId = "replacement"), new)
        assertEquals(listOf(first), fresh)
    }

    @Test fun previewsFromDifferentLegacyRelayAddressesHaveDifferentStreamIds() {
        val old = Mailbox(1, listOf(entry("old", 1).copy(message = "full reply")), "http://10.0.2.2:8787")
        val preview = wearPreview(old)
        val next = wearPreview(old.copy(relay = "http://10.0.2.2:8788"))
        assertNotEquals(preview.streamId, next.streamId)
        assertNull(preview.events.single().message)
        assertEquals("server-id", wearPreview(old.copy(streamId = "server-id")).streamId)
    }

    @Test fun reconnectDoesNotReAlertAlreadySeenEvents() {
        val relay = "http://10.0.2.2:8787"
        val first = entry("e-1", 1)
        val second = entry("e-2", 2)
        val initial = Mailbox(cursor = 2, events = listOf(second, first), relay = relay, fullHistory = true, streamId = "stream-a")

        // Reconnect: relay returns event 2 (which is already known) and event 3
        val third = entry("e-3", 3)
        val (updated, fresh) = initial.mergeFeed(Feed(listOf(third, second), 3, "stream-a"), relay)
        assertEquals(3L, updated.cursor)
        assertEquals(listOf(third), fresh)
        assertEquals(listOf("e-3", "e-2", "e-1"), updated.events.map { it.id })
    }

    @Test fun emptyDatabaseReplacementAtSameAddressWithNewStreamIdResetsCursorAndNotifiesFirstEvent() {
        val relay = "http://10.0.2.2:8787"
        val oldEntry = entry("old-e", 10)
        val oldMailbox = Mailbox(cursor = 10, events = listOf(oldEntry), relay = relay, fullHistory = true, streamId = "stream-old")

        // Replacement DB on same relay address with brand new stream
        val firstNew = entry("first-new", 1)
        val (newMailbox, fresh) = oldMailbox.mergeFeed(Feed(listOf(firstNew), 1, "stream-new"), relay)
        assertEquals(1L, newMailbox.cursor)
        assertEquals("stream-new", newMailbox.streamId)
        assertEquals(listOf(firstNew), fresh)
        assertEquals(listOf("first-new"), newMailbox.events.map { it.id })
    }

    @Test fun reconnectingAfterNetworkDisconnectionCatchesUpBacklog() {
        val relay = "http://10.0.2.2:8787"
        val e1 = entry("e-1", 1)
        val beforeDisconnect = Mailbox(cursor = 1, events = listOf(e1), relay = relay, fullHistory = true, streamId = "stream-a")

        // Reconnect after network gap: events 2 and 3 arrived while offline
        val e2 = entry("e-2", 2)
        val e3 = entry("e-3", 3)
        val (afterReconnect, fresh) = beforeDisconnect.mergeFeed(Feed(listOf(e3, e2), 3, "stream-a"), relay)
        assertEquals(3L, afterReconnect.cursor)
        assertEquals(listOf(e3, e2), fresh)
        assertEquals(listOf("e-3", "e-2", "e-1"), afterReconnect.events.map { it.id })
    }

    @Test fun pushWakeupIncrementalSyncDeduplicatesRepeatedSignals() {
        val relay = "http://10.0.2.2:8787"
        val initial = Mailbox(cursor = 0, events = emptyList(), relay = relay, fullHistory = true, streamId = "stream-p")

        // First push trigger arrives: event 1
        val e1 = entry("push-1", 1)
        val (firstPass, fresh1) = initial.mergeFeed(Feed(listOf(e1), 1, "stream-p"), relay)
        assertEquals(1L, firstPass.cursor)
        assertEquals(listOf(e1), fresh1)

        // Duplicate push trigger arrives for event 1 (same seq, same ID)
        val (duplicatePass, fresh2) = firstPass.mergeFeed(Feed(listOf(e1), 1, "stream-p"), relay)
        assertEquals(1L, duplicatePass.cursor)
        assertEquals(emptyList<Entry>(), fresh2) // No new notification!
        assertEquals(listOf("push-1"), duplicatePass.events.map { it.id })

        // Next push arrives with backlog events 2 and 3
        val e2 = entry("push-2", 2)
        val e3 = entry("push-3", 3)
        val (thirdPass, fresh3) = duplicatePass.mergeFeed(Feed(listOf(e3, e2), 3, "stream-p"), relay)
        assertEquals(3L, thirdPass.cursor)
        assertEquals(listOf(e3, e2), fresh3)
        assertEquals(listOf("push-3", "push-2", "push-1"), thirdPass.events.map { it.id })
    }

    @Test fun pushDeviceModelsSerialization() {
        val reg = DeviceRegistration("token-xyz", "android", "io.github.jerryskywalker.ainotify")
        val json = wireJson.encodeToString(reg)
        val decoded = wireJson.decodeFromString<DeviceRegistration>(json)
        assertEquals("token-xyz", decoded.token)
        assertEquals("android", decoded.deviceType)

        val unreg = DeviceUnregistration("token-xyz")
        val unregJson = wireJson.encodeToString(unreg)
        val decodedUnreg = wireJson.decodeFromString<DeviceUnregistration>(unregJson)
        assertEquals("token-xyz", decodedUnreg.token)

        val delivery = PushDelivery(1, "ev-1", "token-xyz", "dispatched", 123456789L, null)
        val deliveryJson = wireJson.encodeToString(delivery)
        val decodedDelivery = wireJson.decodeFromString<PushDelivery>(deliveryJson)
        assertEquals("ev-1", decodedDelivery.eventId)
        assertEquals("dispatched", decodedDelivery.status)
    }

    private fun entry(id: String, seq: Long) = Entry(id, seq, "win", "Dev PC", "manual", "test", occurredAt = 0)
}
