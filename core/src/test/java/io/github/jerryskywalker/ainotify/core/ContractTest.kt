package io.github.jerryskywalker.ainotify.core

import org.junit.Assert.assertEquals
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
    }
}
