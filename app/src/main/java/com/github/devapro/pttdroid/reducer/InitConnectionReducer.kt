package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttSessionLauncher
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Starts the foreground service that owns the session. Reducers no longer touch the socket or
 * the microphone directly — they delegate, and state arrives back via `PttController.state`.
 */
class InitConnectionReducer(
    private val launcher: PttSessionLauncher,
) : Reducer<MainAction.InitConnection, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.InitConnection> = MainAction.InitConnection::class

    override suspend fun reduce(
        action: MainAction.InitConnection,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        if (!state.micPermissionGranted) {
            return Reducer.Result(state, null, MainEvent.RequestMicPermission)
        }
        launcher.start()
        return Reducer.Result(state)
    }
}
