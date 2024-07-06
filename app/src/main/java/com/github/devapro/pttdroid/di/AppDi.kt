package com.github.devapro.pttdroid.di

import com.github.devapro.pttdroid.CoroutineContextProvider
import com.github.devapro.pttdroid.MainActionProcessor
import com.github.devapro.pttdroid.audio.VoicePlayer
import com.github.devapro.pttdroid.audio.VoiceRecorder
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import com.github.devapro.pttdroid.permission.UtilPermission
import com.github.devapro.pttdroid.viewmodel.MainActivityViewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    singleOf(::PTTWebSocketConnection)
    singleOf(::UtilPermission)
    singleOf(::VoicePlayer)
    singleOf(::CoroutineContextProvider)
    singleOf(::VoiceRecorder)
    viewModelOf(::MainActivityViewModel)
    factoryOf(::MainActionProcessor)
}