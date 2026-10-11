package com.github.devapro.pttdroid.features.settings.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Reconnects immediately. Automatic retries are the controller's job and use exponential
 * backoff — the old reducer slept a flat second and looped forever.
 *
 * Lives here, next to [SaveSettingsReducer], because the only dispatcher of
 * [SettingsAction.Reconnect] is that reducer's chain, and the chain's whole point is that one
 * write and the reconnect that must follow it resolve inside a single processor (see
 * [SaveSettingsReducer]).
 */
class ReconnectReducer(
    private val controller: PttController,
) : Reducer<SettingsAction.Reconnect, SettingsState, SettingsAction, UiEvent> {

    override val actionClass: KClass<SettingsAction.Reconnect> = SettingsAction.Reconnect::class

    override suspend fun reduce(
        action: SettingsAction.Reconnect,
        state: SettingsState,
    ): Reducer.Result<SettingsState, SettingsAction, UiEvent?> {
        controller.restart()
        return Reducer.Result(state)
    }
}
