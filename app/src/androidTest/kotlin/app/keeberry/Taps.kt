package app.keeberry

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/**
 * Taps a node once it has stopped moving. Typing opens the keyboard, and the centred forms slide up
 * as it animates in, so a tap aimed straight after typing can land where the button used to be. On a
 * small screen the keyboard can cover the button altogether, so it is scrolled into view first.
 */
fun SemanticsNodeInteraction.performClickWhenSettled(compose: ComposeTestRule): SemanticsNodeInteraction {
    var last: Rect? = null
    var stillSince = 0L
    compose.waitUntil(10_000) {
        val bounds = fetchSemanticsNode().boundsInWindow
        val now = SystemClock.uptimeMillis()
        if (bounds != last) {
            last = bounds
            stillSince = now
        }
        // Long enough to cover the gap before the keyboard starts to show, not just its animation.
        now - stillSince >= SETTLED_MS
    }
    return performScrollTo().performClick()
}

private const val SETTLED_MS = 750L
