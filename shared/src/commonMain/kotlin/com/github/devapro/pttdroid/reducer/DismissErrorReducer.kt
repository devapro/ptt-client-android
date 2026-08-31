package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Clears the error on the controller rather than on [ScreenState], because that is where it
 * lives — clearing a copy here would have it reappear on the controller's next emission.
 */
class DismissErrorReducer(
    private val controller: PttController,
) : Reducer<MainAction.DismissError, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.DismissError> = MainAction.DismissError::class

    override suspend fun reduce(
        action: MainAction.DismissError,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        controller.clearError()
        return Reducer.Result(state)
    }
}
