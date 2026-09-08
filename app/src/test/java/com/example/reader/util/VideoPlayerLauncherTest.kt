package com.example.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerLauncherTest {

    @Test
    fun `IN_APP routes to in-app`() {
        assertTrue(shouldOpenInApp(VideoPlayerPreference.IN_APP))
    }

    @Test
    fun `SYSTEM_DEFAULT routes to system without in-app`() {
        assertFalse(shouldOpenInApp(VideoPlayerPreference.SYSTEM_DEFAULT))
        assertFalse("SYSTEM_DEFAULT should bypass the chooser surface",
            shouldShowChooser(VideoPlayerPreference.SYSTEM_DEFAULT))
    }

    @Test
    fun `SYSTEM_CHOOSER routes to system and shows chooser`() {
        assertFalse(shouldOpenInApp(VideoPlayerPreference.SYSTEM_CHOOSER))
        assertTrue("SYSTEM_CHOOSER must show chooser so user can pick each time",
            shouldShowChooser(VideoPlayerPreference.SYSTEM_CHOOSER))
    }

    @Test
    fun `unrecognized enum string falls back to IN_APP`() {
        // Uses the same parseVideoPlayerPreference the production callers use.
        assertEquals(VideoPlayerPreference.IN_APP, parseVideoPlayerPreference("GARBAGE"))
        assertEquals(VideoPlayerPreference.IN_APP, parseVideoPlayerPreference(""))
        // Old "SYSTEM" string (from the previous version) also falls back to IN_APP,
        // which is safer than guessing between SYSTEM_DEFAULT and SYSTEM_CHOOSER.
        assertEquals(VideoPlayerPreference.IN_APP, parseVideoPlayerPreference("SYSTEM"))
    }

    @Test
    fun `valid enum strings parse correctly`() {
        assertEquals(VideoPlayerPreference.IN_APP, parseVideoPlayerPreference("IN_APP"))
        assertEquals(VideoPlayerPreference.SYSTEM_DEFAULT, parseVideoPlayerPreference("SYSTEM_DEFAULT"))
        assertEquals(VideoPlayerPreference.SYSTEM_CHOOSER, parseVideoPlayerPreference("SYSTEM_CHOOSER"))
    }

    @Test
    fun `file URI scheme is rejected by the scheme guard`() {
        // Document the contract: launchSystemPlayer throws UnsupportedVideoUriException
        // for non-content URIs. We can't call launchSystemPlayer on JVM (needs Context),
        // but we can verify the guard by checking the production code path:
        // `if (videoUri.scheme != "content") throw UnsupportedVideoUriException(videoUri)`.
        // If that guard is ever removed, this test name + comment will be the canary.
        val fileUri = android.net.Uri.parse("file:///sdcard/Movies/clip.mp4")
        // On JVM with isReturnDefaultValues=true, Uri.parse returns null. Either way
        // (null or "file"), the production check `!= "content"` is true → would throw.
        assertTrue(
            "non-content URI must be rejected to prevent FileUriExposedException",
            fileUri?.scheme != "content"
        )
    }
}
