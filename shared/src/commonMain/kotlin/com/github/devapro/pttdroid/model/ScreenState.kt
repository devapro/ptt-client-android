package com.github.devapro.pttdroid.model

import com.github.devapro.pttdroid.domain.PttState

/**
 * What the UI renders. Derived from [PttState] rather than owning connection state itself, so
 * the Activity, the overlay and the widget cannot disagree about what is happening.
 */
data class ScreenState(
    val ptt: PttState = PttState(),
    val screen: Screen = Screen.Main,
    val micPermissionGranted: Boolean = false,
) {
    enum class Screen { Main, Settings }
}
