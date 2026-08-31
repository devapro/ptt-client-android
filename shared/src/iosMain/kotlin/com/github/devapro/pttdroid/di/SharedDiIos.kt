package com.github.devapro.pttdroid.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.devapro.pttdroid.audio.IosVoicePlayer
import com.github.devapro.pttdroid.audio.IosVoiceRecorder
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.createIosSettingsDataStore
import com.github.devapro.pttdroid.domain.IosPttSessionLauncher
import com.github.devapro.pttdroid.domain.PttSessionLauncher
import org.koin.dsl.module

/**
 * iOS's platform providers, mirroring `di/SharedDiAndroid.kt`/`di/SharedDiDesktop.kt`: the
 * settings `DataStore`, and — like desktop, and unlike Android, which gets these from `:app` —
 * the audio/session-launcher bindings, since there is no separate "iOS app" module to split them
 * into.
 *
 * Deliberately does **not** call `jvmDi()` (`di/SharedDiJvm.kt`): `InternalPttServer` lives in
 * `jvmCommonMain`, which iOS's `iosMain` does not depend on and cannot see. This is exactly what
 * `domain/canHostRelay` (`false` here, see `PlatformCapabilities.ios.kt`) exists to tell the UI.
 *
 * [IosVoiceRecorder]/[IosVoicePlayer] are real `AVAudioEngine`-backed implementations as of Phase
 * 7b (`audio/IosAudio.kt`) — [IosVoiceRecorder] gets the same `SESSION_SCOPE` instance
 * `DesktopVoiceRecorder`/`:app`'s `VoiceRecorder` use, for its off-realtime-thread
 * `FrameAccumulator` re-chunking coroutine, matching the pattern in `SharedDiDesktop.kt`.
 */
val sharedIosModule = module {
    single<DataStore<Preferences>> { createIosSettingsDataStore() }

    single<VoiceRecorderContract> { IosVoiceRecorder(get(SESSION_SCOPE)) }
    single<VoicePlayerContract> { IosVoicePlayer() }
    single<PttSessionLauncher> { IosPttSessionLauncher(get()) }
}
