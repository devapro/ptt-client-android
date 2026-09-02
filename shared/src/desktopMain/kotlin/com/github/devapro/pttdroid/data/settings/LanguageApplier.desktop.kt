package com.github.devapro.pttdroid.data.settings

import java.util.Locale

private val processDefaultLocale: Locale = Locale.getDefault()

/**
 * Best-effort JVM default locale from [LanguageMode.tag]. [LanguageMode.SYSTEM] restores the
 * locale captured at class load so a previous tagged apply does not stick. A full Compose
 * re-render may still need a process restart.
 */
actual fun applyLanguagePreference(mode: LanguageMode) {
    val locale = mode.tag?.let(Locale::forLanguageTag) ?: processDefaultLocale
    Locale.setDefault(locale)
}
