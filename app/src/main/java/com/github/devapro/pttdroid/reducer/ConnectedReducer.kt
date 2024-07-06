package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

class ConnectedReducer: Reducer<MainAction.Connected, ScreenState, MainAction, MainEvent>{

    override val actionClass: KClass<MainAction.Connected> = MainAction.Connected::class

    override suspend fun reduce(
        action: MainAction.Connected,
        state: ScreenState
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        return ScreenState.Connected(
            isSpeaking = false,
            chanelNumber = 1
        ) to null
    }
}