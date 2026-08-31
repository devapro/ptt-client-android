package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttSessionLauncher
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

class DisconnectReducer(
    private val launcher: PttSessionLauncher,
) : Reducer<MainAction.Disconnect, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.Disconnect> = MainAction.Disconnect::class

    override suspend fun reduce(
        action: MainAction.Disconnect,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        launcher.stop()
        return Reducer.Result(state)
    }
}
