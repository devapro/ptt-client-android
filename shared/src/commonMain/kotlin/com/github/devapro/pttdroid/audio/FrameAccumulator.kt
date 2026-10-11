package com.github.devapro.pttdroid.audio

/**
 * Assembles arbitrarily-sized byte chunks into fixed-size frames of [frameSize] bytes.
 *
 * Exists for capture APIs that can short-read — `javax.sound.sampled.TargetDataLine.read` on
 * desktop and `android.media.AudioRecord.read` on Android (in its stopped/interrupted cases) do
 * not guarantee they fill the buffer they were given, and iOS hardware tap buffers are
 * frame-sized only by luck. Trimming a short read down to the bytes captured and shipping that —
 * what Android used to do (see `docs/known-issues.md` #5) — would put a short, odd-length frame
 * on the wire, violating the protocol's fixed 1280-byte frame contract. This class instead
 * carries any leftover bytes to the next call, so every frame handed out is exactly [frameSize]
 * bytes — never short, never long. All three platforms' capture feeds chunks through it.
 *
 * Not thread-safe: feed it from a single reader, same as the line it wraps.
 */
class FrameAccumulator(private val frameSize: Int = AudioConfig.FRAME_BYTES) {

    private var carry: ByteArray = ByteArray(0)

    /**
     * Feeds [chunk] into the accumulator and returns zero or more complete [frameSize]-byte
     * frames, oldest first. [chunk] may be smaller than a frame (a short read), exactly one
     * frame, larger than one frame, or an exact multiple of the frame size — any of these can
     * repeat across calls, and any remainder is carried to the next call.
     */
    fun accumulate(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()

        val combined = if (carry.isEmpty()) chunk else carry + chunk
        val frameCount = combined.size / frameSize
        if (frameCount == 0) {
            carry = combined
            return emptyList()
        }

        val frames = ArrayList<ByteArray>(frameCount)
        var offset = 0
        repeat(frameCount) {
            frames.add(combined.copyOfRange(offset, offset + frameSize))
            offset += frameSize
        }
        carry = if (offset == combined.size) ByteArray(0) else combined.copyOfRange(offset, combined.size)
        return frames
    }

    /** Drops any partial frame carried across calls. Not needed between normal start/stop cycles
     *  (each capture session builds its own accumulator), but available for callers that reuse
     *  one instance and want to discard a stale partial frame explicitly (e.g. after an error).
     */
    fun reset() {
        carry = ByteArray(0)
    }
}
