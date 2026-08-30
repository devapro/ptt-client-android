package com.github.devapro.pttdroid.network.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client-side mirror of the wire contract. The canonical spec lives in the server repo at
 * `ptt-server/docs/protocol.md` — keep these types in step with it.
 *
 * The WebSocket frame type is the discriminator: binary frames carry PCM audio, text frames
 * carry exactly one of these JSON control messages.
 */
const val PROTOCOL_VERSION: Int = 1

@Serializable
data class AudioParams(
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val encoding: String = "pcm16le",
    val frameBytes: Int = 1_280,
)

@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("talk_request")
data object TalkRequest : ClientMessage

@Serializable
@SerialName("talk_release")
data object TalkRelease : ClientMessage

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("welcome")
data class Welcome(
    val clientId: String,
    val channel: Int,
    val peers: Int,
    val audio: AudioParams = AudioParams(),
) : ServerMessage

@Serializable
@SerialName("floor")
data class Floor(
    val holderId: String? = null,
    val holderName: String? = null,
    val isSelf: Boolean = false,
) : ServerMessage

@Serializable
@SerialName("peers")
data class Peers(val count: Int) : ServerMessage

@Serializable
@SerialName("error")
data class ProtocolError(val code: String, val message: String) : ServerMessage

object ErrorCodes {
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val INVALID_CHANNEL = "invalid_channel"
    const val FLOOR_BUSY = "floor_busy"
    const val NOT_FLOOR_HOLDER = "not_floor_holder"
    const val FRAME_TOO_LARGE = "frame_too_large"
    const val MALFORMED_MESSAGE = "malformed_message"
}

val ProtocolJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

fun ClientMessage.encode(): String = ProtocolJson.encodeToString<ClientMessage>(this)

fun decodeServerMessage(text: String): ServerMessage = ProtocolJson.decodeFromString(text)

// Inverse directions, used by the optional embedded relay in `internalserver/`.
fun ServerMessage.encode(): String = ProtocolJson.encodeToString<ServerMessage>(this)

fun decodeClientMessage(text: String): ClientMessage = ProtocolJson.decodeFromString(text)
