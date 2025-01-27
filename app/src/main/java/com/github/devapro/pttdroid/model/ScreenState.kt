package com.github.devapro.pttdroid.model

sealed interface ScreenState {

    data object NoConnection : ScreenState

    data class Connected(
        val isSpeaking: Boolean,
        val chanelNumber: Int
    ): ScreenState
}