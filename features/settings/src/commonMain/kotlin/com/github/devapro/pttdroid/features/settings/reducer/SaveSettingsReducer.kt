package com.github.devapro.pttdroid.features.settings.reducer

import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import com.github.devapro.pttdroid.shared.resources.*
import kotlin.reflect.KClass

/**
 * Persists [SettingsState.form], then hands straight on to [SettingsAction.Reconnect].
 *
 * The chain deliberately stays inside this one processor rather than being bridged through a
 * host event: the Activity used to do the saving itself — six suspending repository calls and
 * two dispatches from inside a Compose callback. That put persistence outside the MVI loop, and
 * each of the six writes emitted separately, so the controller could pick up a half-applied
 * address mid-save (known-issues #25). One write, one emission, one reconnect — and the
 * [SettingsAction.Reconnect] the chain produces is resolved by [ReconnectReducer] in this same
 * processor, so no DataStore emission can interleave between the two.
 *
 * Reads the form out of [state] rather than a payload on the action: the view no longer builds
 * the domain object, so there is nothing left for [SettingsAction.SaveSettings] to carry. The
 * `hasError` guard is a second line of defence, not the only one — the Save button is already
 * disabled on error — because a reducer must not depend on the view having got that right.
 */
class SaveSettingsReducer(
    private val settingsRepository: SettingsRepository,
) : Reducer<SettingsAction.SaveSettings, SettingsState, SettingsAction, UiEvent> {

    override val actionClass: KClass<SettingsAction.SaveSettings> =
        SettingsAction.SaveSettings::class

    override suspend fun reduce(
        action: SettingsAction.SaveSettings,
        state: SettingsState,
    ): Reducer.Result<SettingsState, SettingsAction, UiEvent?> {
        val form = state.form ?: return Reducer.Result(state)
        if (form.hasError) return Reducer.Result(state)
        settingsRepository.save(form.toSettings())
        return Reducer.Result(
            state.copy(form = null),
            SettingsAction.Reconnect,
            UiEvent.ShowMessage(Res.string.settings_saved),
        )
    }
}
