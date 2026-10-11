package com.github.devapro.pttdroid.audio

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * One write to the playback device, shaped like `AudioTrack.write(audioData, offsetInBytes, size)`:
 * pushes up to [count] bytes of [buffer] starting at [offset] for real-time consumption and
 * returns the number of bytes actually accepted, which may be fewer than asked for, or a negative
 * error code.
 *
 * Exists so [PlaybackDrainLoop] — the buffering part of Android playback — can be unit-tested on
 * the JVM without `AudioTrack`, the same seam idea as [PcmReadSource] one direction over.
 */
internal fun interface PcmWriteSink {
    fun write(buffer: ByteArray, offset: Int, count: Int): Int
}

/**
 * Buffers received PCM frames off the receive path and writes them to the device from a dedicated
 * drain thread, so live audio survives both halves of a bad network.
 *
 * The receive path used to call `AudioTrack.write(WRITE_NON_BLOCKING)` once per frame from the
 * session collector: a ~160 ms device buffer is the only slack, so an arrival burst after a CPU
 * stall (background Doze throttling, GC, the connection's own backlog releasing 64 frames at
 * once) overflows the non-blocking write and the excess vanishes — while the receiver-side history
 * capture, fed from the very same frames, stores them all. The user heard gaps live and a clean
 * recording afterwards. The mirror case is just as bad: network jitter longer than the device
 * buffer drains it and the track emits silence.
 *
 * Both are fixed here rather than in the `AudioTrack` itself: [enqueue] parks frames in a bounded
 * FIFO (full ⇒ drop the oldest, the same realtime bias `VoiceRecorder`'s capture channel uses,
 * bounded by [maxQueuedBytes]), and one drain thread does *full* writes — retrying a partial at
 * the offset the device reports and blocking only as long as the hardware needs — so the caller
 * on the receive path never waits on audio hardware and nothing is discarded merely because the
 * device was momentarily busy.
 *
 * Not a jitter buffer in the streaming sense: frames carry no timestamps and there is no
 * concealment — this absorbs timing, not loss.
 */
internal class PlaybackDrainLoop(
    private val sink: PcmWriteSink,
    private val maxQueuedBytes: Int,
    private val frameBytes: Int = AudioConfig.FRAME_BYTES,
    /** Test hook: how long [waitForIdle] refuses to give up. */
    private val idleTimeoutMs: Long = 5_000,
    /** How long [enqueue] waits for queue room before dropping the oldest frame and retrying;
     *  the drain thread consumes in real time, so this is only ever hit by a wedged device. */
    private val enqueueTimeoutMs: Long = 200,
) {
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>()

    /** True while the drain thread holds a frame mid-write, so "idle" excludes a partial in flight. */
    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var drainThread: Thread? = null

    /** Idempotent: a second call while running is a no-op, and so is one after [shutdown]. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = Thread(::run, "PttPlaybackDrain")
        drainThread = thread
        thread.isDaemon = true
        thread.start()
    }

    fun shutdown() {
        running.set(false)
        val thread = drainThread
        drainThread = null
        // The drain thread exits on its next poll; joining a never-started or self-joined
        // thread is simply skipped.
        if (thread !== null && thread !== Thread.currentThread()) {
            runCatching { thread.join(TimeUnit.SECONDS.toMillis(2)) }
                .onFailure { Timber.d("Interrupted joining the playback drain thread: %s", it.toString()) }
        }
        queue.clear()
    }

    /**
     * Hands one frame to the drain thread without blocking the caller for longer than
     * [enqueueTimeoutMs] per attempt. A queue at its byte cap drops its oldest frames — the fresh
     * audio is the audio still worth hearing — until there is room.
     */
    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty() || !running.get()) return
        var fullWarned = false
        while (running.get()) {
            while (queue.size * frameBytes >= maxQueuedBytes) {
                if (queue.poll() === null) break
            }
            if (queue.offer(pcm, enqueueTimeoutMs, TimeUnit.MILLISECONDS)) return
            // Only reachable when the drain thread is not consuming — a wedged or dead device,
            // since the byte cap above keeps the queue below `offer`'s own capacity. Drop toward
            // the fresh end rather than growing latency without bound.
            if (!fullWarned) {
                fullWarned = true
                Timber.w("Playback drain queue is not draining; dropping oldest frames")
            }
            queue.poll()
        }
    }

    /** Drops everything queued but not yet handed to the device. Safe while running. */
    fun clear() {
        queue.clear()
    }

    /**
     * Blocks until every enqueued frame has been fully written (an in-flight partial write
     * included). Only for tests: on the audio path [enqueue] is fire-and-forget by design.
     */
    fun waitForIdle() {
        val deadline = System.currentTimeMillis() + idleTimeoutMs
        while (queue.isNotEmpty() || inFlight.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Playback drain loop did not go idle within ${idleTimeoutMs}ms")
            }
            Thread.sleep(2)
        }
    }

    /**
     * Blocks until the loop has stopped running — naturally, via [shutdown], or because the sink
     * failed a write. Only for tests and lifecycle barriers.
     */
    fun awaitStopped() {
        val deadline = System.currentTimeMillis() + idleTimeoutMs
        while (running.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Playback drain loop still running after ${idleTimeoutMs}ms")
            }
            Thread.sleep(2)
        }
    }

    private fun run() {
        Timber.i("Playback drain thread started")
        try {
            while (running.get()) {
                val frame = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                inFlight.set(true)
                val ok = try {
                    drainFrame(frame)
                } finally {
                    inFlight.set(false)
                }
                if (!ok) running.set(false)
            }
        } catch (e: InterruptedException) {
            // Shutdown of the thread itself; nothing queued survives it.
            Thread.currentThread().interrupt()
        } finally {
            queue.clear()
            if (running.get()) Timber.w("Playback drain thread stopped on a device write failure")
            else Timber.i("Playback drain thread stopped")
        }
    }

    /**
     * Writes [frame] to the sink in full, retrying from the offset each partial reports. Returns
     * false if the sink errored or threw — [run] then tears the loop down and the player's next
     * lifecycle event rebuilds it, which is how a device that died mid-transmission recovers.
     */
    private fun drainFrame(frame: ByteArray): Boolean {
        var offset = 0
        while (offset < frame.size) {
            val written = runCatching { sink.write(frame, offset, frame.size - offset) }
                .getOrElse { return false }
            when {
                written > 0 -> offset += written
                written == 0 -> TimeUnit.MILLISECONDS.sleep(2)
                else -> return false
            }
        }
        return true
    }
}
