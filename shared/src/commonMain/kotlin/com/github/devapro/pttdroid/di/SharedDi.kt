package com.github.devapro.pttdroid.di

import com.github.devapro.pttdroid.CoroutineContextProvider
import com.github.devapro.pttdroid.MainActionProcessor
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.history.BackgroundRecordCapture
import com.github.devapro.pttdroid.history.DataStoreHistoryStore
import com.github.devapro.pttdroid.history.HistoryPlayback
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.network.KtorPttConnection
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.reducer.CloseHistoryReducer
import com.github.devapro.pttdroid.reducer.CloseSettingsReducer
import com.github.devapro.pttdroid.reducer.DeleteRecordReducer
import com.github.devapro.pttdroid.reducer.DisconnectReducer
import com.github.devapro.pttdroid.reducer.DismissErrorReducer
import com.github.devapro.pttdroid.reducer.EditSettingsReducer
import com.github.devapro.pttdroid.reducer.InitConnectionReducer
import com.github.devapro.pttdroid.reducer.OpenHistoryReducer
import com.github.devapro.pttdroid.reducer.OpenSettingsReducer
import com.github.devapro.pttdroid.reducer.PlayRecordReducer
import com.github.devapro.pttdroid.reducer.ReconnectReducer
import com.github.devapro.pttdroid.reducer.RequestOverlayPermissionReducer
import com.github.devapro.pttdroid.reducer.SavePlaybackVolumeReducer
import com.github.devapro.pttdroid.reducer.SaveSettingsReducer
import com.github.devapro.pttdroid.reducer.SetAudioOutputReducer
import com.github.devapro.pttdroid.reducer.SetChannelReducer
import com.github.devapro.pttdroid.reducer.SetPlaybackVolumeReducer
import com.github.devapro.pttdroid.reducer.StartSpeakReducer
import com.github.devapro.pttdroid.reducer.StopRecordPlaybackReducer
import com.github.devapro.pttdroid.reducer.StopSpeakReducer
import com.github.devapro.pttdroid.viewmodel.MainActivityViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.koin.core.module.Module
// Koin 4 moved the ViewModel DSL out of org.koin.androidx.viewmodel.dsl.
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Named qualifier for the application-lifetime coroutine scope the session runs on.
 *
 * Public (not `private const val` any more, as it was when this all lived in one `:app` module):
 * `:app`'s Android-only DI (`VoiceRecorder`, `OverlayController`) and `:shared`'s own platform DI
 * modules all need to `get()` against the exact same scope instance, so the qualifier itself has
 * to be shared rather than duplicated as a string literal in three places.
 */
val SESSION_SCOPE = named("sessionScope")

/**
 * Named qualifier for the history index `DataStore<Preferences>` — a second store next to the
 * settings one, bound by each platform's DI module (see `data/history/HistoryDataStore.*`).
 */
val HISTORY_DATA_STORE = named("historyDataStore")

/**
 * Everything platform-independent: the session's own coroutine scope, the domain layer, the
 * network layer, all fifteen reducers, the action processor and the screen's `ViewModel`.
 *
 * Platform-specific pieces this graph still needs — the settings `DataStore`, the on-device relay
 * (`InternalPttServer`, JVM only) and the concrete `VoiceRecorderContract` /
 * `VoicePlayerContract` / `PttSessionLauncher` implementations — are bound by
 * `di/SharedDiAndroid.kt`, `di/SharedDiDesktop.kt` and (for the two audio contracts and the
 * session launcher on Android) `:app`'s own `di/AppDi.kt`. Koin merges every loaded module into
 * one graph, so it does not matter which module file registers a binding — only that the caller
 * loads all of them together at `startKoin { modules(...) }`.
 */
val sharedModule = module {
    coreDi()
    dataDi()
    domainDi()
    reducerDi()
    appDi()
}

private fun Module.coreDi() {
    singleOf(::CoroutineContextProvider)
    single(SESSION_SCOPE) {
        // Outlives any Activity: the session belongs to the process, driven by the service
        // (Android) or by the application itself (desktop).
        CoroutineScope(SupervisorJob() + get<CoroutineContextProvider>().io)
    }
}

private fun Module.dataDi() {
    // The DataStore<Preferences> this reads is bound by the platform DI module
    // (SharedDiAndroid.kt / SharedDiDesktop.kt), which is why it has to be loaded alongside this
    // one at startKoin time.
    single { SettingsRepository(get()) }
    // The history index store and the WAV files behind it are platform-bound the same way —
    // HISTORY_DATA_STORE and HistoryFileStorage come from the platform module.
    single { DataStoreHistoryStore(get(HISTORY_DATA_STORE), get(), get()) } bind HistoryStore::class
}

private fun Module.domainDi() {
    single { KtorPttConnection() } bind PttConnection::class
    single {
        BackgroundRecordCapture(
            store = get(),
            clock = get(),
            isForeground = get(),
            scope = get(SESSION_SCOPE),
        )
    }
    single {
        HistoryPlayback(
            player = get<VoicePlayerContract>(),
            store = get(),
            scope = get(SESSION_SCOPE),
        )
    }
    single {
        val settingsRepository = get<SettingsRepository>()
        PttController(
            connection = get(),
            recorder = get<VoiceRecorderContract>(),
            player = get<VoicePlayerContract>(),
            settingsProvider = { settingsRepository.settings.first() },
            channelPersister = { channel -> settingsRepository.setChannel(channel) },
            scope = get(SESSION_SCOPE),
            historyCapture = get(),
            historyPlayback = get(),
        )
    }
}

private fun Module.reducerDi() {
    factoryOf(::InitConnectionReducer)
    factoryOf(::DisconnectReducer)
    factoryOf(::ReconnectReducer)
    factoryOf(::StartSpeakReducer)
    factoryOf(::StopSpeakReducer)
    factoryOf(::SetChannelReducer)
    factoryOf(::OpenSettingsReducer)
    factoryOf(::CloseSettingsReducer)
    factoryOf(::EditSettingsReducer)
    factoryOf(::SaveSettingsReducer)
    factoryOf(::RequestOverlayPermissionReducer)
    factoryOf(::DismissErrorReducer)
    factoryOf(::SetAudioOutputReducer)
    factoryOf(::SetPlaybackVolumeReducer)
    factoryOf(::SavePlaybackVolumeReducer)
    factoryOf(::OpenHistoryReducer)
    factoryOf(::CloseHistoryReducer)
    factoryOf(::PlayRecordReducer)
    factoryOf(::StopRecordPlaybackReducer)
    factoryOf(::DeleteRecordReducer)
}

private fun Module.appDi() {
    viewModelOf(::MainActivityViewModel)

    factory {
        MainActionProcessor(
            reducers = setOf(
                get<InitConnectionReducer>(),
                get<DisconnectReducer>(),
                get<ReconnectReducer>(),
                get<StartSpeakReducer>(),
                get<StopSpeakReducer>(),
                get<SetChannelReducer>(),
                get<OpenSettingsReducer>(),
                get<CloseSettingsReducer>(),
                get<EditSettingsReducer>(),
                get<SaveSettingsReducer>(),
                get<RequestOverlayPermissionReducer>(),
                get<DismissErrorReducer>(),
                get<SetAudioOutputReducer>(),
                get<SetPlaybackVolumeReducer>(),
                get<SavePlaybackVolumeReducer>(),
                get<OpenHistoryReducer>(),
                get<CloseHistoryReducer>(),
                get<PlayRecordReducer>(),
                get<StopRecordPlaybackReducer>(),
                get<DeleteRecordReducer>(),
            ),
        )
    }
}
