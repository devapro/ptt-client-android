package com.github.devapro.pttdroid.data.settings

// No-op: Android forces the language through Context.attachBaseContext + Activity.recreate()
// instead (see LocaleApplier.android.kt, MainActivity.attachBaseContext). This actual only exists
// because ui/App.kt is commonMain and so compiles for Android too.
actual fun applyLanguagePreference(mode: LanguageMode) = Unit
