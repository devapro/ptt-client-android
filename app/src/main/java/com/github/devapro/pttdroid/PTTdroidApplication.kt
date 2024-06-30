package com.github.devapro.pttdroid

import android.app.Application
import com.github.devapro.pttdroid.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin

class PTTdroidApplication: Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin{
            androidContext(this@PTTdroidApplication)
            modules(appModule)
        }
    }
}