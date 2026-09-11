package com.github.devapro.pttdroid.audio

/**
 * A linear gain applied to PCM16LE samples, for the platforms whose playback API offers no
 * volume control of its own.
 *
 * Android does not use this — `AudioTrack.setVolume` is a real hardware/mixer gain and costs
 * nothing per frame — and neither does iOS, which folds the same multiply into the Int16 →
 * Float32 conversion it was already performing. `DesktopVoicePlayer` is the caller:
 * `javax.sound.sampled` only exposes `MASTER_GAIN`/`VOLUME` when the mixer happens to implement
 * them, they are decibel-scaled with a device-dependent floor, and there is no fallback when a
 * line reports neither.
 *
 * Lives in `commonMain` rather than beside its one caller in `desktopMain` so it is covered by
 * `commonTest` — the arithmetic (little-endian byte order, sign extension, clamping) is exactly
 * the kind that is wrong silently, and a bug here is heard as distortion rather than seen as a
 * failure.
 */
object PcmGain {

    /**
     * Returns [pcm] scaled by [gain], or [pcm] itself when there is nothing to do.
     *
     * A gain of 1 or more returns the input array untouched, so full volume — the default, and
     * therefore the common case — allocates nothing and copies nothing. Results are clamped to
     * the signed 16-bit range: attenuation cannot overflow, but the clamp costs one comparison
     * and keeps this correct if a gain above 1 is ever wanted.
     *
     * A trailing odd byte cannot occur (a frame is a whole number of PCM16 samples) and is
     * copied through rather than dropped if it ever does.
     */
    fun scale(pcm: ByteArray, gain: Float): ByteArray {
        if (gain >= 1f) return pcm
        val out = ByteArray(pcm.size)
        if (gain <= 0f) return out
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            val scaled = (sample * gain).toInt().coerceIn(MIN_SAMPLE, MAX_SAMPLE)
            out[i] = (scaled and 0xFF).toByte()
            out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        if (i < pcm.size) out[i] = pcm[i]
        return out
    }

    private const val MIN_SAMPLE = -32_768
    private const val MAX_SAMPLE = 32_767
}
