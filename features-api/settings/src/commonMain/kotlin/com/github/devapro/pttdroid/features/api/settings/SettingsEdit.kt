package com.github.devapro.pttdroid.features.api.settings

import com.github.devapro.pttdroid.data.settings.LanguageMode
import com.github.devapro.pttdroid.data.settings.ServerMode
import com.github.devapro.pttdroid.data.settings.ThemeMode

/**
 * One field of the settings form changing — the Settings slice's edit vocabulary.
 *
 * One action and one reducer for the whole form, rather than one pair per field: the form's
 * validation and merge rules live in [SettingsFormState], not scattered across a dozen reducers
 * that would all do the same `state.copy(form = ...)`. Carried by
 * [SettingsAction.EditSettings][SettingsAction.EditSettings] and applied by
 * [SettingsFormState.apply].
 *
 * Lives here, next to the form it edits, since the per-feature split made Settings the only
 * feature that speaks it.
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
