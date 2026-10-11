package com.github.devapro.pttdroid.features.history

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The C-6/R1 contract: `mm:ss` rounds to the nearest second (half up) instead of truncating.
 *
 * The QA symptom was a record at the 2-minute retention cap displaying "1:59": the cap is
 * frame-granular (whole 40 ms wire frames), so `durationMs` of a capped record undershoots
 * 120 000 (e.g. 119 960), which the old truncating division floored to 119 s.
 */
class HistoryDurationFormatTest {

    @Test
    fun exactly_the_two_minute_cap_displays_2_00() {
        assertEquals("2:00", formatDuration(120_000))
    }

    @Test
    fun a_frame_undershot_cap_displays_2_00() {
        // 2999 whole 40 ms frames: the real shape of a capped record's durationMs.
        assertEquals("2:00", formatDuration(119_960))
        // The acceptance case: 119.6 s is nearer 120 than 119, so it rounds to 2:00.
        assertEquals("2:00", formatDuration(119_600))
        // The exact half — half rounds up.
        assertEquals("2:00", formatDuration(119_500))
    }

    @Test
    fun just_under_the_rounding_boundary_stays_1_59() {
        assertEquals("1:59", formatDuration(119_499))
    }

    @Test
    fun sub_second_records_round_to_the_nearest_second() {
        // 0.4 s is nearer 0 than 1 — the display is "0:00", not "0:01". (Same mm:ss shape as
        // every other value; there is no separate bare-seconds format to keep consistent.)
        assertEquals("0:00", formatDuration(400))
        assertEquals("0:00", formatDuration(499))
        // 0.5 s is the half, and halves round up.
        assertEquals("0:01", formatDuration(500))
        assertEquals("0:00", formatDuration(0))
    }

    @Test
    fun mid_values_under_the_rounding_boundary_display_unchanged() {
        // Whole and near-whole seconds — what truncation already got right — stay identical.
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("0:45", formatDuration(45_000))
        assertEquals("0:45", formatDuration(45_400)) // .4 undershoot: no bump
        assertEquals("1:05", formatDuration(65_200)) // .2 undershoot: no bump
        assertEquals("10:30", formatDuration(630_000))
    }

    @Test
    fun mid_values_over_the_rounding_boundary_round_up() {
        assertEquals("0:46", formatDuration(45_600))
        assertEquals("1:16", formatDuration(75_500))
    }
}
