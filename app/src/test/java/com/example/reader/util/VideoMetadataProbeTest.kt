package com.example.reader.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMetadataProbeTest {

    @Test
    fun `toggle off never routes regardless of edge size`() {
        assertFalse(shouldAutoRouteToSystem(enabled = false, longestEdge = 4000, threshold = 1440))
        assertFalse(shouldAutoRouteToSystem(enabled = false, longestEdge = 1441, threshold = 1440))
    }

    @Test
    fun `toggle on but edge below threshold does not route`() {
        // 1080p and below are fine for ExoPlayer on most devices — don't auto-route.
        assertFalse(shouldAutoRouteToSystem(enabled = true, longestEdge = 1080, threshold = 1440))
        assertFalse(shouldAutoRouteToSystem(enabled = true, longestEdge = 1440, threshold = 1440))
    }

    @Test
    fun `toggle on and edge above threshold routes`() {
        // QHD and 4K — the regime where the workaround matters.
        assertTrue(shouldAutoRouteToSystem(enabled = true, longestEdge = 1441, threshold = 1440))
        assertTrue(shouldAutoRouteToSystem(enabled = true, longestEdge = 3840, threshold = 1440))
    }

    @Test
    fun `probe failure (zero edge) fails open — never routes`() {
        // Failsafe: when MediaMetadataRetriever returns 0 (no scheme, IO error,
        // missing metadata), we must NOT auto-route. Otherwise the user gets a
        // confusing system-player launch for a video we couldn't classify.
        assertFalse(shouldAutoRouteToSystem(enabled = true, longestEdge = 0, threshold = 1440))
        // Even with a huge explicit "edge" value of 0, the rule still says no.
    }

    @Test
    fun `exactly at threshold does not route`() {
        // Strict greater-than: at-threshold videos use the user preference as usual.
        // This avoids surprising the user with a system-player launch for borderline
        // resolutions (e.g. exactly 1440p).
        assertFalse(shouldAutoRouteToSystem(enabled = true, longestEdge = 1440, threshold = 1440))
    }

    @Test
    fun `one pixel above threshold routes`() {
        assertTrue(shouldAutoRouteToSystem(enabled = true, longestEdge = 1441, threshold = 1440))
    }
}
