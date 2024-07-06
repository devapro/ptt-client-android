package com.github.devapro.pttdroid

import android.app.Application
import com.github.devapro.pttdroid.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin
import timber.log.Timber

class PTTdroidApplication: Application() {

    override fun onCreate() {
        super.onCreate()

        Timber.plant(Timber.DebugTree())

        startKoin{
            androidContext(this@PTTdroidApplication)
            modules(appModule)
        }
    }
}