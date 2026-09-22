package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/** Leaves the history screen. Playback is not stopped here — it survives going back to Main. */
class CloseHistoryReducer :
    Reducer<MainAction.CloseHistory, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.CloseHistory> = MainAction.CloseHistory::class

    override suspend fun reduce(
        action: MainAction.CloseHistory,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> = Reducer.Result(
        state.copy(screen = ScreenState.Screen.Main),
    )
}
