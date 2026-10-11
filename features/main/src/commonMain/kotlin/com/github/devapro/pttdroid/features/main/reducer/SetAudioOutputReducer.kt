package com.github.devapro.pttdroid.features.main.reducer

import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Switches the playback route on the controller and persists the choice.
 *
 * Applied before it is persisted, in that order on purpose: the write is a DataStore
 * transaction and the tap is meant to be instant. The UI renders the persisted value, so the
 * two converge one emission later either way.
 */
class SetAudioOutputReducer(
    private val controller: PttController,
    private val settingsRepository: SettingsRepository,
) : Reducer<MainAction.SetAudioOutput, MainState, MainAction, UiEvent> {

    override val actionClass: KClass<MainAction.SetAudioOutput> = MainAction.SetAudioOutput::class

    override suspend fun reduce(
        action: MainAction.SetAudioOutput,
        state: MainState,
    ): Reducer.Result<MainState, MainAction, UiEvent?> {
        controller.setAudioOutput(action.output)
        settingsRepository.setAudioOutput(action.output)
        return Reducer.Result(state)
    }
}
