package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Requests the talk floor. Transmission does not begin here — the controller waits for the
 * server to grant the floor, so two people pressing at once cannot both be heard.
 */
class StartSpeakReducer(
    private val controller: PttController,
) : Reducer<MainAction.Speak, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.Speak> = MainAction.Speak::class

    override suspend fun reduce(
        action: MainAction.Speak,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        if (!state.micPermissionGranted) {
            return Reducer.Result(state, null, MainEvent.RequestMicPermission)
        }
        if (!state.ptt.canTalk) return Reducer.Result(state)
        controller.requestTalk()
        return Reducer.Result(state)
    }
}
