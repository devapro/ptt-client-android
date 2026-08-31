package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.network.protocol.ClientMessage
import com.github.devapro.pttdroid.network.protocol.ServerMessage
import kotlinx.coroutines.flow.Flow

/** Something that happened on the transport, as opposed to a protocol-level message. */
sealed interface ConnectionEvent {
    data object Connected : ConnectionEvent

    data class Disconnected(val reason: String?, val cause: Throwable? = null) : ConnectionEvent

    data class Control(val message: ServerMessage) : ConnectionEvent

    data class Audio(val pcm: ByteArray) : ConnectionEvent {
        // ByteArray needs structural equality spelled out.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Audio && pcm.contentEquals(other.pcm))

        override fun hashCode(): Int = pcm.contentHashCode()
    }
}

/**
 * Transport abstraction over the PTT WebSocket.
 *
 * Exists so the domain layer can be exercised with a fake — the previous concrete
 * `PTTWebSocketConnection` was wired directly into DI and could not be substituted in a test.
 */
interface PttConnection {

    val events: Flow<ConnectionEvent>

    /** Opens a connection to [endpoint]. Suspends until the socket closes. */
    suspend fun connect(endpoint: PttEndpoint)

    suspend fun disconnect()

    /** Sends one control message as a text frame. */
    suspend fun send(message: ClientMessage)

    /** Sends one audio frame as a binary frame. Returns false if not currently connected. */
    suspend fun sendAudio(pcm: ByteArray): Boolean
}
