package com.github.devapro.pttdroid.model

import com.github.devapro.pttdroid.data.settings.AppSettings

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
}
