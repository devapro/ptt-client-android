package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * "Draw over other apps" cannot be granted from a runtime permission dialog, only from a system
 * Settings screen — so this reducer changes no state itself and only forwards
 * [MainEvent.RequestOverlayPermission] for the platform layer (`MainActivity`) to act on. Pulling
 * this escape hatch into MVI, rather than a plain lambda passed into `SettingsScreen`, keeps every
 * user intent on that screen expressible as a [MainAction].
 */
class RequestOverlayPermissionReducer :
    Reducer<MainAction.RequestOverlayPermission, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.RequestOverlayPermission> =
        MainAction.RequestOverlayPermission::class

    override suspend fun reduce(
        action: MainAction.RequestOverlayPermission,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> =
        Reducer.Result(state, null, MainEvent.RequestOverlayPermission)
}
