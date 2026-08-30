package com.github.devapro.pttdroid.domain

/** Transport-level status, modelled explicitly rather than as a pair of booleans. */
sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
}

/**
 * The whole app's view of the PTT session. Owned by [PttController] and observed by the UI,
 * the foreground service, the floating overlay and the widget alike.
 */
data class PttState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val channel: Int = 1,
    /** True only once the server has actually granted us the floor. */
    val isTransmitting: Boolean = false,
    /** Set while we are waiting for the server's answer to a talk_request. */
    val isRequestingFloor: Boolean = false,
    val floorHolderName: String? = null,
    val isFloorHeldByOther: Boolean = false,
    val peers: Int = 0,
    val lastError: String? = null,
) {
    val isConnected: Boolean get() = status is ConnectionStatus.Connected

    /** Receiving someone else's transmission — drives the incoming-call animation. */
    val isReceiving: Boolean get() = isConnected && isFloorHeldByOther

    /** The PTT control is only usable when connected and nobody else holds the floor. */
    val canTalk: Boolean get() = isConnected && !isFloorHeldByOther
}
