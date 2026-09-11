package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
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
) : Reducer<MainAction.SavePlaybackVolume, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.SavePlaybackVolume> =
        MainAction.SavePlaybackVolume::class

    override suspend fun reduce(
        action: MainAction.SavePlaybackVolume,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        controller.setPlaybackVolume(action.volume)
        settingsRepository.setPlaybackVolume(action.volume)
        return Reducer.Result(state)
    }
}
