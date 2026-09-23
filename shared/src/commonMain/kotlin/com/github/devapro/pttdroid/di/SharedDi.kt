package com.github.devapro.pttdroid.di

import com.github.devapro.pttdroid.CoroutineContextProvider
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.history.BackgroundRecordCapture
import com.github.devapro.pttdroid.history.DataStoreHistoryStore
import com.github.devapro.pttdroid.history.HistoryBadge
import com.github.devapro.pttdroid.history.HistoryPlayback
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.network.KtorPttConnection
import com.github.devapro.pttdroid.network.PttConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
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
 * network layer and the data/history stores.
 *
 * The MVI layer is deliberately **not** here: each feature's view model, action processor and
 * reducers are registered by `registerMainScreenDi()` / `registerSettingsScreenDi()` /
 * `registerHistoryScreenDi()` in their own `:features:*` module, which is the only place that
 * can see both `:shared`'s domain and the feature's contracts. What remains in `:shared` is the
 * layer every feature points downward at — and nothing that points back up.
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
    // The cross-feature unread derivation — see its KDoc for why it lives in :shared.
    singleOf(::HistoryBadge)
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
