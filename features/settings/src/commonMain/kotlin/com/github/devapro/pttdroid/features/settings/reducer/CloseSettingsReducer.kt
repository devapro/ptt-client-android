package com.github.devapro.pttdroid.features.settings.reducer

import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Discards the in-progress form: reopening seeds a fresh one from disk, so nothing stale from
 * this visit can leak into the next. Leaving the screen itself is the Voyager `Navigator`'s
 * job: the screen root pops when the form goes seeded → null, which this dispatch completes.
 */
class CloseSettingsReducer :
    Reducer<SettingsAction.Close, SettingsState, SettingsAction, UiEvent> {

    override val actionClass: KClass<SettingsAction.Close> = SettingsAction.Close::class

    override suspend fun reduce(
        action: SettingsAction.Close,
        state: SettingsState,
    ): Reducer.Result<SettingsState, SettingsAction, UiEvent?> = Reducer.Result(
        state.copy(form = null),
    )
}
