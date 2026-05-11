package com.example.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderUtilsTest {

    @Test
    fun `normal value rounds to nearest int`() {
        assertEquals(5, clampSliderTarget(5.3f, 10))
        assertEquals(6, clampSliderTarget(5.7f, 10))
        assertEquals(3, clampSliderTarget(3.0f, 10))
    }

    @Test
    fun `lower bound clamped to 0`() {
        assertEquals(0, clampSliderTarget(0f, 10))
        assertEquals(0, clampSliderTarget(-1f, 10))
        assertEquals(0, clampSliderTarget(-100f, 10))
    }

    @Test
    fun `upper bound clamped to last index`() {
        assertEquals(9, clampSliderTarget(9f, 10))
        assertEquals(9, clampSliderTarget(10f, 10))
        assertEquals(9, clampSliderTarget(100f, 10))
    }

    @Test
    fun `single item always returns 0`() {
        assertEquals(0, clampSliderTarget(0f, 1))
        assertEquals(0, clampSliderTarget(0.5f, 1))
        assertEquals(0, clampSliderTarget(1f, 1))
        assertEquals(0, clampSliderTarget(5f, 1))
        assertEquals(0, clampSliderTarget(-5f, 1))
    }

    @Test
    fun `empty list always returns 0 without crash`() {
        assertEquals(0, clampSliderTarget(0f, 0))
        assertEquals(0, clampSliderTarget(1f, 0))
        assertEquals(0, clampSliderTarget(-1f, 0))
        assertEquals(0, clampSliderTarget(100f, 0))
    }

    @Test
    fun `zero items with very large slider value`() {
        assertEquals(0, clampSliderTarget(Float.MAX_VALUE, 0))
    }

    @Test
    fun `two items midpoint rounds correctly`() {
        assertEquals(0, clampSliderTarget(0f, 2))
        assertEquals(0, clampSliderTarget(0.4f, 2))
        assertEquals(1, clampSliderTarget(0.5f, 2))
        assertEquals(1, clampSliderTarget(1f, 2))
        assertEquals(1, clampSliderTarget(1.5f, 2))
        assertEquals(1, clampSliderTarget(5f, 2))
    }

    @Test
    fun `slider at extremes for large list`() {
        assertEquals(0, clampSliderTarget(0f, 500))
        assertEquals(499, clampSliderTarget(499f, 500))
        assertEquals(499, clampSliderTarget(500f, 500))
        assertEquals(499, clampSliderTarget(999f, 500))
    }

    @Test
    fun `fractional step near boundary is clamped`() {
        assertEquals(0, clampSliderTarget(-0.001f, 10))
        assertEquals(9, clampSliderTarget(9.001f, 10))
        assertEquals(9, clampSliderTarget(8.999f, 10))
    }

    @Test
    fun `phase2 growth clamp uses latest item count`() {
        // After Phase 2 merges unindexed files, itemCount grows.
        // The same value should clamp against the CURRENTLY passed itemCount,
        // not a stale capture from before the merge.
        assertEquals(14, clampSliderTarget(14f, 15))   // new max after growth
        assertEquals(9,  clampSliderTarget(14f, 10))   // old max before growth
        assertEquals(0,  clampSliderTarget(14f, 0))    // empty -> 0, never out of bounds
    }
}
