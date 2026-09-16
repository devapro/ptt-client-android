package com.github.devapro.pttdroid.model

import com.github.devapro.pttdroid.data.settings.LanguageMode
import com.github.devapro.pttdroid.data.settings.ServerMode
import com.github.devapro.pttdroid.data.settings.ThemeMode

/**
 * One field of the settings form changing. Carried by [MainAction.EditSettings].
 *
 * One action and one reducer for the whole form, rather than one pair per field: the form's
 * validation and merge rules live in [SettingsFormState], not scattered across a dozen reducers
 * that would all do the same `state.copy(settingsForm = ...)`.
 */
sealed interface SettingsEdit {
    data class Mode(val value: ServerMode) : SettingsEdit
    data class Address(val value: String) : SettingsEdit
    data class DisplayName(val value: String) : SettingsEdit
    data class Channel(val value: String) : SettingsEdit
    data class BroadcastStartBip(val enabled: Boolean) : SettingsEdit
    data class FloatingButton(val enabled: Boolean) : SettingsEdit
    data class HostServer(val enabled: Boolean) : SettingsEdit
    data class Theme(val value: ThemeMode) : SettingsEdit
    data class Language(val value: LanguageMode) : SettingsEdit
    data class Tls(val enabled: Boolean) : SettingsEdit
    data class Fingerprint(val value: String) : SettingsEdit
    data class AccessToken(val value: String) : SettingsEdit
    data class AccessTokenVisible(val visible: Boolean) : SettingsEdit
}
