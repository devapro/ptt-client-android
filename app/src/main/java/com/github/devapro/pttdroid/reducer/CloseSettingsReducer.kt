package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

class CloseSettingsReducer :
    Reducer<MainAction.CloseSettings, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.CloseSettings> = MainAction.CloseSettings::class

    override suspend fun reduce(
        action: MainAction.CloseSettings,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> =
        Reducer.Result(state.copy(screen = ScreenState.Screen.Main))
}
