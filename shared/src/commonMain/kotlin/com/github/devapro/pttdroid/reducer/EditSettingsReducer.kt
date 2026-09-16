package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Applies one [com.github.devapro.pttdroid.model.SettingsEdit] to the *current*
 * [ScreenState.settingsForm], not to a snapshot the view held onto — that is what keeps a stray
 * emission elsewhere in the app from ever being able to clobber an edit in progress, the same
 * defect [OpenSettingsReducer] seeds once against.
 *
 * A no-op, rather than an error, when Settings is not open: [ScreenState.settingsForm] is `null`
 * outside that screen, and an edit racing a close is expected, not exceptional.
 */
class EditSettingsReducer :
    Reducer<MainAction.EditSettings, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.EditSettings> = MainAction.EditSettings::class

    override suspend fun reduce(
        action: MainAction.EditSettings,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> = Reducer.Result(
        state.copy(settingsForm = state.settingsForm?.apply(action.edit)),
    )
}
