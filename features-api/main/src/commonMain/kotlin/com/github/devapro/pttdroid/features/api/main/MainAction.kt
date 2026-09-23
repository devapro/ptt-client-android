package com.github.devapro.pttdroid.features.api.main

import com.github.devapro.pttdroid.data.settings.AudioOutput

/**
 * Main-screen intents — everything the Main slice's `ViewModel` accepts.
 *
 * The nav and settings/history variants are deliberately absent: entering another screen is host
 * navigation (the gear and history buttons call host lambdas, they do not dispatch actions), and
 * the settings/history payload actions live in their own slices (`SettingsAction`,
 * `HistoryAction`). `Reconnect` is absent because its only live dispatcher is the settings save,
 * which chains into `SettingsAction.Reconnect` inside `:features:settings`'s own processor.
 */
sealed interface MainAction {

    /** Ensure the session is running (starts the foreground service). */
    data object InitConnection : MainAction

    /** Tear the session down. */
    data object Disconnect : MainAction

    /** PTT pressed — asks the server for the talk floor. */
    data object Speak : MainAction

    /** PTT released. */
    data object StopSpeak : MainAction

    data class SetChannel(val channel: Int) : MainAction

    /** Clear the last transport/protocol error so a stale one stops looking like a live fault. */
    data object DismissError : MainAction

    /** Loudspeaker or handset receiver. One deliberate tap, so it is applied and written at once. */
    data class SetAudioOutput(val output: AudioOutput) : MainAction

    /**
     * The volume slider moving under a finger: applied to the speaker immediately so the change
     * is audible while dragging, and deliberately **not** written down — see
     * [SavePlaybackVolume].
     */
    data class SetPlaybackVolume(val volume: Float) : MainAction

    /**
     * The volume slider let go. One DataStore write for the whole drag, rather than one per
     * value the slider passed through on the way.
     */
    data class SavePlaybackVolume(val volume: Float) : MainAction
}
