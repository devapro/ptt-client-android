package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.network.protocol.AudioParams
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.Peers
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.TalkRelease
import com.github.devapro.pttdroid.network.protocol.TalkRequest
import com.github.devapro.pttdroid.network.protocol.Welcome
import com.github.devapro.pttdroid.network.protocol.decodeServerMessage
import com.github.devapro.pttdroid.network.protocol.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format is a contract with a separate codebase (`ptt-server`), so these tests pin
 * the exact JSON rather than just round-tripping.
 */
class ProtocolSerializationTest {

    @Test
    fun `talk_request encodes to the documented shape`() {
        assertEquals("""{"type":"talk_request"}""", TalkRequest.encode())
    }

    @Test
    fun `talk_release encodes to the documented shape`() {
        assertEquals("""{"type":"talk_release"}""", TalkRelease.encode())
    }

    @Test
    fun `welcome decodes including nested audio params`() {
        val json = """
            {"type":"welcome","clientId":"abc-123","channel":7,"peers":3,
             "audio":{"sampleRate":16000,"channels":1,"encoding":"pcm16le","frameBytes":1280}}
        """.trimIndent()

        val message = decodeServerMessage(json)
        assertTrue(message is Welcome)
        message as Welcome
        assertEquals("abc-123", message.clientId)
        assertEquals(7, message.channel)
        assertEquals(3, message.peers)
        assertEquals(AudioParams(16_000, 1, "pcm16le", 1_280), message.audio)
    }

    @Test
    fun `floor decodes a held floor`() {
        val message = decodeServerMessage(
            """{"type":"floor","holderId":"id-1","holderName":"Alice","isSelf":true}""",
        )
        assertEquals(Floor("id-1", "Alice", true), message)
    }

    @Test
    fun `floor decodes a released floor with explicit nulls`() {
        val message = decodeServerMessage(
            """{"type":"floor","holderId":null,"holderName":null,"isSelf":false}""",
        )
        assertTrue(message is Floor)
        assertNull((message as Floor).holderId)
    }

    @Test
    fun `peers and error decode`() {
        assertEquals(Peers(4), decodeServerMessage("""{"type":"peers","count":4}"""))
        assertEquals(
            ProtocolError("floor_busy", "Channel 1 is busy"),
            decodeServerMessage("""{"type":"error","code":"floor_busy","message":"Channel 1 is busy"}"""),
        )
    }

    @Test
    fun `unknown fields are tolerated so a newer server does not break us`() {
        val message = decodeServerMessage(
            """{"type":"peers","count":2,"somethingNew":"ignored"}""",
        )
        assertEquals(Peers(2), message)
    }
}
