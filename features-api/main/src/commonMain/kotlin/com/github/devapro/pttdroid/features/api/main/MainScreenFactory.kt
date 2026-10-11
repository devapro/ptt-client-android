package com.github.devapro.pttdroid.features.api.main

import androidx.compose.runtime.Composable

/**
 * The contract for rendering the Main screen. Implemented by `:features:main`, bound in Koin, and
 * resolved by [MainScreen.Content] — the destination marker itself, not a host.
 *
 * It takes no arguments since the Voyager migration: the implementation is a self-sufficient screen
 * root that resolves [com.github.devapro.pttdroid.features.main.MainViewModel], `:shared`'s
 * `SettingsRepository` (endpoint, `audioOutput`, `playbackVolume`) and `:shared`'s `HistoryBadge`
 * itself, pushes `SettingsScreen`/`HistoryScreen` through the `Navigator` Voyager provides to it,
 * and reads the host's one-shot effects from
 * [com.github.devapro.pttdroid.ui.LocalPttUiEffects]. The pure `MainScreen` composable in
 * `:features:main` still takes all of that as parameters — only the *root* is self-sufficient — so
 * the screen stays a renderable function of its inputs for previews and for `MainScreenTest`.
 *
 * The screen speaks this slice's own [MainState]/[MainAction]; `historyUnread` still crosses into
 * it from `:shared`'s badge rather than from `:features:history`, so no `:features:main` →
 * `:features:history` edge exists.
 */
interface MainScreenFactory {
    @Composable
    fun CreateMainScreen()
}
