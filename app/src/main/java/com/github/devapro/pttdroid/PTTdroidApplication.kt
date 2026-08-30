package com.github.devapro.pttdroid

import android.app.Application
import com.github.devapro.pttdroid.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class PTTdroidApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Debug only: release builds previously logged on the per-audio-frame path.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        startKoin {
            androidContext(this@PTTdroidApplication)
            modules(appModule)
        }
    }
}
