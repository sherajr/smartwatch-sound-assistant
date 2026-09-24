package com.peaceantz.stagescope.ui.rotation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Inject real rotary input through Compose's focus path, not straight into OrientationMath. */
@OptIn(ExperimentalTestApi::class)
class RotaryOrientationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun crownReachesOnlyActivePageAndReturnsAfterDetails() {
        var activePage by mutableIntStateOf(0)
        val deltas = FloatArray(2)
        compose.setContent {
            // Retain both pages, as the pager can do. A one-shot focus request cannot reliably
            // follow these hierarchy changes, and a handler AFTER focusable cannot receive input.
            Box(Modifier.fillMaxSize()) {
                repeat(2) { page ->
                    Box(Modifier.fillMaxSize().hierarchicalFocusGroup(activePage == page)) {
                        Box(
                            Modifier.fillMaxSize()
                                .rotaryOrientation(locked = false) { deltas[page] += it }
                                .testTag("page-$page")
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("page-0").assertIsFocused()
        rotate(30f)
        compose.runOnIdle {
            assertTrue("The focused page must receive the crown event", deltas[0] > 0f)
            assertEquals(0f, deltas[1], 0.01f)
            activePage = 1
        }

        compose.onNodeWithTag("page-1").assertIsFocused()
        val firstPageDelta = compose.runOnIdle { deltas[0] }
        rotate(30f)
        compose.runOnIdle {
            assertEquals(firstPageDelta, deltas[0], 0.01f)
            assertTrue("Swiping pages must transfer crown input", deltas[1] > 0f)
            activePage = -1 // Neither main page is active while Details owns the hierarchy.
        }
        compose.waitForIdle()
        compose.runOnIdle { activePage = 0 }
        compose.onNodeWithTag("page-0").assertIsFocused()
        rotate(30f)
        compose.runOnIdle {
            assertTrue("Returning to a retained page must restore crown input", deltas[0] > firstPageDelta)
        }
    }

    @Test
    fun lockingStopsRotationAndUnlockingResumesWithoutRecreatingPage() {
        var locked by mutableStateOf(false)
        var received = 0f
        compose.setContent {
            Box(
                Modifier.fillMaxSize()
                    .hierarchicalFocusGroup(active = true)
                    .rotaryOrientation(locked = locked) { received += it }
            )
        }
        rotate(30f)
        val beforeLock = compose.runOnIdle { received }
        assertTrue(beforeLock > 0f)
        compose.runOnIdle { locked = true }
        rotate(30f)
        compose.runOnIdle { assertEquals(beforeLock, received, 0.01f) }
        compose.runOnIdle { locked = false }
        rotate(30f)
        compose.runOnIdle { assertTrue(received > beforeLock) }
    }

    private fun rotate(pixels: Float) {
        compose.onRoot().performRotaryScrollInput { rotateToScrollVertically(pixels) }
    }
}
