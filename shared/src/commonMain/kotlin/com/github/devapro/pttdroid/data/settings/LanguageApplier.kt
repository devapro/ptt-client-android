package com.github.devapro.pttdroid.data.settings

/**
 * Applies [mode] to whatever this platform's `stringResource()` reads its locale from.
 *
 * On desktop this is the JVM's default locale, on iOS the `AppleLanguages` preference (next
 * launch only — see the iosMain actual), and on Android the process default locale as well.
 * Android additionally forces the language through `Context.attachBaseContext`/
 * `Activity.recreate()` (`LocaleApplier.android.kt`, `MainActivity.attachBaseContext`) when a
 * **persisted** change lands, which also gives it a real Android `res/` configuration (layout
 * direction, plurals, the works); this function is the lighter lever on that platform: the live
 * *display* language of the unsaved Settings form (R9 — labels react to the language picked in
 * the form before Save, and the host restores the persisted language through the same call when
 * the form goes away).
 */
expect fun applyLanguagePreference(mode: LanguageMode)
