package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The address field takes whatever the user has on the clipboard: what `ptt-server` prints when it
 * starts, what a tunnel hands out, or a bare host typed by hand. Each of those implies a different
 * port, and getting that wrong is a connection that fails with no explanation.
 */
class ServerAddressTest {

    private fun valid(raw: String): ServerAddress.Valid {
        val parsed = ServerAddress.parse(raw)
        assertEquals(ServerAddress.Valid::class, parsed::class, "expected $raw to parse")
        return parsed as ServerAddress.Valid
    }

    private fun problem(raw: String): ServerAddress.Problem =
        ServerAddress.parse(raw) as? ServerAddress.Problem
            ?: throw AssertionError("expected $raw to be rejected")

    @Test
    fun `a bare host takes the relay's own port, not a web port`() {
        val address = valid("relay.local")

        assertEquals("relay.local", address.host)
        assertEquals(AppSettings.DEFAULT_PORT, address.port)
        // Nothing was said about the protocol, so the Security switch keeps deciding.
        assertEquals(null, address.secure)
    }

    @Test
    fun `host and port are split`() {
        val address = valid("192.168.1.20:8000")

        assertEquals("192.168.1.20", address.host)
        assertEquals(8000, address.port)
        assertEquals(null, address.secure)
    }

    @Test
    fun `what the server prints at startup can be pasted whole`() {
        val address = valid("ws://192.168.1.20:8000")

        assertEquals("192.168.1.20", address.host)
        assertEquals(8000, address.port)
        assertEquals(false, address.secure)
    }

    @Test
    fun `a tunnel url brings its own scheme and port`() {
        // The whole point: ngrok hands out an https URL with no port, and 443 is the only right
        // answer. Typing this into a host box and a port box is where people got it wrong.
        val address = valid("https://something.ngrok-free.app")

        assertEquals("something.ngrok-free.app", address.host)
        assertEquals(443, address.port)
        assertEquals(true, address.secure)
    }

    @Test
    fun `an explicit port beats the scheme's default`() {
        assertEquals(8443, valid("wss://relay.example.com:8443").port)
        assertEquals(8000, valid("http://relay.example.com:8000").port)
    }

    @Test
    fun `a scheme that says nothing about encryption is not accepted`() {
        // Silently treating ftp:// as plaintext would be worse than saying it is not an address.
        assertEquals(ServerAddress.Problem.MALFORMED, problem("ftp://relay.local"))
    }

    @Test
    fun `the scheme is case insensitive`() {
        assertEquals(true, valid("WSS://relay.local").secure)
    }

    @Test
    fun `a pasted path query or fragment is dropped rather than swallowed by the host`() {
        assertEquals("relay.local", valid("wss://relay.local:8443/channel/2?name=Ann").host)
        assertEquals(8443, valid("wss://relay.local:8443/channel/2?name=Ann").port)
        assertEquals("relay.local", valid("relay.local/").host)
        assertEquals("relay.local", valid("relay.local#frag").host)
    }

    @Test
    fun `surrounding whitespace from a clipboard is ignored`() {
        assertEquals("relay.local", valid("  relay.local:8000\n").host)
    }

    @Test
    fun `an ipv6 literal keeps the brackets a url needs`() {
        val address = valid("ws://[fe80::1]:8000")

        assertEquals("[fe80::1]", address.host)
        assertEquals(8000, address.port)
    }

    @Test
    fun `an unbracketed ipv6 literal is rejected rather than read as a port`() {
        assertEquals(ServerAddress.Problem.MALFORMED, problem("fe80::1:8000"))
    }

    @Test
    fun `credentials in the address are called out, not quietly accepted`() {
        // A URL reaches proxy logs and the server's own error output; the token header does not.
        assertEquals(ServerAddress.Problem.CREDENTIALS, problem("wss://ann:hunter2@relay.local"))
    }

    @Test
    fun `an empty address is empty rather than malformed`() {
        assertEquals(ServerAddress.Problem.EMPTY, problem(""))
        assertEquals(ServerAddress.Problem.EMPTY, problem("   "))
        assertEquals(ServerAddress.Problem.EMPTY, problem("wss://"))
    }

    @Test
    fun `a port outside the legal range is reported as a port problem`() {
        assertEquals(ServerAddress.Problem.PORT, problem("relay.local:70000"))
        assertEquals(ServerAddress.Problem.PORT, problem("relay.local:0"))
        assertEquals(ServerAddress.Problem.PORT, problem("relay.local:"))
        assertEquals(ServerAddress.Problem.PORT, problem("relay.local:80a"))
    }

    @Test
    fun `a host with a space or a stray character is not an address`() {
        assertEquals(ServerAddress.Problem.MALFORMED, problem("relay local"))
        assertEquals(ServerAddress.Problem.MALFORMED, problem("relay!.local"))
        assertEquals(ServerAddress.Problem.MALFORMED, problem("-relay.local"))
    }

    @Test
    fun `a valid address can be written back as the text it normalizes to`() {
        // What the field is reset to when the Security switch takes the scheme back: the port the
        // scheme implied has to survive that, or turning encryption off silently loses 443.
        assertEquals("something.ngrok-free.app:443", valid("https://something.ngrok-free.app").hostAndPort())
        assertEquals("[fe80::1]:8000", valid("ws://[fe80::1]:8000").hostAndPort())
    }
}
