package com.github.devapro.pttdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import com.github.devapro.pttdroid.data.settings.LanguageMode

/**
 * Applies [languageMode] to the Compose Multiplatform resource loader for everything inside
 * [content]. Wraps the three platform entry points (`MainActivity`, `:desktopApp`'s `Main.kt`,
 * iOS's `App()`) so the whole UI honours the setting.
 *
 * `key(tag)` forces a full recomposition when the language changes: resource reads are cached
 * against the composition, and a locale swap has to invalidate all of them at once rather than
 * leaving a half-translated screen.
 */
@Composable
fun AppLocale(languageMode: LanguageMode, content: @Composable () -> Unit) {
    val tag = languageMode.languageTag()
    CompositionLocalProvider(LocalAppLocale provides tag) {
        key(tag) { content() }
    }
}
