package com.github.devapro.pttdroid.di

import com.github.devapro.pttdroid.audio.VoicePlayer
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import com.github.devapro.pttdroid.permission.UtilPermission
import org.koin.dsl.module

val appModule = module {
    single { PTTWebSocketConnection() }
    single { UtilPermission() }
    single { VoicePlayer() }
}