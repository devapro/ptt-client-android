package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Requests the talk floor.
 *
 * THE INVARIANT THIS FILE GUARDS: pressing PTT must never open the microphone. Transmission
 * does not begin here — the controller waits for the server to grant the floor, so two people
 * pressing at once cannot both be heard. The "already transmitting / already requesting"
 * debounce is deliberately *not* mirrored here: it lives inside `PttController.requestTalk()`
 * and is delegated to, so there is one copy of that check and the
 * reducer cannot drift from it.
 */
class StartSpeakReducer(
    private val controller: PttController,
) : Reducer<MainAction.Speak, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.Speak> = MainAction.Speak::class

    override suspend fun reduce(
        action: MainAction.Speak,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        if (!state.micPermissionGranted) {
            return Reducer.Result(state, null, UiEvent.RequestMicPermission)
        }
        if (!state.ptt.canTalk) return Reducer.Result(state)
        controller.requestTalk()
        return Reducer.Result(state)
    }
}
