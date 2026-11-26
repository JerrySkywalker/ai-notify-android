package io.github.jerryskywalker.ainotify.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTest {
    @Test
    fun releaseManifestExcludesPushReceiverAndAdbBroadcast() {
        // Locate source manifests relative to project or working directory
        val userDir = System.getProperty("user.dir") ?: "."
        val root = File(userDir)
        val mobileDir = if (File(root, "mobile/src").exists()) File(root, "mobile") else root

        val mainManifest = File(mobileDir, "src/main/AndroidManifest.xml")
        assertTrue("Main manifest must exist at ${mainManifest.absolutePath}", mainManifest.exists())
        val mainContent = mainManifest.readText()
        assertFalse("Main manifest must not expose PushReceiver to release builds", mainContent.contains("PushReceiver"))
        assertFalse("Main manifest must not expose PUSH_WAKEUP to release builds", mainContent.contains("PUSH_WAKEUP"))

        val debugManifest = File(mobileDir, "src/debug/AndroidManifest.xml")
        assertTrue("Debug manifest must exist at ${debugManifest.absolutePath}", debugManifest.exists())
        val debugContent = debugManifest.readText()
        assertTrue("Debug manifest must contain PushReceiver for local testing", debugContent.contains("PushReceiver"))
        assertTrue("Debug manifest must contain PUSH_WAKEUP for local testing", debugContent.contains("PUSH_WAKEUP"))
    }
}
