package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * The live half of the volume slider: reaches the speaker, touches no storage.
 *
 * Persisting here instead would mean a DataStore transaction per value the slider emits while a
 * finger is on it — a hundred or more per drag, each one re-serialising the whole preferences
 * file and each one emitting a new `AppSettings` that recomposes the screen underneath the
 * gesture. `SavePlaybackVolumeReducer` does the writing, once, on release.
 */
class SetPlaybackVolumeReducer(
    private val controller: PttController,
) : Reducer<MainAction.SetPlaybackVolume, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.SetPlaybackVolume> =
        MainAction.SetPlaybackVolume::class

    override suspend fun reduce(
        action: MainAction.SetPlaybackVolume,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        controller.setPlaybackVolume(action.volume)
        return Reducer.Result(state)
    }
}
