package com.github.devapro.pttdroid.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartBeepTest {

    private fun samples(pcm: ByteArray): List<Int> =
        (0 until pcm.size / 2).map {
            ((pcm[it * 2 + 1].toInt() shl 8) or (pcm[it * 2].toInt() and 0xFF)).toShort().toInt()
        }

    @Test
    fun `the tone is three 40 ms wire frames`() {
        assertEquals(3, StartBeep.frames.size)
        assertEquals(120, StartBeep.DURATION_MS)
        StartBeep.frames.forEach { frame ->
            assertEquals(AudioConfig.FRAME_BYTES, frame.size)
        }
    }

    @Test
    fun `the tone is not silence and ramps at the edges`() {
        val all = StartBeep.frames.flatMap { samples(it) }
        val peak = all.maxOf { abs(it) }
        assertTrue(peak > 1_000, "a start-of-talk tone must actually make a sound")
        assertTrue(all.max() > 0 && all.min() < 0, "a sine has to swing both ways")
        // Linear 5 ms ramps: sample 0 is the start of the attack, so it is silence.
        assertEquals(0, all.first())
        assertTrue(abs(all.last()) < peak / 4, "release ramp should quiet the last sample")
    }
}
