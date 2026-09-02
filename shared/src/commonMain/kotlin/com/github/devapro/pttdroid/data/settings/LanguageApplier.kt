package com.github.devapro.pttdroid.data.settings

/**
 * Applies a persisted [LanguageMode] on platforms that do not go through Android's
 * `attachBaseContext` + `recreate` path.
 *
 * Android applies the locale in `MainActivity.attachBaseContext` and recreates the Activity
 * when the setting changes; this function is a no-op there so the selector is not applied
 * twice. Desktop sets the JVM default locale from [LanguageMode.tag]. iOS is a no-op
 * placeholder — the selector still persists and round-trips.
 */
expect fun applyLanguagePreference(mode: LanguageMode)
