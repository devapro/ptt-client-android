package com.github.devapro.pttdroid.features.settings.reducer

import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * "Draw over other apps" cannot be granted from a runtime permission dialog, only from a system
 * Settings screen — so this reducer changes no state itself and only forwards
 * [UiEvent.RequestOverlayPermission] for the platform layer (`MainActivity`) to act on. Pulling
 * this escape hatch into MVI, rather than a plain lambda passed into `SettingsScreen`, keeps every
 * user intent on that screen expressible as a [SettingsAction].
 */
class RequestOverlayPermissionReducer :
    Reducer<SettingsAction.RequestOverlayPermission, SettingsState, SettingsAction, UiEvent> {

    override val actionClass: KClass<SettingsAction.RequestOverlayPermission> =
        SettingsAction.RequestOverlayPermission::class

    override suspend fun reduce(
        action: SettingsAction.RequestOverlayPermission,
        state: SettingsState,
    ): Reducer.Result<SettingsState, SettingsAction, UiEvent?> =
        Reducer.Result(state, null, UiEvent.RequestOverlayPermission)
}
