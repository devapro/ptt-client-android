package com.github.devapro.pttdroid.audio

/**
 * The single source of truth for the audio format on the wire.
 *
 * Protocol v1 fixes these values — there is no negotiation, so recorder and player must not
 * probe for a rate independently (the old code did, with no guarantee the two agreed and no
 * way to tell a peer what it had chosen).
 *
 * The three `android.media.AudioFormat` ints this object used to also carry
 * (`IN_CHANNEL_MASK`/`OUT_CHANNEL_MASK`/`PCM_ENCODING`) are Android-only and live in `:app`'s
 * `AndroidAudioFormat` instead — this object is shared with the desktop target too.
 */
object AudioConfig {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val CHANNEL_COUNT: Int = 1
    const val ENCODING_NAME: String = "pcm16le"

    const val BYTES_PER_SAMPLE: Int = 2

    /** 40 ms of audio: 16000 * 0.04 * 2 bytes. Matches the server's `frameBytes`. */
    const val FRAME_BYTES: Int = 1_280

    /** Upper bound the server enforces on a single binary frame. */
    const val MAX_FRAME_BYTES: Int = 8_192
    /**
     * Two fixed-size frames of a 1 kHz tone for the start of a local broadcast.
     *
     * The waveform is constructed once in common code and sent unchanged through the normal
     * PCM stream, so every receiver hears the same legal PCM16LE signal without a platform
     * audio API. The 40 ms frame duration holds exactly 40 cycles, preserving phase at the
     * frame boundary; the 5 ms envelope starts and ends at zero to avoid a click.
     */
    internal fun broadcastStartBipFrames(): List<ByteArray> = BROADCAST_START_BIP_FRAMES

    private val BROADCAST_START_BIP_FRAMES: List<ByteArray> = buildBroadcastStartBipFrames()

    private fun buildBroadcastStartBipFrames(): List<ByteArray> {
        val frameCount = 2
        val samplesPerFrame = FRAME_BYTES / BYTES_PER_SAMPLE
        val totalSamples = frameCount * samplesPerFrame
        val fadeSamples = SAMPLE_RATE_HZ / 200
        val waveform = intArrayOf(
            0, 6_123, 11_314, 14_782, 16_000, 14_782, 11_314, 6_123,
            0, -6_123, -11_314, -14_782, -16_000, -14_782, -11_314, -6_123,
        )

        return List(frameCount) { frameIndex ->
            ByteArray(FRAME_BYTES).also { frame ->
                repeat(samplesPerFrame) { sampleInFrame ->
                    val sampleIndex = frameIndex * samplesPerFrame + sampleInFrame
                    val envelope = minOf(
                        sampleIndex,
                        totalSamples - 1 - sampleIndex,
                        fadeSamples,
                    )
                    val sample = waveform[sampleIndex % waveform.size] * envelope / fadeSamples
                    val byteIndex = sampleInFrame * BYTES_PER_SAMPLE
                    frame[byteIndex] = sample.toByte()
                    frame[byteIndex + 1] = (sample ushr 8).toByte()
                }
            }
        }
    }
}
