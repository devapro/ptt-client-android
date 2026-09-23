package com.github.devapro.pttdroid.features.main

import com.github.devapro.pttdroid.features.api.main.MainScreenFactory
import com.github.devapro.pttdroid.features.main.reducer.DisconnectReducer
import com.github.devapro.pttdroid.features.main.reducer.DismissErrorReducer
import com.github.devapro.pttdroid.features.main.reducer.InitConnectionReducer
import com.github.devapro.pttdroid.features.main.reducer.SavePlaybackVolumeReducer
import com.github.devapro.pttdroid.features.main.reducer.SetAudioOutputReducer
import com.github.devapro.pttdroid.features.main.reducer.SetChannelReducer
import com.github.devapro.pttdroid.features.main.reducer.SetPlaybackVolumeReducer
import com.github.devapro.pttdroid.features.main.reducer.StartSpeakReducer
import com.github.devapro.pttdroid.features.main.reducer.StopSpeakReducer
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

/**
 * Koin registration for `:features:main`, in this repo's `SharedDi.kt` extension-function style
 * (see docs/architecture.md's Koin graph section): hosts call this inside their own
 * `module { ... }` block at `startKoin`, so no host has to know the impl class. A `factory` (not
 * `single`) for the impl because it is a stateless composable dispatcher — nothing to reuse,
 * nothing to lifetime-manage.
 *
 * This module owns Main's whole MVI stack: `MainViewModel` plus its processor and the nine main
 * reducers.
 *
 * **`MainViewModel` is a `single`, not a `viewModelOf`.** That is the Voyager migration's one
 * non-mechanical DI decision, and it is deliberate: with per-screen roots there is no host left
 * resolving the three view models at one site and threading them down, so each root asks Koin for
 * its own — and Koin 4's `viewModelOf` is a Kind.Factory definition, a *fresh instance per `get()`*.
 * Left that way, the second `koinInject` would silently create a second `MainViewModel`: the root's
 * `onAction` would drive one loop while its `rememberPttUiEvents` collected the other one's
 * (empty) channel, and the `PttController` mirror in `init` would run twice. That is precisely
 * known-issues #22's shape — events going nowhere — so identity has to be structural, not a
 * convention about who resolves first.
 *
 * `single` gives exactly the guarantee the Android `ViewModelStore` used to: one instance for the
 * process, hence survive an Activity `recreate()` (the locale-change path triggers one), and be
 * shared with the screens without anyone threading it. What is given up is `ViewModel.onCleared()`
 * — never called for a `single` — which costs nothing here because these view models front
 * `PttController`, which is itself process-scoped and lives exactly as long. The `ViewModel` base
 * class stays (for `viewModelScope`, whose lifetime is now the process's, which is what the
 * session-mirroring `collect`s above have always assumed).
 */
fun Module.registerMainScreenDi() {
    factoryOf(::MainScreenFactoryImpl) bind MainScreenFactory::class
    factoryOf(::InitConnectionReducer)
    factoryOf(::DisconnectReducer)
    factoryOf(::StartSpeakReducer)
    factoryOf(::StopSpeakReducer)
    factoryOf(::SetChannelReducer)
    factoryOf(::DismissErrorReducer)
    factoryOf(::SetAudioOutputReducer)
    factoryOf(::SetPlaybackVolumeReducer)
    factoryOf(::SavePlaybackVolumeReducer)
    singleOf(::MainViewModel)

    factory {
        MainActionProcessor(
            reducers = setOf(
                get<InitConnectionReducer>(),
                get<DisconnectReducer>(),
                get<StartSpeakReducer>(),
                get<StopSpeakReducer>(),
                get<SetChannelReducer>(),
                get<DismissErrorReducer>(),
                get<SetAudioOutputReducer>(),
                get<SetPlaybackVolumeReducer>(),
                get<SavePlaybackVolumeReducer>(),
            ),
        )
    }
}
