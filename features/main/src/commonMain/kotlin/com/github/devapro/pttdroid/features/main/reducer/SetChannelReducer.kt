package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Changes the talker channel on the live session. Clamps the requested
 * channel (known-issues #12 — the UI used to allow 0 and negative values) and skips a change
 * the session already has, so a no-op step does not tear down and redial the socket.
 */
class SetChannelReducer(
    private val controller: PttController,
) : Reducer<MainAction.SetChannel, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.SetChannel> = MainAction.SetChannel::class

    override suspend fun reduce(
        action: MainAction.SetChannel,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        val clamped = AppSettings.clampChannel(action.channel)
        if (clamped == state.ptt.channel) return Reducer.Result(state)
        controller.setChannel(clamped)
        return Reducer.Result(state)
    }
}
