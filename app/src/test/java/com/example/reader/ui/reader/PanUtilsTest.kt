package com.example.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PanUtilsTest {

    @Test
    fun `scale less than or equal to 1 returns zero`() {
        // Single-finger pan is only meaningful when zoomed. Below 1x we always
        // force offset to (0,0) regardless of proposed input.
        val vp = IntSize(1000, 2000)
        assertEquals(Offset.Zero, clampPanOffset(Offset(100f, 100f), 1f, vp))
        assertEquals(Offset.Zero, clampPanOffset(Offset(100f, 100f), 0.5f, vp))
        assertEquals(Offset.Zero, clampPanOffset(Offset(-500f, -500f), 0f, vp))
    }

    @Test
    fun `scale 2 with 1000x2000 viewport clamps to 500x1000`() {
        // Overhang per side = viewportSize * (scale - 1) / 2 = 500x1000
        val vp = IntSize(1000, 2000)
        assertEquals(
            Offset(500f, 1000f),
            clampPanOffset(Offset(800f, 1500f), 2f, vp)
        )
    }

    @Test
    fun `negative direction clamped symmetrically`() {
        val vp = IntSize(1000, 2000)
        assertEquals(
            Offset(-500f, -1000f),
            clampPanOffset(Offset(-800f, -1500f), 2f, vp)
        )
    }

    @Test
    fun `inside bounds unchanged`() {
        val vp = IntSize(1000, 2000)
        assertEquals(
            Offset(100f, 200f),
            clampPanOffset(Offset(100f, 200f), 2f, vp)
        )
    }

    @Test
    fun `zero viewport returns zero`() {
        // LazyColumn not yet measured — safe degenerate input.
        assertEquals(
            Offset.Zero,
            clampPanOffset(Offset(100f, 100f), 2f, IntSize.Zero)
        )
    }

    @Test
    fun `scale 3 with 1080x2400 viewport clamps to 1080x2400`() {
        // Realistic phone viewport at max continuous-scroll zoom.
        // maxX = 1080 * (3-1) / 2 = 1080
        // maxY = 2400 * (3-1) / 2 = 2400
        val vp = IntSize(1080, 2400)
        assertEquals(
            Offset(1080f, 2400f),
            clampPanOffset(Offset(9999f, 9999f), 3f, vp)
        )
    }

    @Test
    fun `exactly at boundary passes through unchanged`() {
        val vp = IntSize(1000, 2000)
        // 500 is exactly the boundary for scale=2 viewport=1000; clamp is inclusive.
        assertEquals(
            Offset(500f, 1000f),
            clampPanOffset(Offset(500f, 1000f), 2f, vp)
        )
        assertEquals(
            Offset(-500f, -1000f),
            clampPanOffset(Offset(-500f, -1000f), 2f, vp)
        )
    }

    @Test
    fun `asymmetric viewport clamped independently per axis`() {
        // Wide content: horizontal overflow is much larger than vertical.
        val vp = IntSize(2400, 1080)
        // scale=1.5: maxX = 2400 * 0.5 / 2 = 600; maxY = 1080 * 0.5 / 2 = 270
        assertEquals(
            Offset(600f, 270f),
            clampPanOffset(Offset(9999f, 9999f), 1.5f, vp)
        )
    }
}
