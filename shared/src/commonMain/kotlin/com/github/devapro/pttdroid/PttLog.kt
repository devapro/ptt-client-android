package com.github.devapro.pttdroid

import co.touchlab.kermit.Logger

/**
 * Thin facade over Kermit, replacing `timber.log.Timber` (Android-only) for code that lives in
 * `:shared`'s commonMain. `:app`'s own Android-only classes keep using Timber directly.
 *
 * Kermit's default log writer already routes to logcat on Android and to stdout on desktop, so
 * there is no `plant()`-equivalent call needed at startup.
 *
 * As with Timber, never call this on a per-audio-frame path — see `VoiceRecorder`'s read loop
 * and `VoicePlayer.play()` for what that rule protects.
 */
object PttLog {
    private val logger = Logger.withTag("PTT")

    fun d(message: () -> String) {
        logger.d(message = message)
    }

    fun i(message: () -> String) {
        logger.i(message = message)
    }

    fun w(throwable: Throwable? = null, message: () -> String) {
        logger.w(throwable = throwable, message = message)
    }

    fun e(throwable: Throwable? = null, message: () -> String) {
        logger.e(throwable = throwable, message = message)
    }
}
