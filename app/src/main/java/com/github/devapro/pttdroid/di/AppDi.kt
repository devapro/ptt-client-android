package com.github.devapro.pttdroid.di

import com.github.devapro.pttdroid.CoroutineContextProvider
import com.github.devapro.pttdroid.MainActionProcessor
import com.github.devapro.pttdroid.audio.VoicePlayer
import com.github.devapro.pttdroid.audio.VoiceRecorder
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.domain.PttSessionLauncher
import com.github.devapro.pttdroid.domain.ServicePttSessionLauncher
import com.github.devapro.pttdroid.internalserver.InternalPttServer
import com.github.devapro.pttdroid.network.KtorPttConnection
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.overlay.OverlayController
import com.github.devapro.pttdroid.reducer.CloseSettingsReducer
import com.github.devapro.pttdroid.reducer.DisconnectReducer
import com.github.devapro.pttdroid.reducer.DismissErrorReducer
import com.github.devapro.pttdroid.reducer.InitConnectionReducer
import com.github.devapro.pttdroid.reducer.OpenSettingsReducer
import com.github.devapro.pttdroid.reducer.ReconnectReducer
import com.github.devapro.pttdroid.reducer.SaveSettingsReducer
import com.github.devapro.pttdroid.reducer.SetChannelReducer
import com.github.devapro.pttdroid.reducer.StartSpeakReducer
import com.github.devapro.pttdroid.reducer.StopSpeakReducer
import com.github.devapro.pttdroid.viewmodel.MainActivityViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
// Koin 4 moved the ViewModel DSL out of org.koin.androidx.viewmodel.dsl.
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Named qualifier for the application-lifetime coroutine scope the session runs on. */
private const val SESSION_SCOPE = "sessionScope"

val appModule = module {
    coreDi()
    dataDi()
    voiceDi()
    domainDi()
    appDi()
}

private fun Module.coreDi() {
    singleOf(::CoroutineContextProvider)
    single(org.koin.core.qualifier.named(SESSION_SCOPE)) {
        // Outlives any Activity: the session belongs to the process, driven by the service.
        CoroutineScope(SupervisorJob() + get<CoroutineContextProvider>().io)
    }
}

private fun Module.dataDi() {
    single { SettingsRepository(androidContext()) }
    single<PttSessionLauncher> { ServicePttSessionLauncher(androidContext()) }
}

private fun Module.voiceDi() {
    single { VoiceRecorder(get(org.koin.core.qualifier.named(SESSION_SCOPE))) }
    singleOf(::VoicePlayer)
}

private fun Module.domainDi() {
    single { InternalPttServer() }
    single { KtorPttConnection() } bind PttConnection::class
    single {
        val settingsRepository = get<SettingsRepository>()
        PttController(
            connection = get(),
            recorder = get<VoiceRecorder>(),
            player = get<VoicePlayer>(),
            settingsProvider = { settingsRepository.settings.first() },
            channelPersister = { channel -> settingsRepository.setChannel(channel) },
            scope = get(org.koin.core.qualifier.named(SESSION_SCOPE)),
        )
    }
    single {
        OverlayController(
            context = androidContext(),
            controller = get(),
            settingsRepository = get(),
            scope = get(org.koin.core.qualifier.named(SESSION_SCOPE)),
        )
    }
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
                get<SaveSettingsReducer>(),
                get<DismissErrorReducer>(),
            ),
        )
    }

    factoryOf(::InitConnectionReducer)
    factoryOf(::DisconnectReducer)
    factoryOf(::ReconnectReducer)
    factoryOf(::StartSpeakReducer)
    factoryOf(::StopSpeakReducer)
    factoryOf(::SetChannelReducer)
    factoryOf(::OpenSettingsReducer)
    factoryOf(::CloseSettingsReducer)
    factoryOf(::SaveSettingsReducer)
    factoryOf(::DismissErrorReducer)
}
