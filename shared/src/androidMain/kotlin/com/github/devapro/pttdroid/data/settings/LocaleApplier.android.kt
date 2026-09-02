package com.github.devapro.pttdroid.data.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Wraps [base] in a configuration context for [mode]. Tagged modes pin a BCP 47 locale.
 * [LanguageMode.SYSTEM] restores the device locale from [Resources.getSystem] so a previous
 * [Locale.setDefault] (ru/sr/en) cannot leak into Compose Multiplatform string lookup.
 */
fun applyLocale(base: Context, mode: LanguageMode): Context {
    val locale = mode.tag?.let(Locale::forLanguageTag) ?: systemLocale()
    Locale.setDefault(locale)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
}

@Suppress("DEPRECATION")
private fun systemLocale(): Locale {
    val config = Resources.getSystem().configuration
    val locales = config.locales
    return if (locales.isEmpty) config.locale else locales[0]
}
