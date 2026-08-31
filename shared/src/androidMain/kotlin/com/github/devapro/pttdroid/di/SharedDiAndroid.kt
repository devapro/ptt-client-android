package com.github.devapro.pttdroid.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.devapro.pttdroid.data.settings.createAndroidSettingsDataStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Android's platform providers: the settings `DataStore` (built from Koin's own
 * `androidContext()` — see `data/settings/SettingsDataStore.android.kt` for why that replaced the
 * Phase 3 `AndroidSettingsContext` static holder) and the on-device relay (`jvmDi()`, shared with
 * the desktop target).
 *
 * `VoiceRecorderContract`/`VoicePlayerContract`/`PttSessionLauncher` are *not* bound here: the
 * concrete Android implementations (`VoiceRecorder`, `VoicePlayer`, `ServicePttSessionLauncher`)
 * need real `AudioRecord`/`AudioTrack`/`PttForegroundService` classes that only exist in `:app`,
 * so those bindings live in `:app`'s own `di/AppDi.kt` instead. Koin merges every loaded module
 * into one graph regardless of which file registers a binding, so `PttController` (in
 * `SharedDi.kt`) resolving them by interface still works as long as `:app` loads this module and
 * its own together at `startKoin`.
 */
val sharedAndroidModule = module {
    single<DataStore<Preferences>> { createAndroidSettingsDataStore(androidContext()) }
    jvmDi()
}
