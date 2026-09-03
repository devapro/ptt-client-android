package com.github.devapro.pttdroid.data.settings

import java.util.Locale

/**
 * The desktop JVM's own locale, captured once at class-init time — before anything in this app
 * has had a chance to call [Locale.setDefault]. See `LocaleApplier.android.kt`'s `systemLocale`
 * for why this capture has to happen before the first forced switch: `Locale.setDefault` is
 * process-global and sticky, so resolving [LanguageMode.SYSTEM] back to this value is what makes
 * "back to System" actually restore the machine's own locale rather than whichever language was
 * forced last.
 */
private val systemLocale: Locale = Locale.getDefault()

actual fun applyLanguagePreference(mode: LanguageMode) {
    Locale.setDefault(mode.tag?.let(Locale::forLanguageTag) ?: systemLocale)
}
