package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.audio.VoiceRecorder
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

class StartSpeakReducer(
    private val voiceRecorder: VoiceRecorder
): Reducer<MainAction.Speak, ScreenState.Connected, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.Speak> = MainAction.Speak::class

    override suspend fun reduce(
        action: MainAction.Speak,
        state: ScreenState.Connected
    ): Reducer.Result<ScreenState.Connected, MainAction, MainEvent?> {
        voiceRecorder.startRecord()
        return Reducer.Result(state.copy(isSpeaking = true))
    }
}