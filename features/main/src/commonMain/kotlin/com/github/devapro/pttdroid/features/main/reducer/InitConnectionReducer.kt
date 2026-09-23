package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.domain.PttSessionLauncher
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Starts the foreground service that owns the session.
 *
 * Reducers no longer touch the socket or the microphone directly — they delegate, and state
 * arrives back via `PttController.state`. The mic gate is load-bearing: without the grant the
 * reducer asks the host for permission instead of starting a service that could not record.
 */
class InitConnectionReducer(
    private val launcher: PttSessionLauncher,
) : Reducer<MainAction.InitConnection, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.InitConnection> = MainAction.InitConnection::class

    override suspend fun reduce(
        action: MainAction.InitConnection,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        if (!state.micPermissionGranted) {
            return Reducer.Result(state, null, UiEvent.RequestMicPermission)
        }
        launcher.start()
        return Reducer.Result(state)
    }
}
