package com.github.devapro.pttdroid.audio

/**
 * One blocking PCM read, shaped like `AudioRecord.read(audioData, 0, size)`: fills up to
 * `buffer.size` bytes of [buffer] and returns the number of bytes actually captured, 0 if the
 * read produced nothing, or a negative error code.
 *
 * Exists so [CaptureFrameLoop] — the frame-reshaping part of Android capture — can be unit-
 * tested on the JVM without `AudioRecord` (see `audio/AudioContracts.kt` in `:shared` for the
 * same seam one level up).
 */
internal fun interface PcmReadSource {
    fun read(buffer: ByteArray): Int
}

/**
 * Reshapes whatever a [PcmReadSource] hands back into exact [AudioConfig.FRAME_BYTES] frames.
 *
 * `AudioRecord.read` in blocking mode usually returns the whole buffer, but a stopped or
 * interrupted recording can end a read short — and the protocol fixes the wire frame at 1280
 * bytes, rejecting odd lengths and capping size (`../ptt-server/docs/protocol.md`), so a raw
 * short read must never reach [emit]. This does the same job [FrameAccumulator] does for
 * desktop and iOS capture, fed from Android's read loop instead of a line read or an audio
 * tap; any leftover bytes are carried to the next [step] and a partial frame is simply never
 * emitted (each capture session builds its own loop, so a session's trailing remainder dies
 * with it).
 *
 * Not thread-safe: drive [step] from the single capture coroutine, like the record it wraps.
 */
internal class CaptureFrameLoop(
    private val source: PcmReadSource,
    private val emit: (ByteArray) -> Unit,
    bufferSize: Int = AudioConfig.FRAME_BYTES,
) {

    private val accumulator = FrameAccumulator()
    private val buffer = ByteArray(bufferSize)

    /**
     * Performs one read and feeds the captured bytes — never more than the read returned, the
     * bug `docs/known-issues.md` #5 was about — into the accumulator, calling [emit] once per
     * completed frame, oldest first.
     *
     * Returns the read's byte count: `> 0` captured, `0` nothing this pass, `< 0` the source
     * failed and the caller should stop looping. The error is reported to the caller rather than
     * logged here so this stays free of per-frame side effects.
     */
    fun step(): Int {
        val read = source.read(buffer)
        if (read > 0) {
            accumulator.accumulate(buffer.copyOf(read)).forEach(emit)
        }
        return read
    }
}
