package com.github.devapro.pttdroid.model

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput

/** UI intents. The domain state itself lives in `domain/PttState`. */
sealed interface MainAction {

    /** Ensure the session is running (starts the foreground service). */
    data object InitConnection : MainAction

    /** Tear the session down. */
    data object Disconnect : MainAction

    /** Force a reconnect, e.g. after changing the server address. */
    data object Reconnect : MainAction

    /** PTT pressed — asks the server for the talk floor. */
    data object Speak : MainAction

    /** PTT released. */
    data object StopSpeak : MainAction

    data class SetChannel(val channel: Int) : MainAction

    data object OpenSettings : MainAction

    data object CloseSettings : MainAction

    /** Persist the edited settings, close Settings and reconnect onto the new address. */
    data class SaveSettings(val settings: AppSettings) : MainAction

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
