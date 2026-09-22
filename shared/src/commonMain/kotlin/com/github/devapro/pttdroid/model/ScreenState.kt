package com.github.devapro.pttdroid.model

import com.github.devapro.pttdroid.domain.PttState
import com.github.devapro.pttdroid.history.VoiceRecord

/**
 * What the UI renders. Derived from [PttState] rather than owning connection state itself, so
 * the Activity, the overlay and the widget cannot disagree about what is happening.
 */
data class ScreenState(
    val ptt: PttState = PttState(),
    val screen: Screen = Screen.Main,
    val micPermissionGranted: Boolean = false,
    /**
     * Whether "draw over other apps" is granted, for the floating-button warning in Settings.
     *
     * Defaults to `true` deliberately: desktop and iOS have no such permission concept and never
     * update this field, so the default has to be the one that shows no warning there. Android
     * overwrites it with the real grant state on entering Settings (see
     * `MainActivityViewModel.onOverlayPermissionResult`), exactly parallel to
     * [micPermissionGranted].
     */
    val canDrawOverlay: Boolean = true,
    /** Non-null exactly while [screen] is [Screen.Settings]. See [SettingsFormState]. */
    val settingsForm: SettingsFormState? = null,
    /** Received-while-backgrounded records, newest first. Mirrored from the history store. */
    val history: List<VoiceRecord> = emptyList(),
    /** Whether any [history] record arrived after the last time the history screen was opened. */
    val historyUnread: Boolean = false,
) {
    enum class Screen { Main, Settings, History }
}
