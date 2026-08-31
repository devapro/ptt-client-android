package com.github.devapro.pttdroid.audio

import android.media.AudioFormat

/**
 * The `android.media.AudioFormat` ints that go with [AudioConfig]'s wire constants.
 *
 * `AudioConfig` moved to `:shared`'s commonMain in Phase 2 (it is also read by the desktop
 * target), which cannot reference `android.media`. [VoiceRecorder] and [VoicePlayer] — both
 * Android-only and staying in `:app` — read these from here instead of from `AudioConfig`.
 */
object AndroidAudioFormat {
    const val IN_CHANNEL_MASK: Int = AudioFormat.CHANNEL_IN_MONO
    const val OUT_CHANNEL_MASK: Int = AudioFormat.CHANNEL_OUT_MONO
    const val PCM_ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT
}
