package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.network.protocol.Ping
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

/**
 * R2 at the transport itself: [KtorPttConnection.send] reports false — never throws, never
 * silently "succeeds" — when there is no live session. This is the null-session case the
 * requestTalk rollback depends on; the write-failure case (an exception from the socket) is
 * covered by the same runCatching shape as sendAudio and needs a live-then-killed session that
 * the interface cannot fake from here.
 */
class KtorPttConnectionSendWithoutSessionTest {

    @Test
    fun `a control send with no session reports false instead of throwing or silently succeeding`() =
        runTest {
            val connection = KtorPttConnection()
            try {
                assertFalse(connection.send(Ping), "no session must be a reported failure")
                assertFalse(
                    connection.sendAudio(ByteArray(640)),
                    "sendAudio keeps its existing false-without-session contract",
                )
            } finally {
                connection.shutdown()
            }
        }

    @Test
    fun `a send after disconnect reports false`() = runTest {
        val connection = KtorPttConnection()
        try {
            // disconnect() with no session must also be safe, and afterwards there is
            // definitively nowhere to write.
            connection.disconnect()
            assertFalse(connection.send(Ping))
        } finally {
            connection.shutdown()
        }
    }
}
