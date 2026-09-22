package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/** Stops a history replay. No-op — like every other stop here — when nothing is playing. */
class StopRecordPlaybackReducer(
    private val controller: PttController,
) : Reducer<MainAction.StopRecordPlayback, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.StopRecordPlayback> =
        MainAction.StopRecordPlayback::class

    override suspend fun reduce(
        action: MainAction.StopRecordPlayback,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        controller.stopHistoryPlayback()
        return Reducer.Result(state)
    }
}
