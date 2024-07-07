package com.github.devapro.pttdroid.di

import android.preference.PreferenceManager
import com.github.devapro.pttdroid.CoroutineContextProvider
import com.github.devapro.pttdroid.MainActionProcessor
import com.github.devapro.pttdroid.audio.VoicePlayer
import com.github.devapro.pttdroid.audio.VoiceRecorder
import com.github.devapro.pttdroid.data.ChannelSettingsRepository
import com.github.devapro.pttdroid.data.PrefManager
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import com.github.devapro.pttdroid.permission.UtilPermission
import com.github.devapro.pttdroid.reducer.ConnectedReducer
import com.github.devapro.pttdroid.reducer.DisconnectReducer
import com.github.devapro.pttdroid.reducer.InitConnectionReducer
import com.github.devapro.pttdroid.reducer.ReconnectReducer
import com.github.devapro.pttdroid.reducer.SetChannelReducer
import com.github.devapro.pttdroid.reducer.StartSpeakReducer
import com.github.devapro.pttdroid.reducer.StopSpeakReducer
import com.github.devapro.pttdroid.viewmodel.MainActivityViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    coreDi()
    appDi()
    dataDi()
    voiceDi()
}

private fun Module.appDi() {
    viewModelOf(::MainActivityViewModel)
    factory{
        MainActionProcessor(
            reducers = setOf(
                get(DisconnectReducer::class),
                get(InitConnectionReducer::class),
                get(ReconnectReducer::class),
                get(StartSpeakReducer::class),
                get(StopSpeakReducer::class),
                get(ConnectedReducer::class),
                get(SetChannelReducer::class)
            )
        )
    }

    factoryOf(::DisconnectReducer)
    factoryOf(::InitConnectionReducer)
    factoryOf(::ReconnectReducer)
    factoryOf(::StartSpeakReducer)
    factoryOf(::StopSpeakReducer)
    factoryOf(::ConnectedReducer)
    factoryOf(::SetChannelReducer)
}

private fun Module.dataDi() {
    singleOf(::PTTWebSocketConnection)
    singleOf(::ChannelSettingsRepository)
}

private fun Module.voiceDi() {
    singleOf(::VoicePlayer)
    singleOf(::VoiceRecorder)
}

private fun Module.coreDi() {
    singleOf(::UtilPermission)
    singleOf(::CoroutineContextProvider)
    single {
        PrefManager(
            preferences = PreferenceManager.getDefaultSharedPreferences(
                androidContext()
            )
        )
    }
}