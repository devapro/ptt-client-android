package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Reconnects immediately. Automatic retries are the controller's job and use exponential
 * backoff — the old reducer slept a flat second and looped forever.
 */
class ReconnectReducer(
    private val controller: PttController,
) : Reducer<MainAction.Reconnect, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.Reconnect> = MainAction.Reconnect::class

    override suspend fun reduce(
        action: MainAction.Reconnect,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        controller.restart()
        return Reducer.Result(state)
    }
}
