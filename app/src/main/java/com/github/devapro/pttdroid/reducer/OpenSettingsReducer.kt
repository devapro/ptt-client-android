package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

class OpenSettingsReducer :
    Reducer<MainAction.OpenSettings, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.OpenSettings> = MainAction.OpenSettings::class

    override suspend fun reduce(
        action: MainAction.OpenSettings,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> =
        Reducer.Result(state.copy(screen = ScreenState.Screen.Settings))
}
