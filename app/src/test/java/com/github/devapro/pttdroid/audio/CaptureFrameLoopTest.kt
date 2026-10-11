package com.github.devapro.pttdroid.audio

import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec tests for C-2: Android mic capture must emit only exact 1280-byte PCM frames.
 *
 * Drives the internal [CaptureFrameLoop] seam directly through [PcmReadSource], as
 * `VoiceRecorder` does, and asserts the frame contract from the requirements: every emitted
 * frame is exactly [AudioConfig.FRAME_BYTES], short/odd reads are carried and never emitted,
 * a partial at stop is discarded (fresh loop per start), byte order is preserved across
 * carries, `step()` returns the raw read count, and source errors propagate.
 */
class CaptureFrameLoopTest {

    private val frameBytes = AudioConfig.FRAME_BYTES // 1280

    /** Collects everything [CaptureFrameLoop] emits. */
    private class Sink {
        val emitted = mutableListOf<ByteArray>()
        fun emit(frame: ByteArray) {
            emitted += frame
        }
    }

    /**
     * A [PcmReadSource] that replays a scripted sequence of read results. Each scripted entry
     * is a function that fills the given buffer however it likes and reports the byte count,
     * exactly like `AudioRecord.read` does.
     */
    private class ScriptedSource(private vararg val steps: (ByteArray) -> Int) : PcmReadSource {
        var calls = 0
        override fun read(buffer: ByteArray): Int {
            val step = steps.getOrNull(calls)
                ?: throw AssertionError("source read more times ($calls) than scripted (${steps.size})")
            calls++
            return step(buffer)
        }
    }

    /** Writes `pattern` into buffer and returns only [returned] of them (a short read). */
    private fun writeN(pattern: (Int) -> Byte, returned: Int): (ByteArray) -> Int = { buffer ->
        for (i in 0 until maxOf(returned, 0)) buffer[i] = pattern(i)
        returned
    }

    /** Writes a byte whose value encodes its absolute stream position, so order is checkable. */
    private fun posByte(base: Int): (Int) -> Byte = { i -> ((base + i) % 251).toByte() }

    // ---------- R1: every emitted frame is exactly FRAME_BYTES ----------

    @Test
    fun exactFrameSizedReadEmitsOneExactFrame() {
        val sink = Sink()
        val source = ScriptedSource(writeN(posByte(0), frameBytes))
        val loop = CaptureFrameLoop(source, sink::emit)

        assertEquals(frameBytes, loop.step())
        assertEquals(1, sink.emitted.size)
        assertEquals(frameBytes, sink.emitted[0].size)
        for (i in 0 until frameBytes) assertEquals(posByte(0)(i), sink.emitted[0][i])
    }

    @Test
    fun shortReadIsCarriedAndNeverEmittedAlone() {
        val sink = Sink()
        // 517 bytes is the short-read size called out in the spec; nothing may be emitted.
        val source = ScriptedSource(writeN(posByte(0), 517))
        val loop = CaptureFrameLoop(source, sink::emit)

        assertEquals(517, loop.step())
        assertTrue("a 517-byte short read must not emit a frame", sink.emitted.isEmpty())
    }

    @Test
    fun oddLengthReadsAreCarriedAndNeverEmitted() {
        val sink = Sink()
        // 3 + 5 = 8, 8 + 7 = 15 ... all odd, none may ever reach the wire.
        val sizes = listOf(3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47)
        val source = ScriptedSource(*sizes.map { n -> writeN(posByte(0), n) }.toTypedArray())
        val loop = CaptureFrameLoop(source, sink::emit)
        sizes.forEach { n ->
            assertEquals(n, loop.step())
            sink.emitted.forEach { f -> assertEquals(frameBytes, f.size) }
        }
        assertTrue("only ${sizes.sum()} bytes fed, under one frame, nothing may emit", sink.emitted.isEmpty())
    }

    @Test
    fun carryAcrossStepsPreservesExactByteSequence() {
        val sink = Sink()
        // 517 + 1280 = 1797 -> one frame of 1280 (517 from read 1 + 763 from read 2),
        // remainder 517 carried into read 3 (1280) -> 1797 -> second frame + 517 carried again.
        val source = ScriptedSource(
            writeN(posByte(0), 517),
            writeN(posByte(517), frameBytes),
            writeN(posByte(517 + frameBytes), frameBytes),
        )
        val loop = CaptureFrameLoop(source, sink::emit)
        repeat(3) { loop.step() }

        assertEquals(2, sink.emitted.size)
        val concatenated = sink.emitted[0] + sink.emitted[1]
        assertEquals(2 * frameBytes, concatenated.size)
        // Every emitted byte equals the original stream byte at that position.
        for (i in 0 until concatenated.size) assertEquals(((i) % 251).toByte(), concatenated[i])
    }

    @Test
    fun oversizedReadEmitsMultipleFramesOldestFirst() {
        val sink = Sink()
        val total = 3 * frameBytes + 44 // oversized relative to one frame, with a remainder
        val source = ScriptedSource(writeN(posByte(0), total))
        // bufferSize must be >= the read the fake "AudioRecord" returns; the default loop
        // buffer is FRAME_BYTES, so construct with the matching size for this scenario.
        val loop = CaptureFrameLoop(source, sink::emit, bufferSize = total)

        assertEquals(total, loop.step())
        assertEquals(3, sink.emitted.size)
        sink.emitted.forEach { f -> assertEquals(frameBytes, f.size) }
        // oldest first: frame k is bytes [k*1280, (k+1)*1280) of the stream
        for (k in 0 until 3) {
            for (i in 0 until frameBytes) {
                assertEquals(posByte(0)(k * frameBytes + i), sink.emitted[k][i])
            }
        }
    }

    @Test
    fun randomizedReadSizesAlwaysYieldExactFrames() {
        val sink = Sink()
        val rng = Random(42)
        val sizes = List(200) { rng.nextInt(1, 2560) }
        // Each read's bytes must continue the global stream pattern: base = prefix sum.
        var prefix = 0
        val source = ScriptedSource(*sizes.map { n ->
            writeN(posByte(prefix), n).also { prefix += n }
        }.toTypedArray())
        val loop = CaptureFrameLoop(source, sink::emit, bufferSize = 2560)
        val stream = ByteArray(sizes.sum())
        // Rebuild the same stream independently: byte at position p is (p % 251).
        for (i in stream.indices) stream[i] = (i % 251).toByte()
        sizes.forEach { loop.step() }

        val expected = stream.size / frameBytes
        assertEquals(expected, sink.emitted.size)
        sink.emitted.forEach { f -> assertEquals(frameBytes, f.size) }
        val concatenated = sink.emitted.reduce { a, b -> a + b }
        assertTrue(stream.copyOfRange(0, concatenated.size).contentEquals(concatenated))
        assertEquals(0, sink.emitted.sumOf { it.size } % frameBytes)
    }

    @Test
    fun negativeReadReturnsNegativeAndEmitsNothing() {
        val sink = Sink()
        // 700 carried (no frame), then a failure code.
        val source = ScriptedSource(writeN(posByte(0), 700), { _ -> -1 })
        val loop = CaptureFrameLoop(source, sink::emit)

        assertEquals(700, loop.step())
        assertEquals(-1, loop.step())
        assertTrue(sink.emitted.isEmpty())
    }

    @Test
    fun zeroReadEmitsNothingAndReportsZero() {
        val sink = Sink()
        val source = ScriptedSource({ _ -> 0 })
        val loop = CaptureFrameLoop(source, sink::emit)
        assertEquals(0, loop.step())
        assertTrue(sink.emitted.isEmpty())
    }

    @Test
    fun sourceErrorPropagatesFromStep() {
        val sink = Sink()
        val boom = IOException("read failed")
        val loop = CaptureFrameLoop({ _ -> throw boom }, sink::emit)
        try {
            loop.step()
            fail("expected the source's exception to propagate so the caller can react")
        } catch (e: IOException) {
            assertEquals(boom, e)
        }
        assertTrue(sink.emitted.isEmpty())
    }

    // ---------- R2: buffer.copyOf(read) — no stale tail ----------

    @Test
    fun shortReadDoesNotEmitStaleBufferTail() {
        val sink = Sink()
        // Read 1: buffer is fully overwritten with 0x41 but the read REPORTS only 640 — the
        // remaining 640 bytes of the buffer are stale garbage that must not be accumulated.
        // Read 2: reports 640 of 0x42 -> the only frame must be 640 A's then 640 B's.
        val source = ScriptedSource(
            { buffer -> buffer.fill(0x41); 640 },
            { buffer -> buffer.fill(0x42); 640 },
        )
        val loop = CaptureFrameLoop(source, sink::emit)
        loop.step()
        assertTrue(sink.emitted.isEmpty())
        loop.step()

        assertEquals(1, sink.emitted.size)
        val frame = sink.emitted[0]
        assertEquals(frameBytes, frame.size)
        for (i in 0 until 640) assertEquals("stale tail leaked at $i", 0x41.toByte(), frame[i])
        for (i in 640 until frameBytes) assertEquals("wrong second half at $i", 0x42.toByte(), frame[i])
    }

    // ---------- R1: partial at stop discarded; fresh accumulator per start() ----------

    @Test
    fun trailingPartialDiesWithTheLoopAndANewLoopStartsFresh() {
        // Session 1: feed 3000 bytes -> 2 frames, 440 carried, then "stop": the loop is
        // dropped. Nothing further may be emitted.
        val sink1 = Sink()
        val source1 = ScriptedSource(writeN(posByte(0), 3000))
        val loop1 = CaptureFrameLoop(source1, sink1::emit, bufferSize = 3000)
        loop1.step()
        assertEquals(2, sink1.emitted.size)

        // Session 2 (a fresh start()): brand-new loop + new source. Its first exact read must
        // emit exactly one clean frame — none of session 1's 440-byte remainder anywhere.
        val sink2 = Sink()
        val source2 = ScriptedSource(writeN(posByte(3000), frameBytes))
        val loop2 = CaptureFrameLoop(source2, sink2::emit, bufferSize = frameBytes)
        loop2.step()
        assertEquals(1, sink2.emitted.size)
        assertEquals(frameBytes, sink2.emitted[0].size)
        for (i in 0 until frameBytes) assertEquals(posByte(3000)(i), sink2.emitted[0][i])
    }

    // ---------- R3 (unit-level): the buffer size VoiceRecorder actually uses ----------

    @Test
    fun defaultBufferSizeMatchesFrameBytes() {
        // VoiceRecorder constructs CaptureFrameLoop without an explicit bufferSize, so the
        // default must be the wire frame size: a source that writes the whole buffer it is
        // given must produce exactly one frame per step.
        val sink = Sink()
        var written = -1
        val source = PcmReadSource { buffer ->
            buffer.fill(0x7f)
            written = buffer.size
            buffer.size
        }
        val loop = CaptureFrameLoop(source, sink::emit)
        loop.step()
        assertEquals(frameBytes, written)
        assertEquals(1, sink.emitted.size)
        assertEquals(frameBytes, sink.emitted[0].size)
    }
}
