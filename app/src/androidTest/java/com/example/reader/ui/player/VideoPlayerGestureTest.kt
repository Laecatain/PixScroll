package com.example.reader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented gesture tests for the video player long-press 3x speed feature.
 *
 * Tests the exact gesture pattern used in VideoPlayerScreen:
 * - Block 1: detectTapGestures (tap, double-tap, long-press)
 * - Block 2: awaitPointerEventScope polling (real finger lift detection)
 *
 * Run: ./gradlew connectedDebugAndroidTest --tests "*.VideoPlayerGestureTest"
 */
class VideoPlayerGestureTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Minimal composable replicating VideoPlayerScreen's gesture handling.
     * Reports state changes via callbacks for assertion.
     */
    private fun setGestureContent(
        onTap: () -> Unit = {},
        onDoubleTap: () -> Unit = {},
        onLongPressStart: () -> Unit = {},
        onLongPressEnd: () -> Unit = {}
    ) {
        rule.setContent {
            var isLongPressing = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .semantics { contentDescription = "gesture_area" }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { onDoubleTap() },
                            onLongPress = {
                                isLongPressing.value = true
                                onLongPressStart()
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        while (true) {
                            awaitPointerEventScope {
                                val event = awaitPointerEvent()
                                if (isLongPressing.value) {
                                    val anyReleased = event.changes.any { c -> !c.pressed }
                                    if (anyReleased) {
                                        isLongPressing.value = false
                                        onLongPressEnd()
                                        event.changes.forEach { c -> c.consume() }
                                    }
                                }
                            }
                        }
                    }
            )
        }
    }

    // ── Scenario 1: Short tap ──

    @Test
    fun shortTap_triggersOnTap() {
        var tapped = false
        setGestureContent(onTap = { tapped = true })

        rule.onNodeWithContentDescription("gesture_area")
            .performClick()

        rule.waitUntil(timeoutMillis = 1000) { tapped }
        assertTrue("Short tap should trigger onTap", tapped)
    }

    // ── Scenario 2: Double tap ──

    @Test
    fun doubleTap_triggersOnDoubleTap() {
        var doubleTapped = false
        setGestureContent(onDoubleTap = { doubleTapped = true })

        rule.onNodeWithContentDescription("gesture_area")
            .performTouchInput {
                doubleClick(center)
            }

        rule.waitUntil(timeoutMillis = 1000) { doubleTapped }
        assertTrue("Double tap should trigger onDoubleTap", doubleTapped)
    }

    // ── Scenario 3: Long press start ──

    @Test
    fun longPress_triggersOnLongPressStart() {
        var longPressStarted = false
        setGestureContent(onLongPressStart = { longPressStarted = true })

        rule.onNodeWithContentDescription("gesture_area")
            .performTouchInput {
                longClick(center)
            }

        rule.waitUntil(timeoutMillis = 2000) { longPressStarted }
        assertTrue("Long press should trigger onLongPressStart", longPressStarted)
    }

    // ── Scenario 4: Long press hold + release ──

    @Test
    fun longPressHold_thenRelease_triggersStartThenEnd() {
        var startCount = 0
        var endCount = 0
        setGestureContent(
            onLongPressStart = { startCount++ },
            onLongPressEnd = { endCount++ }
        )

        val node = rule.onNodeWithContentDescription("gesture_area")

        // Simulate: press down, hold for 600ms (exceeds long-press threshold), then release
        node.performTouchInput {
            down(center)
        }

        // Wait for long press to register
        rule.waitUntil(timeoutMillis = 2000) { startCount > 0 }
        assertEquals("Long press should have started", 1, startCount)
        assertEquals("Speed should NOT have reset yet (finger still down)", 0, endCount)

        // Now release
        node.performTouchInput {
            up()
        }

        // Long press end should fire
        rule.waitUntil(timeoutMillis = 1000) { endCount > 0 }
        assertEquals("Long press end should trigger on finger up", 1, endCount)
    }

    // ── Scenario 5: Long press hold for extended period ──
    // This is the KEY test: 3x speed must stay stable while finger is held down.

    @Test
    fun longPress_holdFor3Seconds_speedStaysStable() {
        var startCount = 0
        var endCount = 0
        setGestureContent(
            onLongPressStart = { startCount++ },
            onLongPressEnd = { endCount++ }
        )

        val node = rule.onNodeWithContentDescription("gesture_area")

        node.performTouchInput {
            down(center)
        }

        // Wait for long press
        rule.waitUntil(timeoutMillis = 2000) { startCount > 0 }
        assertEquals(1, startCount)
        assertEquals(0, endCount)

        // Hold for 3 seconds — speed must NOT reset
        rule.mainClock.advanceTimeBy(3000)
        rule.waitForIdle()

        assertEquals("startCount should still be 1 (no duplicate)", 1, startCount)
        assertEquals("endCount should be 0 (finger still down)", 0, endCount)

        // Release
        node.performTouchInput { up() }
        rule.waitUntil(timeoutMillis = 1000) { endCount > 0 }
        assertEquals(1, endCount)
    }

    // ── Scenario 6: Long press + drag (finger moves but stays down) ──

    @Test
    fun longPress_thenDrag_speedStaysStable() {
        var startCount = 0
        var endCount = 0
        setGestureContent(
            onLongPressStart = { startCount++ },
            onLongPressEnd = { endCount++ }
        )

        val node = rule.onNodeWithContentDescription("gesture_area")

        node.performTouchInput {
            down(center)
        }

        rule.waitUntil(timeoutMillis = 2000) { startCount > 0 }
        assertEquals(1, startCount)

        // Drag finger across screen (finger stays down)
        node.performTouchInput {
            moveTo(center.copy(x = center.x + 200f))
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        assertEquals("Speed should NOT reset during drag", 0, endCount)

        // Release
        node.performTouchInput { up() }
        rule.waitUntil(timeoutMillis = 1000) { endCount > 0 }
        assertEquals(1, endCount)
    }

    // ── Scenario 7: Tap does NOT trigger long press ──

    @Test
    fun shortTap_doesNotTriggerLongPress() {
        var longPressStarted = false
        var tapped = false
        setGestureContent(
            onTap = { tapped = true },
            onLongPressStart = { longPressStarted = true }
        )

        rule.onNodeWithContentDescription("gesture_area")
            .performClick()

        rule.waitUntil(timeoutMillis = 1000) { tapped }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()

        assertFalse("Short tap should NOT trigger long press", longPressStarted)
    }
}
