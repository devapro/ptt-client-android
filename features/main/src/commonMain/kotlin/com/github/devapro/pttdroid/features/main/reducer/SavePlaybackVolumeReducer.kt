package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * The volume slider let go: one write for the whole drag.
 *
 * Still applies the level as well as storing it, so that a tap straight onto a point of the
 * track — which produces a value and a release with nothing in between — is not silent until
 * the next connect.
 */
class SavePlaybackVolumeReducer(
    private val controller: PttController,
    private val settingsRepository: SettingsRepository,
) : Reducer<MainAction.SavePlaybackVolume, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.SavePlaybackVolume> =
        MainAction.SavePlaybackVolume::class

    override suspend fun reduce(
        action: MainAction.SavePlaybackVolume,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        controller.setPlaybackVolume(action.volume)
        settingsRepository.setPlaybackVolume(action.volume)
        return Reducer.Result(state)
    }
}
