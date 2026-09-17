package com.github.devapro.pttdroid

import android.app.Application
import android.util.Log
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

        // Full logging in debug; release plants an ERROR-only tree instead of nothing. Release
        // builds previously planted no tree at all, which is what made a real failure like
        // VoicePlayer.prepare()'s AudioTrack setup failing completely invisible outside a debug
        // build — the Timber.e() call was already there, it just had nowhere to go. ReleaseTree
        // is not a return to per-frame logging: it is ERROR-only, and prepare() (called once per
        // connection, on `welcome`) is not a per-frame path — play()/VoiceRecorder's read loop
        // still log nothing, in debug or release, per this repo's hard rule.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
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

/**
 * What a release build plants instead of nothing.
 *
 * [Timber.DebugTree] already does the formatting and tagging work this needs (chunking long
 * messages, deriving a tag from the call site); the only thing to change is which priorities get
 * through. [isLoggable] is checked by [timber.log.Timber.Tree] itself before `log()` ever runs,
 * so this is a hard floor at [Log.ERROR] — nothing at `d`/`i`/`w` reaches this tree in a release
 * build. That specifically excludes `play()`, `VoiceRecorder`'s read loop, and the desktop/iOS
 * per-frame paths, none of which log at any level; this tree only ever gets a chance to see the
 * occasional lifecycle/error call sites already in the codebase (connect/disconnect, `prepare()`
 * failures, and the like).
 */
private class ReleaseTree : Timber.DebugTree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.ERROR
}
