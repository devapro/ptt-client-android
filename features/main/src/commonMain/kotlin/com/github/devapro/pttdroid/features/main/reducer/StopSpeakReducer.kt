package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Releases the talk floor. The no-op-when-not-talking check lives
 * inside `PttController.releaseTalk()` and is delegated to, exactly as before.
 */
class StopSpeakReducer(
    private val controller: PttController,
) : Reducer<MainAction.StopSpeak, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.StopSpeak> = MainAction.StopSpeak::class

    override suspend fun reduce(
        action: MainAction.StopSpeak,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        controller.releaseTalk()
        return Reducer.Result(state)
    }
}
