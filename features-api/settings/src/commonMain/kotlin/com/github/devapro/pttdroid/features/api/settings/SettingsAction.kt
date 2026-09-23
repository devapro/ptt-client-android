package com.github.devapro.pttdroid.features.api.settings


/**
 * Settings-screen intents — the whole Settings vocabulary of the app's action space, plus the
 * [Reconnect] that `SaveSettings` chains into it. Navigation itself is not an action: entering
 * and leaving the screen is Voyager stack state (`navigator.push`/`pop` in the screen root), so
 * there is no `screen =` write anywhere in this feature — see `HistoryAction`'s KDoc for the
 * same split.
 */
sealed interface SettingsAction {

    /** Open the settings screen: seeds [SettingsState.form] from what is on disk, once. */
    data object Open : SettingsAction

    /** Leave the settings screen, discarding the in-progress form. */
    data object Close : SettingsAction

    /** One field of the open settings form changing. Applied to [SettingsState.form]. */
    data class EditSettings(val edit: SettingsEdit) : SettingsAction

    /**
     * Persist the settings form, drop it, and reconnect onto the new address.
     *
     * Carries no payload: the form being saved is [SettingsState.form], the single copy the
     * view and the reducer both read — see `SaveSettingsReducer`.
     */
    data object SaveSettings : SettingsAction

    /** The floating button needs "draw over other apps"; only a Settings screen can grant it. */
    data object RequestOverlayPermission : SettingsAction

    /**
     * Force a reconnect, e.g. after changing the server address.
     *
     * Lives here rather than staying a `MainAction` because its only dispatcher is this
     * feature's `SaveSettingsReducer`, and the save → reconnect chain must stay inside one
     * processor (see `SaveSettingsReducer`'s KDoc in `:features:settings`).
     */
    data object Reconnect : SettingsAction
}
