package com.github.devapro.pttdroid.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * A short start-of-transmission tone, in the wire format.
 *
 * Generated once: the same PCM is played locally (so the talker hears the floor grant) and
 * sent as the first frames of the broadcast (so everyone else hears it too). Not a sound
 * resource — the format is fixed by protocol v1, and a sine wave at that rate is smaller
 * than a WAV header.
 */
object StartBeep {

    const val FREQUENCY_HZ: Int = 1_000

    /** Three 40 ms frames — long enough to register, short enough not to delay speech. */
    const val DURATION_FRAMES: Int = 3

    val DURATION_MS: Int =
        DURATION_FRAMES * AudioConfig.FRAME_BYTES / AudioConfig.BYTES_PER_SAMPLE * 1_000 /
            AudioConfig.SAMPLE_RATE_HZ

    private const val AMPLITUDE: Int = 10_000

    /** 5 ms linear attack/release so the tone does not click at the edges. */
    private const val RAMP_SAMPLES: Int = AudioConfig.SAMPLE_RATE_HZ / 200

    val frames: List<ByteArray> = render()

    private fun render(): List<ByteArray> {
        val samplesPerFrame = AudioConfig.FRAME_BYTES / AudioConfig.BYTES_PER_SAMPLE
        val totalSamples = samplesPerFrame * DURATION_FRAMES
        val out = Array(DURATION_FRAMES) { ByteArray(AudioConfig.FRAME_BYTES) }
        var sampleIndex = 0
        for (frame in 0 until DURATION_FRAMES) {
            val bytes = out[frame]
            var offset = 0
            repeat(samplesPerFrame) {
                val remaining = totalSamples - sampleIndex
                val ramp = when {
                    sampleIndex < RAMP_SAMPLES -> sampleIndex.toDouble() / RAMP_SAMPLES
                    remaining < RAMP_SAMPLES -> remaining.toDouble() / RAMP_SAMPLES
                    else -> 1.0
                }
                val sample = (
                    AMPLITUDE * ramp *
                        sin(2.0 * PI * FREQUENCY_HZ * sampleIndex / AudioConfig.SAMPLE_RATE_HZ)
                    ).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[offset] = (sample and 0xFF).toByte()
                bytes[offset + 1] = ((sample shr 8) and 0xFF).toByte()
                offset += 2
                sampleIndex++
            }
        }
        return out.toList()
    }
}
