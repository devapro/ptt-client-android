package com.github.devapro.pttdroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.data.settings.applyLanguagePreference
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import com.github.devapro.pttdroid.viewmodel.MainActivityViewModel
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject

/**
 * The shared UI root, hosted by [MainViewController] (`MainViewController.kt`, iosMain) via
 * `ComposeUIViewController`.
 *
 * This intentionally duplicates most of `:desktopApp`'s `Main.kt` `application { Window { ... } }`
 * body rather than having that body call into this: unifying `MainActivity` (which has a real
 * runtime mic permission flow, an overlay/"draw over other apps" permission, and an Android
 * `Activity` lifecycle to hook `PttController` start/stop to) and `Main.kt` (which has neither)
 * into one function used by all three platforms would mean reworking two already-shipping,
 * already-tested entry points to accommodate a third that cannot even be run yet on this Linux
 * machine. That felt like the wrong trade for Phase 7a — see the Phase 7a report. `App()` is new,
 * modeled closely on `Main.kt`'s body (no runtime mic permission, no overlay permission — neither
 * concept exists on iOS either, at least not for what Phase 7a's placeholder audio needs), and is
 * used only by iOS today. A future phase could still fold `Main.kt` into calling this once the
 * three entry points' remaining differences (Android's permission plumbing) are worth abstracting
 * over.
 *
 * Mic permission: Phase 7a's `IosVoiceRecorder`/`IosVoicePlayer` are no-op placeholders (see
 * `audio/IosAudio.kt`), so — like desktop — there is nothing to gate on a runtime permission
 * result yet; `onMicPermissionResult(granted = true)` is called unconditionally. Requesting the
 * real `AVAudioSession` permission is Phase 7b's job, alongside the real recorder/player.
 */
@Composable
fun App() {
    val viewModel = koinInject<MainActivityViewModel>()
    val settingsRepository = koinInject<SettingsRepository>()

    val state by viewModel.state.collectAsState()
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.onMicPermissionResult(granted = true) }

    LaunchedEffect(settings.languageMode) { applyLanguagePreference(settings.languageMode) }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            if (event is MainEvent.ShowMessage) {
                snackbarHostState.showSnackbar(getString(event.messageRes))
            }
            // MainEvent.RequestMicPermission / RequestOverlayPermission: no iOS equivalent yet —
            // see the class KDoc. Matches Main.kt's own collectEvents equivalent.
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
                // No "draw over other apps" concept on iOS, same as desktop's Main.kt.
                canDrawOverlay = true,
                onSave = { viewModel.onAction(MainAction.SaveSettings(it)) },
                onRequestOverlayPermission = {},
                onBack = { viewModel.onAction(MainAction.CloseSettings) },
                // canHostRelay defaults to this platform's own domain.canHostRelay (false on
                // iOS), so the row is already hidden without passing it explicitly — spelled out
                // anyway here since this is the one call site Phase 7a added on purpose.
            )
        }
    }
}
