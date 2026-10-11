package com.github.devapro.pttdroid.features.history

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tester's edge cases for the C-6/R1 rounding contract, complementing `HistoryDurationFormatTest`:
 * carries across the minute boundary, second padding after a carry, values at the far side of the
 * cap, and small negative inputs (guard sanity — the store can only ever produce non-negative
 * durations, so the contract here is only "never throw, never a malformed string").
 */
class HistoryDurationFormatEdgeTest {

    @Test
    fun rounding_carries_across_the_minute_boundary() {
        // 1:39.5 rounds half-up to 100 s, which must render as "1:40", not "1:39" or "1:100".
        assertEquals("1:40", formatDuration(99_500))
        assertEquals("1:39", formatDuration(99_499))
        // 59.5 s rolls over into the first minute.
        assertEquals("1:00", formatDuration(59_500))
        assertEquals("0:59", formatDuration(59_499))
        assertEquals("1:00", formatDuration(59_999))
    }

    @Test
    fun carries_keep_two_digit_seconds_padded() {
        // 600.5 s -> 601 s = 10:01 (pad applies to the rounded value, not the raw ms).
        assertEquals("10:01", formatDuration(600_500))
        // 1199.999 s -> 1200 s = 20:00, well past the 2-minute cap the fix was for.
        assertEquals("20:00", formatDuration(1_199_999))
    }

    @Test
    fun values_just_under_the_cap_from_either_side() {
        assertEquals("2:00", formatDuration(119_999))
        assertEquals("2:00", formatDuration(119_501))
        assertEquals("1:59", formatDuration(119_499))
        assertEquals("1:59", formatDuration(119_400)) // whole frames under the boundary stay down
    }

    @Test
    fun small_negative_inputs_round_to_zero_without_mangling() {
        // Unreachable from production data (durationMs derives from pcm.size), pinned only as a
        // guard: anything within half a second under zero still displays "0:00".
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-1))
        assertEquals("0:00", formatDuration(-499))
        assertEquals("0:00", formatDuration(-500))
    }
}
