package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure whole-book progress arithmetic shared by the Wear glance surfaces (tile /
 * complication) and the phone's watch publisher. Pure JVM — no Android, no `PlaybackManager` singleton.
 */
class WholeBookProgressTest {

    @Test fun midBook_isTheFraction() {
        // 300s into a 1200s book → 0.25.
        assertEquals(0.25f, wholeBookProgressFor(positionMs = 300_000, durationSeconds = 1200.0), 0.0001f)
    }

    @Test fun start_isZero() {
        assertEquals(0f, wholeBookProgressFor(positionMs = 0, durationSeconds = 1200.0), 0.0001f)
    }

    @Test fun end_isOne() {
        assertEquals(1f, wholeBookProgressFor(positionMs = 1_200_000, durationSeconds = 1200.0), 0.0001f)
    }

    @Test fun nullDuration_isZero() {
        assertEquals(0f, wholeBookProgressFor(positionMs = 300_000, durationSeconds = null), 0.0001f)
    }

    @Test fun zeroDuration_isZero() {
        assertEquals(0f, wholeBookProgressFor(positionMs = 300_000, durationSeconds = 0.0), 0.0001f)
    }

    @Test fun negativeDuration_isZero() {
        assertEquals(0f, wholeBookProgressFor(positionMs = 300_000, durationSeconds = -5.0), 0.0001f)
    }

    @Test fun positionPastEnd_clampsToOne() {
        assertEquals(1f, wholeBookProgressFor(positionMs = 5_000_000, durationSeconds = 1200.0), 0.0001f)
    }

    @Test fun negativePosition_clampsToZero() {
        assertEquals(0f, wholeBookProgressFor(positionMs = -1_000, durationSeconds = 1200.0), 0.0001f)
    }
}
