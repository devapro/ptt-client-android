package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/** Clamps the requested channel; the UI used to allow 0 and negative values. */
class SetChannelReducer(
    private val controller: PttController,
) : Reducer<MainAction.SetChannel, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.SetChannel> = MainAction.SetChannel::class

    override suspend fun reduce(
        action: MainAction.SetChannel,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        val clamped = AppSettings.clampChannel(action.channel)
        if (clamped == state.ptt.channel) return Reducer.Result(state)
        controller.setChannel(clamped)
        return Reducer.Result(state)
    }
}
