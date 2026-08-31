package com.github.devapro.pttdroid.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.devapro.pttdroid.audio.DesktopVoicePlayer
import com.github.devapro.pttdroid.audio.DesktopVoiceRecorder
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.createDesktopSettingsDataStore
import com.github.devapro.pttdroid.domain.DesktopPttSessionLauncher
import com.github.devapro.pttdroid.domain.PttSessionLauncher
import org.koin.dsl.module

/**
 * Desktop's platform providers: the settings `DataStore`, the on-device relay (`jvmDi()`, shared
 * with Android), and — unlike Android, which gets these from `:app` — the audio/session-launcher
 * bindings too, since there is no separate "desktop app" module to split them into.
 *
 * `DesktopVoiceRecorder`/`DesktopVoicePlayer` (Phase 6, `audio/DesktopAudio.kt`) are real
 * `javax.sound.sampled` implementations, not placeholders — they each get the same `SESSION_SCOPE`
 * instance `:app`'s `VoiceRecorder`/`OverlayController` use, so their read/drain loops share the
 * one application-lifetime scope rather than spawning their own.
 */
val sharedDesktopModule = module {
    single<DataStore<Preferences>> { createDesktopSettingsDataStore() }
    jvmDi()

    single<VoiceRecorderContract> { DesktopVoiceRecorder(get(SESSION_SCOPE)) }
    single<VoicePlayerContract> { DesktopVoicePlayer(get(SESSION_SCOPE)) }
    single<PttSessionLauncher> { DesktopPttSessionLauncher(get()) }
}
