package com.github.devapro.pttdroid.audio

import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * Spec tests for the receive-path gap fix: [PlaybackDrainLoop] must hand the device every frame
 * it accepts *in full* — the old non-blocking `AudioTrack.write` dropped whatever did not fit the
 * ~160 ms device buffer, so listeners heard breaks the stored history recording did not have.
 *
 * Drives the [PcmWriteSink] seam directly, as `VoicePlayer` does, with a scripted fake that can
 * park the drain thread inside its first write (modelling a busy device) and partial-write (as
 * `AudioTrack` really does).
 */
class PlaybackDrainLoopTest {

    @Before
    fun plantTimber() {
        // The loop logs lifecycle transitions; with no tree planted those calls reach the
        // unmocked android.util.Log stubs of a plain-JUnit run and throw.
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) = Unit
        })
    }

    @After
    fun uprootTimber() = Timber.uprootAll()

    private val frame = (0 until AudioConfig.FRAME_BYTES).map { (it % 251).toByte() }.toByteArray()

    private fun frameTagged(tag: Int): ByteArray =
        ByteArray(AudioConfig.FRAME_BYTES) { (tag + it).toByte() }

    /**
     * Records every write call as an (offset, chunk) pair. With [gateFirstWrite], the first call
     * parks the drain thread inside the write until [release] — long enough for a test to pile
     * frames into the queue against a busy "device".
     */
    private class FakeSink(
        private val chunkBytes: Int = AudioConfig.FRAME_BYTES,
        private val gateFirstWrite: Boolean = false,
    ) {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, ByteArray>>())

        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun release() = released.countDown()

        /** Blocks until the drain thread is parked inside (or past) its very first write. */
        fun awaitFirstWrite() {
            assertTrue("gated sink never saw a write", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }

        fun write(buffer: ByteArray, offset: Int, count: Int): Int {
            if (calls.size == 0) {
                entered.countDown()
                if (gateFirstWrite) assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            val n = minOf(chunkBytes, count)
            calls += offset to buffer.copyOfRange(offset, offset + n)
            return n
        }
    }

    /** Re-joins partial writes back into the frames they came from, in call order. */
    private fun writtenFrames(sink: FakeSink): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val current = java.io.ByteArrayOutputStream(AudioConfig.FRAME_BYTES)
        for ((offset, chunk) in sink.calls.toList()) {
            if (offset == 0) {
                if (current.size() > 0) frames += current.toByteArray()
                current.reset()
            } else {
                // A continuation must land exactly where the previous write of this frame ended.
                assertEquals(current.size(), offset)
            }
            current.write(chunk)
        }
        if (current.size() > 0) frames += current.toByteArray()
        frames.forEach { assertEquals(AudioConfig.FRAME_BYTES, it.size) }
        return frames
    }

    private fun assertFramesEqual(expected: ByteArray, actual: ByteArray) {
        assertTrue("frames differ", expected.contentEquals(actual))
    }

    @Test
    fun `a burst that would overflow the non-blocking device buffer still reaches it in full`() {
        val sink = FakeSink(gateFirstWrite = true)
        val loop = PlaybackDrainLoop(
            sink = sink::write,
            maxQueuedBytes = 40 * AudioConfig.FRAME_BYTES,
        )
        loop.start()
        loop.enqueue(frameTagged(0))
        sink.awaitFirstWrite()
        repeat(9) { i -> loop.enqueue(frameTagged(i + 1)) }
        sink.release()
        loop.waitForIdle()
        loop.shutdown()

        val written = writtenFrames(sink)
        assertEquals(10, written.size)
        written.forEachIndexed { i, f -> assertFramesEqual(frameTagged(i), f) }
    }

    @Test
    fun `a partial device write is retried at the reported offset until the frame is complete`() {
        val sink = FakeSink(chunkBytes = 100)
        val loop = PlaybackDrainLoop(
            sink = sink::write,
            maxQueuedBytes = 40 * AudioConfig.FRAME_BYTES,
        )
        loop.start()
        loop.enqueue(frame)
        loop.waitForIdle()
        loop.shutdown()

        val written = writtenFrames(sink)
        assertEquals(1, written.size)
        assertFramesEqual(frame, written.first())
        // Genuinely partial, not one big call: every continuation starts where its write returned.
        assertTrue(sink.calls.size > 1)
        assertEquals(0, sink.calls.first().first)
        assertTrue(sink.calls.drop(1).all { (offset, _) -> offset > 0 })
    }

    @Test
    fun `overflow past the byte cap drops the oldest frames, never the newest`() {
        val sink = FakeSink(gateFirstWrite = true)
        val loop = PlaybackDrainLoop(
            sink = sink::write,
            maxQueuedBytes = 4 * AudioConfig.FRAME_BYTES,
        )
        loop.start()
        loop.enqueue(frameTagged(0))
        sink.awaitFirstWrite()
        repeat(8) { i -> loop.enqueue(frameTagged(i + 1)) }
        sink.release()
        loop.waitForIdle()
        loop.shutdown()

        val written = writtenFrames(sink)
        // Frame 0 went to the device through the gate; the cap dropped frames 1–4 and kept 5–8.
        assertEquals(5, written.size)
        assertFramesEqual(frameTagged(0), written.first())
        assertFramesEqual(frameTagged(5), written[1])
        assertFramesEqual(frameTagged(8), written.last())
    }

    @Test
    fun `clear discards queued frames but never the one already at the device`() {
        val sink = FakeSink(gateFirstWrite = true)
        val loop = PlaybackDrainLoop(
            sink = sink::write,
            maxQueuedBytes = 40 * AudioConfig.FRAME_BYTES,
        )
        loop.start()
        loop.enqueue(frameTagged(0))
        // Deterministic: frame 0 is parked inside the gated first write — in flight, not queued.
        sink.awaitFirstWrite()
        repeat(5) { i -> loop.enqueue(frameTagged(i + 1)) }
        loop.clear()
        sink.release()
        loop.waitForIdle()
        loop.shutdown()

        val written = writtenFrames(sink)
        assertEquals(1, written.size)
        assertFramesEqual(frameTagged(0), written.first())
    }

    @Test
    fun `a failed device write tears the loop down instead of spinning on a dead device`() {
        var calls = 0
        val sink = PcmWriteSink { _, _, _ ->
            calls++
            if (calls == 1) -1 else AudioConfig.FRAME_BYTES
        }
        val loop = PlaybackDrainLoop(
            sink = sink,
            maxQueuedBytes = 40 * AudioConfig.FRAME_BYTES,
        )
        loop.start()
        loop.enqueue(frame)
        loop.waitForIdle()
        // The second frame must be rejected because the loop is down, not because of timing luck.
        loop.awaitStopped()
        loop.enqueue(frame)
        loop.waitForIdle()
        loop.shutdown()

        assertEquals(1, calls)
    }

    @Test
    fun `frames enqueued before start are dropped, so a stale backlog cannot play late`() {
        val sink = FakeSink()
        val loop = PlaybackDrainLoop(
            sink = sink::write,
            maxQueuedBytes = 40 * AudioConfig.FRAME_BYTES,
        )
        loop.enqueue(frameTagged(0))
        loop.start()
        loop.waitForIdle()
        loop.shutdown()

        assertEquals(0, sink.calls.size)
    }
}
