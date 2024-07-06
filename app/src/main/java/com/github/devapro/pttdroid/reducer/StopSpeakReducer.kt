package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.audio.VoiceRecorder
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

class StopSpeakReducer(
    private val voiceRecorder: VoiceRecorder
): Reducer<MainAction.StopSpeak, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.StopSpeak> = MainAction.StopSpeak::class

    override suspend fun reduce(
        action: MainAction.StopSpeak,
        state: ScreenState
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        voiceRecorder.stopRecord()
        return if (state is ScreenState.Connected) {
            state.copy(isSpeaking = false) to null
        } else {
            ScreenState.NoConnection to null
        }
    }
}