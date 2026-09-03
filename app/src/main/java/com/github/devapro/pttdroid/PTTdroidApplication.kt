package com.github.devapro.pttdroid

import android.app.Application
import com.github.devapro.pttdroid.data.settings.LanguageMode
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.data.settings.applyLocale
import com.github.devapro.pttdroid.di.appModule
import com.github.devapro.pttdroid.di.sharedAndroidModule
import com.github.devapro.pttdroid.di.sharedModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.get
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

        // Applies the stored language process-wide (Locale.setDefault, inside applyLocale) so the
        // foreground-service notification, the Glance widget and the floating overlay bubble are
        // in the chosen language even when this process was started by one of them and
        // MainActivity — the only other place a language gets applied — never existed. The
        // returned Context is discarded on purpose: the point here is only the Locale.setDefault
        // side effect, not a configured Context — none of those three surfaces reads strings
        // against the Application's own base context. This also makes LocaleApplier.android.kt's
        // pristine-locale capture happen at the earliest possible moment the app can run code,
        // which is what makes switching back to LanguageMode.SYSTEM later reliably restore the
        // device's own locale.
        val settingsRepository: SettingsRepository = get()
        val mode = try {
            runBlocking { settingsRepository.settings.first().languageMode }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LanguageMode.SYSTEM
        }
        applyLocale(this, mode)
    }
}
