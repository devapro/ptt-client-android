package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.data.ChannelSettingsRepository
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import kotlinx.coroutines.delay
import kotlin.reflect.KClass

class ReconnectReducer(
    private val socketConnection: PTTWebSocketConnection,
    private val channelSettingsRepository: ChannelSettingsRepository
): Reducer<MainAction.Reconnect, ScreenState, MainAction, MainEvent>{

    override val actionClass: KClass<MainAction.Reconnect> = MainAction.Reconnect::class

    override suspend fun reduce(
        action: MainAction.Reconnect,
        state: ScreenState
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        delay(1000L)
        socketConnection.reconnect(channelSettingsRepository.getChannel())
        return ScreenState.NoConnection to null
    }
}