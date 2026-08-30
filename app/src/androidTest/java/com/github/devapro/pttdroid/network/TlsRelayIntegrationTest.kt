package com.github.devapro.pttdroid.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.Welcome
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pinned TLS path against a real relay, on a real device.
 *
 * This is the one part of the transport that unit tests on the JVM cannot settle. Android's
 * TLS stack is Conscrypt, not the JDK's, and OkHttp treats a hand-written trust manager
 * differently there — a pin that verifies correctly under `testDebugUnitTest` can still fail
 * to connect on a handset. So it is checked where it actually runs.
 *
 * Skipped unless pointed at a relay, because it needs one:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.relayHost=10.0.2.2 \
 *   -Pandroid.testInstrumentationRunnerArguments.relayPort=8443 \
 *   -Pandroid.testInstrumentationRunnerArguments.relayFingerprint=<64 hex chars> \
 *   -Pandroid.testInstrumentationRunnerArguments.relayToken=<token>
 * ```
 */
@RunWith(AndroidJUnit4::class)
class TlsRelayIntegrationTest {

    private val arguments = InstrumentationRegistry.getArguments()
    private val host: String? = arguments.getString("relayHost")
    private val port: String = arguments.getString("relayPort") ?: "8443"
    private val fingerprint: String = arguments.getString("relayFingerprint").orEmpty()
    private val token: String = arguments.getString("relayToken").orEmpty()

    private fun endpoint(
        pin: String = fingerprint,
        accessToken: String = token,
        channel: Int = 1,
    ) = PttEndpoint(
        url = "wss://$host:$port/channel/$channel?name=Integration&v=1",
        pinnedSha256 = pin,
        accessToken = accessToken,
    )

    /** Runs one connection attempt and returns the events it produced. */
    private fun attempt(endpoint: PttEndpoint, timeoutMs: Long = 15_000): ConnectionEvent? =
        runBlocking {
            val connection = KtorPttConnection()
            val result = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val events = async {
                        connection.events.first {
                            it is ConnectionEvent.Control || it is ConnectionEvent.Disconnected
                        }
                    }
                    val dialling = launch { connection.connect(endpoint) }
                    events.await().also { dialling.cancel() }
                }
            }
            connection.disconnect()
            connection.shutdown()
            result
        }

    @Test
    fun a_pinned_self_signed_relay_is_reachable() {
        assumeTrue("relayHost not set", host != null)
        assumeTrue("relayFingerprint not set", fingerprint.isNotEmpty())

        val event = attempt(endpoint())

        assertNotNull("no event — the relay never answered", event)
        assertTrue(
            "expected a welcome, got $event",
            (event as? ConnectionEvent.Control)?.message is Welcome,
        )
    }

    @Test
    fun the_wrong_fingerprint_is_refused_with_a_message_that_says_why() {
        assumeTrue("relayHost not set", host != null)
        assumeTrue("relayFingerprint not set", fingerprint.isNotEmpty())

        // Same length, one nibble different.
        val wrong = fingerprint.dropLast(1) + if (fingerprint.last() == 'A') '1' else 'A'

        val event = attempt(endpoint(pin = wrong))

        val disconnect = event as? ConnectionEvent.Disconnected
        assertNotNull("expected a refusal, got $event", disconnect)
        assertTrue(
            "the banner has to say what is wrong: ${disconnect!!.reason}",
            disconnect.reason.orEmpty().contains("fingerprint", ignoreCase = true),
        )
    }

    @Test
    fun a_relay_that_wants_a_token_refuses_a_client_without_one() {
        assumeTrue("relayHost not set", host != null)
        assumeTrue("relayToken not set", token.isNotEmpty())

        val event = attempt(endpoint(accessToken = ""))

        val message = (event as? ConnectionEvent.Control)?.message
        assertTrue("expected an error, got $event", message is ProtocolError)
        assertEquals(ErrorCodes.UNAUTHORIZED, (message as ProtocolError).code)
    }
}
