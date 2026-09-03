package com.github.devapro.pttdroid.data.settings

/**
 * Applies [mode] to whatever this platform's `stringResource()` reads its locale from.
 *
 * Android does **not** go through this — it forces the language via
 * `Context.attachBaseContext`/`Activity.recreate()` (`LocaleApplier.android.kt`,
 * `MainActivity.attachBaseContext`), which also gives it a real Android `res/` configuration
 * (layout direction, plurals, the works) rather than just swapping which Compose-resources string
 * table gets read. This `expect`/`actual` pair exists so desktop and iOS — which have no
 * `Context`/`Activity` to recreate — are not left with a language selector that silently does
 * nothing.
 */
expect fun applyLanguagePreference(mode: LanguageMode)
