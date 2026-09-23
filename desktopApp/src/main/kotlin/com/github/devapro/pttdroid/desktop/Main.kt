package com.github.devapro.pttdroid.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.Navigator
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.data.settings.applyLanguagePreference
import com.github.devapro.pttdroid.di.sharedDesktopModule
import com.github.devapro.pttdroid.di.sharedModule
import com.github.devapro.pttdroid.features.api.main.MainScreen
import com.github.devapro.pttdroid.features.history.registerHistoryScreenDi
import com.github.devapro.pttdroid.features.main.MainViewModel
import com.github.devapro.pttdroid.features.main.registerMainScreenDi
import com.github.devapro.pttdroid.features.settings.registerSettingsScreenDi
import com.github.devapro.pttdroid.ui.LocalPttUiEffects
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import com.github.devapro.pttdroid.uishell.rememberPttHostUiEffects
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * The real shared UI, running on the JVM/desktop target.
 *
 * As of Phase 6, audio works here too: [com.github.devapro.pttdroid.audio.DesktopVoiceRecorder]
 * and [com.github.devapro.pttdroid.audio.DesktopVoicePlayer] (see `di/SharedDiDesktop.kt`) are
 * real `javax.sound.sampled` implementations of the same contracts the Android app's
 * `VoiceRecorder`/`VoicePlayer` implement — this connects to a relay, shows peers, requests the
 * floor, and captures/plays PCM, all through the same network layer, reducers and screens the
 * Android app runs.
 *
 * There is no Android `Activity`/`Application` here, so this module starts Koin itself rather
 * than relying on one being started for it, and there is no runtime microphone permission to wait
 * for, so the session is kicked off unconditionally instead of from a permission callback.
 */
fun main() {
    startKoin {
        modules(
            sharedModule,
            sharedDesktopModule,
            // The feature DI bindings behind the Voyager destinations — each register*ScreenDi()
            // owns its feature's whole MVI stack (view model as a process `single`, processor,
            // reducers) plus its ScreenFactory. From here on the hosts only need these to start
            // the graph; nothing here resolves screens or threads their arguments any more.
            module {
                registerMainScreenDi()
                registerSettingsScreenDi()
                registerHistoryScreenDi()
            },
        )
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "PTTdroid") {
            // Not `koinViewModel()` — desktop has no `ViewModelStoreOwner` for a bare `Window`
            // to bind one to, and since the Voyager step it would not even be the right tool:
            // the feature DI modules register the view models as Koin `single`s, so *any*
            // resolution site (this one, above `key(language)`, or a screen root below it)
            // provably gets the same instance. Resolving here rather than letting the root
            // `Navigator` sit under a recreated VM on a language change is still deliberate —
            // the view models front the session, and `key(language)` must not rebuild them.
            val mainViewModel = koinInject<MainViewModel>()
            val settingsRepository = koinInject<SettingsRepository>()

            val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
            val snackbarHostState = remember { SnackbarHostState() }
            val effects = rememberPttHostUiEffects(snackbarHostState)

            // The Android app does this from MainActivity.onStart(), gated on a runtime
            // permission result; desktop has no such permission, so it happens once, up front —
            // and since a grant in onMicPermissionResult dispatches InitConnection itself, this
            // is what starts the session (through DesktopPttSessionLauncher straight into
            // PttController.start()).
            LaunchedEffect(Unit) { mainViewModel.onMicPermissionResult(granted = true) }

            val language = settings.languageMode
            // Applied during composition, not from a LaunchedEffect: an effect runs *after* the
            // first pass has already resolved every string against the old locale.
            remember(language) { applyLanguagePreference(language) }
            key(language) {
                PTTdroidTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())) {
                    // Voyager Navigator, same as the other two hosts. History stays reachable
                    // (its icon is on the shared MainScreen) though its list stays empty on
                    // desktop by decision: capture is inert while always foreground — see
                    // `ForegroundProvider` in di/SharedDiDesktop.kt. Desktop has no system back
                    // (Voyager's BackHandler actual is a no-op on JVM), so the in-screen arrows
                    // remain the only route out of Settings/History here — behaviour identical to
                    // the pre-Voyager router on this platform.
                    CompositionLocalProvider(LocalPttUiEffects provides effects) {
                        Navigator(MainScreen)
                    }
                }
            }
        }
    }
}
