package com.github.devapro.pttdroid.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the "accumulate bytes into exact-size frames" logic used by `DesktopVoiceRecorder` to
 * cope with `TargetDataLine.read` short reads — see the class KDoc. All hardware-independent:
 * a small frame size (4 bytes) stands in for [AudioConfig.FRAME_BYTES] so test data stays
 * readable.
 */
class FrameAccumulatorTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `exact-size chunk yields exactly one frame and no carry`() {
        val accumulator = FrameAccumulator(frameSize = 4)

        val frames = accumulator.accumulate(bytes(1, 2, 3, 4))

        assertEquals(1, frames.size)
        assertContentEquals(bytes(1, 2, 3, 4), frames[0])
        // Nothing left over: the next chunk starts a fresh frame from empty.
        assertTrue(accumulator.accumulate(bytes(5, 6)).isEmpty())
    }

    @Test
    fun `short reads across multiple calls assemble into one frame`() {
        val accumulator = FrameAccumulator(frameSize = 4)

        assertTrue(accumulator.accumulate(bytes(1)).isEmpty())
        assertTrue(accumulator.accumulate(bytes(2, 3)).isEmpty())
        val frames = accumulator.accumulate(bytes(4))

        assertEquals(1, frames.size)
        assertContentEquals(bytes(1, 2, 3, 4), frames[0])
    }

    @Test
    fun `oversized chunk yields multiple frames plus a carried remainder`() {
        val accumulator = FrameAccumulator(frameSize = 4)

        // 10 bytes: two full frames plus a 2-byte remainder.
        val frames = accumulator.accumulate(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))

        assertEquals(2, frames.size)
        assertContentEquals(bytes(1, 2, 3, 4), frames[0])
        assertContentEquals(bytes(5, 6, 7, 8), frames[1])

        // The 2-byte remainder (9, 10) is carried; two more bytes complete the next frame.
        val next = accumulator.accumulate(bytes(11, 12))
        assertEquals(1, next.size)
        assertContentEquals(bytes(9, 10, 11, 12), next[0])
    }

    @Test
    fun `remainder is carried across three separate calls before completing`() {
        val accumulator = FrameAccumulator(frameSize = 4)

        assertTrue(accumulator.accumulate(bytes(1)).isEmpty())
        assertTrue(accumulator.accumulate(bytes(2)).isEmpty())
        assertTrue(accumulator.accumulate(bytes(3)).isEmpty())
        val frames = accumulator.accumulate(bytes(4, 5, 6, 7, 8))

        // The 4th call supplies the last byte of frame 1 plus a full frame 2, with nothing left.
        assertEquals(2, frames.size)
        assertContentEquals(bytes(1, 2, 3, 4), frames[0])
        assertContentEquals(bytes(5, 6, 7, 8), frames[1])
    }

    @Test
    fun `many short reads eventually emit frames in order with no bytes lost or reordered`() {
        val accumulator = FrameAccumulator(frameSize = 4)
        val all = (1..20).toList()
        val emitted = mutableListOf<Int>()

        for (b in all) {
            for (frame in accumulator.accumulate(byteArrayOf(b.toByte()))) {
                frame.forEach { emitted.add(it.toInt()) }
            }
        }

        // 20 bytes fed one at a time -> 5 complete frames of 4, nothing lost, order preserved.
        assertEquals(all, emitted)
    }

    @Test
    fun `empty chunk is a no-op`() {
        val accumulator = FrameAccumulator(frameSize = 4)

        assertTrue(accumulator.accumulate(ByteArray(0)).isEmpty())
        val frames = accumulator.accumulate(bytes(1, 2, 3, 4))
        assertEquals(1, frames.size)
    }

    @Test
    fun `reset drops a partial frame`() {
        val accumulator = FrameAccumulator(frameSize = 4)

        assertTrue(accumulator.accumulate(bytes(1, 2)).isEmpty())
        accumulator.reset()
        val frames = accumulator.accumulate(bytes(3, 4, 5, 6))

        assertEquals(1, frames.size)
        // If reset had not dropped (1, 2), this frame would start with them instead.
        assertContentEquals(bytes(3, 4, 5, 6), frames[0])
    }

    @Test
    fun `default frame size matches the protocol's wire frame size`() {
        val accumulator = FrameAccumulator()

        val frames = accumulator.accumulate(ByteArray(AudioConfig.FRAME_BYTES) { it.toByte() })

        assertEquals(1, frames.size)
        assertEquals(AudioConfig.FRAME_BYTES, frames[0].size)
    }
}
