package com.github.devapro.pttdroid.features.settings.reducer

import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Applies one [com.github.devapro.pttdroid.features.api.settings.SettingsEdit] to the *current*
 * [SettingsState.form], not to a snapshot the view held onto — that is what keeps a stray
 * emission elsewhere in the app from ever being able to clobber an edit in progress, the same
 * defect [OpenSettingsReducer] seeds once against.
 *
 * A no-op, rather than an error, when Settings is not open: [SettingsState.form] is `null`
 * outside that screen, and an edit racing a close is expected, not exceptional.
 */
class EditSettingsReducer :
    Reducer<SettingsAction.EditSettings, SettingsState, SettingsAction, UiEvent> {

    override val actionClass: KClass<SettingsAction.EditSettings> =
        SettingsAction.EditSettings::class

    override suspend fun reduce(
        action: SettingsAction.EditSettings,
        state: SettingsState,
    ): Reducer.Result<SettingsState, SettingsAction, UiEvent?> = Reducer.Result(
        state.copy(form = state.form?.apply(action.edit)),
    )
}
