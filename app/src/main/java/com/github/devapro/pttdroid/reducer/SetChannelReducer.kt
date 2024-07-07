package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.data.ChannelSettingsRepository
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

class SetChannelReducer(
    private val channelSettingsRepository: ChannelSettingsRepository
): Reducer<MainAction.SetChannel, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.SetChannel> = MainAction.SetChannel::class

    override suspend fun reduce(
        action: MainAction.SetChannel,
        state: ScreenState
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        return if (state is ScreenState.Connected) {
            channelSettingsRepository.setChannel(action.channel)
            state.copy(chanelNumber = action.channel) to MainAction.Reconnect
        } else {
            state to null
        }
    }
}