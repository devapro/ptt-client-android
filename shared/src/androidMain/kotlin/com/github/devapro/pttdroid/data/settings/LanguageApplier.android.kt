package com.github.devapro.pttdroid.data.settings

/**
 * Android applies the locale in `MainActivity.attachBaseContext` via [applyLocale]; this
 * actual is a no-op so a shared call site cannot double-apply.
 */
actual fun applyLanguagePreference(mode: LanguageMode) {
}
