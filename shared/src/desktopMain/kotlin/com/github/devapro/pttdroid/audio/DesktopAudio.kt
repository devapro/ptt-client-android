package com.github.devapro.pttdroid.audio

import com.github.devapro.pttdroid.PttLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** The `javax.sound.sampled` format matching [AudioConfig] exactly: 16 kHz, mono, signed PCM16LE. */
private val PCM_FORMAT = AudioFormat(
    AudioConfig.SAMPLE_RATE_HZ.toFloat(),
    AudioConfig.BYTES_PER_SAMPLE * 8,
    AudioConfig.CHANNEL_COUNT,
    /* signed = */ true,
    /* bigEndian = */ false,
)

/**
 * Desktop microphone capture via `javax.sound.sampled`, mirroring `:app`'s `VoiceRecorder`
 * (`AudioRecord`) semantics: fixed-size frames, realtime/lossy backpressure on [frames], a
 * symmetric start/stop/release lifecycle.
 *
 * One difference from Android forces [FrameAccumulator]'s existence: `TargetDataLine.read` can
 * short-read, where `AudioRecord.read` on Android is just trimmed to the bytes actually captured
 * (`docs/known-issues.md` #5). A short, odd-length frame on the wire would violate the protocol's
 * fixed [AudioConfig.FRAME_BYTES] contract, so reads are assembled through the accumulator instead
 * of emitted straight off one `read` call.
 *
 * No capture device, or a device that refuses to open, is not fatal: [start] then simply never
 * launches a read loop, [frames] stays empty, and the failure is logged exactly once (not per
 * frame) so the app still runs as a listen-only client rather than crashing.
 */
class DesktopVoiceRecorder(private val scope: CoroutineScope) : VoiceRecorderContract {

    private var line: TargetDataLine? = null
    private var readJob: Job? = null
    private var loggedFailure = false

    /** Same realtime/lossy semantics as `:app`'s `VoiceRecorder`: drop the oldest frame rather
     *  than let a slow consumer build an unbounded backlog of stale audio. */
    override val frames: Channel<ByteArray> = Channel(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun ensureLine(): TargetDataLine? {
        line?.let { return it }

        val info = DataLine.Info(TargetDataLine::class.java, PCM_FORMAT)
        if (!AudioSystem.isLineSupported(info)) {
            logFailureOnce { "No capture device supports 16 kHz mono PCM16; running listen-only" }
            return null
        }

        // Matches Android's max(minBufferSize, FRAME_BYTES * 4) depth — javax.sound.sampled has
        // no minimum-buffer-size query of its own, so this is just the floor directly.
        val bufferBytes = AudioConfig.FRAME_BYTES * 4
        val opened = try {
            (AudioSystem.getLine(info) as TargetDataLine).apply { open(PCM_FORMAT, bufferBytes) }
        } catch (e: LineUnavailableException) {
            logFailureOnce(e) { "Could not open capture line; running listen-only" }
            return null
        } catch (e: IllegalArgumentException) {
            logFailureOnce(e) { "Capture line rejected the requested format/buffer; running listen-only" }
            return null
        }

        line = opened
        return opened
    }

    private fun logFailureOnce(throwable: Throwable? = null, message: () -> String) {
        if (loggedFailure) return
        loggedFailure = true
        PttLog.w(throwable, message)
    }

    override fun start() {
        if (readJob?.isActive == true) return
        val targetLine = ensureLine() ?: return

        runCatching { targetLine.start() }
            .onFailure {
                logFailureOnce(it) { "TargetDataLine.start failed; running listen-only" }
                return
            }

        readJob = scope.launch(Dispatchers.IO) {
            val accumulator = FrameAccumulator()
            val readBuffer = ByteArray(AudioConfig.FRAME_BYTES)
            while (isActive && targetLine.isOpen) {
                // Blocks until at least one byte is available; may return fewer bytes than asked
                // for (the short-read case FrameAccumulator exists to handle).
                val read = targetLine.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) continue
                for (frame in accumulator.accumulate(readBuffer.copyOf(read))) {
                    frames.trySend(frame)
                }
            }
        }
    }

    override fun stop() {
        readJob?.cancel()
        readJob = null
        line?.let { l -> if (l.isRunning) runCatching { l.stop() } }
    }

    /** Idempotent; safe to call more than once. */
    override fun release() {
        stop()
        line?.let { l -> runCatching { l.close() } }
        line = null
    }
}

/**
 * Desktop playback via `javax.sound.sampled`, mirroring `:app`'s `VoicePlayer` (`AudioTrack`)
 * semantics.
 *
 * Android's `AudioTrack.write` is called with `WRITE_NON_BLOCKING` so the network receive path
 * never stalls. `SourceDataLine.write` has no non-blocking mode — it blocks until its internal
 * buffer has room — so [play] never calls it directly; frames instead go through a small dropping
 * channel drained by a dedicated IO coroutine, and a slow or stalled line simply loses late audio
 * rather than backing up the caller.
 */
class DesktopVoicePlayer(private val scope: CoroutineScope) : VoicePlayerContract {

    private var line: SourceDataLine? = null
    private var drainJob: Job? = null
    private var loggedFailure = false

    // Recreated on every prepare() so a release() while frames are queued cannot have them played
    // back stale on the next reconnect.
    private var pending: Channel<ByteArray> = newPendingChannel()

    private fun newPendingChannel(): Channel<ByteArray> = Channel(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun logFailureOnce(throwable: Throwable? = null, message: () -> String) {
        if (loggedFailure) return
        loggedFailure = true
        PttLog.w(throwable, message)
    }

    /** Idempotent; safe to call more than once. */
    override fun prepare() {
        if (line != null) return

        val info = DataLine.Info(SourceDataLine::class.java, PCM_FORMAT)
        if (!AudioSystem.isLineSupported(info)) {
            logFailureOnce { "No playback device supports 16 kHz mono PCM16; audio will not play" }
            return
        }

        val bufferBytes = AudioConfig.FRAME_BYTES * 4
        val opened = try {
            (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(PCM_FORMAT, bufferBytes)
                start()
            }
        } catch (e: LineUnavailableException) {
            logFailureOnce(e) { "Could not open playback line; audio will not play" }
            return
        } catch (e: IllegalArgumentException) {
            logFailureOnce(e) { "Playback line rejected the requested format/buffer; audio will not play" }
            return
        }

        line = opened
        drainJob = scope.launch(Dispatchers.IO) {
            for (frame in pending) {
                runCatching { opened.write(frame, 0, frame.size) }
            }
        }
    }

    /** Writes one received frame. No logging here — this runs per audio frame. */
    override fun play(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (line == null) return
        pending.trySend(pcm)
    }

    /** Idempotent; safe to call more than once and from either side of a reconnect. */
    override fun release() {
        val current = line ?: return
        line = null
        drainJob?.cancel()
        drainJob = null
        pending.close()
        pending = newPendingChannel()
        runCatching {
            if (current.isRunning) current.stop()
            current.close()
        }
    }
}
