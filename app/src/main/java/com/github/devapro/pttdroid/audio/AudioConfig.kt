package com.github.devapro.pttdroid.audio

import android.media.AudioFormat

/**
 * The single source of truth for the audio format on the wire.
 *
 * Protocol v1 fixes these values — there is no negotiation, so recorder and player must not
 * probe for a rate independently (the old code did, with no guarantee the two agreed and no
 * way to tell a peer what it had chosen).
 */
object AudioConfig {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val CHANNEL_COUNT: Int = 1
    const val ENCODING_NAME: String = "pcm16le"

    const val IN_CHANNEL_MASK: Int = AudioFormat.CHANNEL_IN_MONO
    const val OUT_CHANNEL_MASK: Int = AudioFormat.CHANNEL_OUT_MONO
    const val PCM_ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT

    const val BYTES_PER_SAMPLE: Int = 2

    /** 40 ms of audio: 16000 * 0.04 * 2 bytes. Matches the server's `frameBytes`. */
    const val FRAME_BYTES: Int = 1_280

    /** Upper bound the server enforces on a single binary frame. */
    const val MAX_FRAME_BYTES: Int = 8_192
}
