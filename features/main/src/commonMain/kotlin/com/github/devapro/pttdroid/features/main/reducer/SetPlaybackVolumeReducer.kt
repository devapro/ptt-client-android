package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * The live half of the volume slider: reaches the speaker, touches no storage.
 *
 * Persisting here instead would mean a DataStore transaction per value the slider emits while a
 * finger is on it — a hundred or more per drag, each one re-serialising the whole preferences
 * file and each one emitting a new `AppSettings` that recomposes the screen underneath the
 * gesture. `SavePlaybackVolumeReducer` does the writing, once, on release (known-issues #34).
 */
class SetPlaybackVolumeReducer(
    private val controller: PttController,
) : Reducer<MainAction.SetPlaybackVolume, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.SetPlaybackVolume> =
        MainAction.SetPlaybackVolume::class

    override suspend fun reduce(
        action: MainAction.SetPlaybackVolume,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        controller.setPlaybackVolume(action.volume)
        return Reducer.Result(state)
    }
}
