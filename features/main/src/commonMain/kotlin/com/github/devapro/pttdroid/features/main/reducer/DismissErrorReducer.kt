package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Clears the controller's error, since that is where it lives — see below.
 *
 * Clears the error on the controller rather than on [MainState], because that is where it
 * lives — clearing a copy here would have it reappear on the controller's next emission.
 */
class DismissErrorReducer(
    private val controller: PttController,
) : Reducer<MainAction.DismissError, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.DismissError> = MainAction.DismissError::class

    override suspend fun reduce(
        action: MainAction.DismissError,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        controller.clearError()
        return Reducer.Result(state)
    }
}
