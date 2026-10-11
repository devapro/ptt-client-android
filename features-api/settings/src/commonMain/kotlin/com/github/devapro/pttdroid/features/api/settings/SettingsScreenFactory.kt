package com.github.devapro.pttdroid.features.api.settings

import androidx.compose.runtime.Composable

/**
 * The contract for rendering the Settings screen. Implemented by `:features:settings`, bound in
 * Koin, and resolved by [SettingsScreen.Content].
 *
 * It takes no arguments since the Voyager migration: the implementation is a self-sufficient screen
 * root that resolves `SettingsViewModel`, dispatches `SettingsAction.Open` once per entry, pops the
 * `Navigator` on the form's seeded → null edge, and collects the view model's events into
 * [com.github.devapro.pttdroid.ui.LocalPttUiEffects]. The pure `SettingsScreen` composable in
 * `:features:settings` still takes this slice's [SettingsState] and `onAction: (SettingsAction) ->
 * Unit`, which is what `SettingsScreenTest` drives directly.
 */
interface SettingsScreenFactory {
    @Composable
    fun CreateSettingsScreen()
}
