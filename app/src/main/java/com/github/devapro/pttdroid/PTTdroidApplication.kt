package com.github.devapro.pttdroid

import android.app.Application
import com.github.devapro.pttdroid.di.appModule
import com.github.devapro.pttdroid.di.sharedAndroidModule
import com.github.devapro.pttdroid.di.sharedModule
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
            // sharedModule (platform-independent) + sharedAndroidModule (Android's platform
            // providers, from :shared) + appModule (:app's own Android-only classes). Koin merges
            // all three into one graph, so it does not matter which module registers a binding
            // another module's definitions depend on.
            modules(sharedModule, sharedAndroidModule, appModule)
        }
    }
}
