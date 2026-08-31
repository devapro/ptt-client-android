package com.github.devapro.pttdroid.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.di.sharedDesktopModule
import com.github.devapro.pttdroid.di.sharedModule
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.ui.MainScreen
import com.github.devapro.pttdroid.ui.SettingsScreen
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import com.github.devapro.pttdroid.viewmodel.MainActivityViewModel
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject
import org.koin.core.context.startKoin

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
        modules(sharedModule, sharedDesktopModule)
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "PTTdroid") {
            // Not `koinViewModel()`: that resolves through `LocalViewModelStoreOwner`, which ties
            // the instance to a screen/navigation lifecycle Compose Multiplatform Desktop does
            // not have a documented default for in a bare `Window`. `viewModelOf` in
            // `di/SharedDi.kt` registers this as a plain Koin factory, so `koinInject` (created
            // once, then `remember`-ed for the composition's lifetime, same as every other
            // dependency below) resolves it exactly as well — there is no configuration change or
            // back-stack to survive on desktop that would need the extra machinery.
            val viewModel = koinInject<MainActivityViewModel>()
            val settingsRepository = koinInject<SettingsRepository>()

            val state by viewModel.state.collectAsState()
            val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
            val snackbarHostState = remember { SnackbarHostState() }

            // The Android app does this from MainActivity.onStart(), gated on a runtime
            // permission result; desktop has no such permission, so it happens once, up front.
            LaunchedEffect(Unit) { viewModel.onMicPermissionResult(granted = true) }

            // Mirrors MainActivity.collectEvents(): only ShowMessage has a desktop equivalent —
            // there is no runtime mic permission or "draw over other apps" prompt to route the
            // other two events to.
            LaunchedEffect(Unit) {
                viewModel.event.collect { event ->
                    if (event is MainEvent.ShowMessage) {
                        snackbarHostState.showSnackbar(getString(event.messageRes))
                    }
                }
            }

            PTTdroidTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())) {
                when (state.screen) {
                    ScreenState.Screen.Main -> MainScreen(
                        state = state,
                        endpoint = "${settings.serverHost}:${settings.serverPort}",
                        snackbarHostState = snackbarHostState,
                        onAction = viewModel::onAction,
                    )

                    ScreenState.Screen.Settings -> SettingsScreen(
                        settings = settings,
                        // No overlay/"draw over other apps" concept on desktop; the floating-
                        // bubble toggle in Settings just has nothing left to require.
                        canDrawOverlay = true,
                        onSave = { viewModel.onAction(MainAction.SaveSettings(it)) },
                        onRequestOverlayPermission = {},
                        onBack = { viewModel.onAction(MainAction.CloseSettings) },
                    )
                }
            }
        }
    }
}
