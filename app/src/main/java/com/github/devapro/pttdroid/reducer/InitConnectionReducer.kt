package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.data.ChannelSettingsRepository
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import kotlin.reflect.KClass

class InitConnectionReducer(
    private val socketConnection: PTTWebSocketConnection,
    private val channelSettingsRepository: ChannelSettingsRepository
): Reducer<MainAction.InitConnection, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.InitConnection> = MainAction.InitConnection::class

    override suspend fun reduce(
        action: MainAction.InitConnection,
        state: ScreenState
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        socketConnection.start(channelSettingsRepository.getChannel())
        return ScreenState.NoConnection to null
    }
}