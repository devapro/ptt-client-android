package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.domain.PttSessionLauncher
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Disconnect: stops the session launcher. [MainState] is left untouched — every session fact
 * arrives back through `PttController.state`, so there is nothing here to clear by hand.
 */
class DisconnectReducer(
    private val launcher: PttSessionLauncher,
) : Reducer<MainAction.Disconnect, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.Disconnect> = MainAction.Disconnect::class

    override suspend fun reduce(
        action: MainAction.Disconnect,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        launcher.stop()
        return Reducer.Result(state)
    }
}
