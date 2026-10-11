package com.github.devapro.pttdroid.features.api.history

import androidx.compose.runtime.Composable

/**
 * The contract for rendering the History screen. Implemented by `:features:history`, bound in Koin,
 * and resolved by [HistoryScreen.Content].
 *
 * It takes no arguments since the Voyager migration: the implementation is a self-sufficient screen
 * root that resolves `HistoryViewModel`, dispatches `HistoryAction.Open` once per entry (the badge's
 * read receipt), pops the `Navigator` on `HistoryAction.Close` without stopping playback, and
 * collects the view model's events into [com.github.devapro.pttdroid.ui.LocalPttUiEffects]. The pure
 * `HistoryScreen` composable in `:features:history` still takes this slice's [HistoryState] and
 * `onAction: (HistoryAction) -> Unit`.
 */
interface HistoryScreenFactory {
    @Composable
    fun CreateHistoryScreen()
}
