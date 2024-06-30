package com.github.devapro.pttdroid.di

import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import org.koin.dsl.module

val appModule = module {
    single { PTTWebSocketConnection() }
}