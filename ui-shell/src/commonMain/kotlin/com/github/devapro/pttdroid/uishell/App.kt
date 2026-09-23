package com.github.devapro.pttdroid.uishell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.Navigator
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.data.settings.applyLanguagePreference
import com.github.devapro.pttdroid.features.api.main.MainScreen
import com.github.devapro.pttdroid.features.main.MainViewModel
import com.github.devapro.pttdroid.ui.LocalPttUiEffects
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.koin.compose.koinInject

/**
 * The iOS UI root, hosted by `MainViewController()` (`MainViewController.kt`, iosMain) via
 * `ComposeUIViewController`.
 *
 * Kept platform-distinct from `:desktopApp`'s `Main.kt` rather than unified: `MainActivity` has a
 * real runtime mic permission flow, an overlay/"draw over other apps" permission, and an Android
 * `Activity` lifecycle to hook `PttController` start/stop to; `Main.kt` and this have none of
 * those. What the three roots *do* share since step 6 of the module-graph refactor is the
 * destination itself: each renders `Navigator(MainScreen)` with a [LocalPttUiEffects] host around
 * it, and Voyager's `Screen` destinations (declared in `:features-api:*`, wired in
 * `:features:*`) resolve their own view models — there is no shared router left to hold screen
 * state, and none of the three hosts knows which screens exist beyond Main's two pushes.
 *
 * Mic permission: no runtime-permission prompt is wired on iOS yet — like desktop,
 * `onMicPermissionResult(granted = true)` is called unconditionally, which (the flag update now
 * dispatching `InitConnection` itself) is what starts the session here. Requesting the real
 * `AVAudioSession` permission is a follow-up (see docs/platform-support.md).
 */
@Composable
fun App() {
    val mainViewModel = koinInject<MainViewModel>()
    val settingsRepository = koinInject<SettingsRepository>()

    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val snackbarHostState = remember { SnackbarHostState() }
    val effects = rememberPttHostUiEffects(snackbarHostState)

    // iOS has no runtime mic gate yet, so the grant is reported unconditionally — and since
    // MainViewModel.onMicPermissionResult dispatches InitConnection on a grant (the launcher it
    // reaches is the process-level IosPttSessionLauncher), this is what kicks off the session.
    LaunchedEffect(Unit) { mainViewModel.onMicPermissionResult(granted = true) }

    val language = settings.languageMode
    // Applied during composition, not from a LaunchedEffect: an effect runs *after* the first
    // pass has already resolved every string against the old locale.
    remember(language) { applyLanguagePreference(language) }
    key(language) {
        PTTdroidTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())) {
            // The Navigator is re-created with the language (it sits inside `key(language)`), so
            // a locale change lands back on Main — same as the old router's composition state did.
            // Per-screen event collection (rememberPttUiEvents in each root) replaced the three
            // host-level collectors that lived here before Voyager.
            CompositionLocalProvider(LocalPttUiEffects provides effects) {
                Navigator(MainScreen)
            }
        }
    }
}
