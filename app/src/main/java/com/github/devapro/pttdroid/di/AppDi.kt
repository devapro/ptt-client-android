package com.github.devapro.pttdroid.di

import com.github.devapro.pttdroid.audio.VoicePlayer
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorder
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.domain.PttSessionLauncher
import com.github.devapro.pttdroid.domain.ServicePttSessionLauncher
import com.github.devapro.pttdroid.overlay.OverlayController
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * `:app`'s own Koin module — Android-only classes that need a real `Context`, `AudioRecord`/
 * `AudioTrack`, or `PttForegroundService`, none of which `:shared` can see.
 *
 * Everything platform-independent (the reducers, `PttController`, `MainActionProcessor`, the
 * `ViewModel`, `SettingsRepository`...) lives in `:shared`'s `sharedModule`
 * (`di/SharedDi.kt`); Android's other platform providers (the settings `DataStore`, the on-device
 * relay) live in `:shared`'s `sharedAndroidModule` (`di/SharedDiAndroid.kt`). `PTTdroidApplication`
 * loads all three together — see that file — so it does not matter that `VoiceRecorderContract`/
 * `VoicePlayerContract` are only bound here while `PttController` (which consumes them) is
 * declared in `sharedModule`: Koin merges every loaded module into one graph.
 */
val appModule = module {
    single { VoiceRecorder(get(SESSION_SCOPE)) } bind VoiceRecorderContract::class
    singleOf(::VoicePlayer) bind VoicePlayerContract::class

    single<PttSessionLauncher> { ServicePttSessionLauncher(androidContext()) }

    single {
        OverlayController(
            context = androidContext(),
            controller = get(),
            settingsRepository = get(),
            scope = get(SESSION_SCOPE),
        )
    }
}
