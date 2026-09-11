package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Switches playback between the loudspeaker and the handset receiver, and writes the choice.
 *
 * Applied before it is persisted, in that order on purpose: the write is a DataStore
 * transaction and the tap is meant to be instant. The UI renders the persisted value, so the
 * two converge one emission later either way.
 */
class SetAudioOutputReducer(
    private val controller: PttController,
    private val settingsRepository: SettingsRepository,
) : Reducer<MainAction.SetAudioOutput, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.SetAudioOutput> = MainAction.SetAudioOutput::class

    override suspend fun reduce(
        action: MainAction.SetAudioOutput,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        controller.setAudioOutput(action.output)
        settingsRepository.setAudioOutput(action.output)
        return Reducer.Result(state)
    }
}
