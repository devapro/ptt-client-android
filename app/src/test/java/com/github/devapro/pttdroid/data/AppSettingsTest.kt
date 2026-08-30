package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `channel is clamped into range`() {
        // The old +/- selector let the channel reach 0 and go negative.
        assertEquals(1, AppSettings.clampChannel(0))
        assertEquals(1, AppSettings.clampChannel(-5))
        assertEquals(99, AppSettings.clampChannel(100))
        assertEquals(42, AppSettings.clampChannel(42))
    }

    @Test
    fun `websocket url carries channel version and encoded name`() {
        val url = AppSettings(
            serverHost = "10.0.2.2",
            serverPort = 8000,
            channel = 7,
            displayName = "Alice",
        ).webSocketUrl()

        assertEquals("ws://10.0.2.2:8000/channel/7?name=Alice&v=1", url)
    }

    @Test
    fun `display name with spaces and symbols is url encoded`() {
        val url = AppSettings(displayName = "Bob & Co").webSocketUrl()
        assertTrue("name should be encoded, got $url", url.contains("name=Bob+%26+Co"))
    }

    @Test
    fun `port range covers the whole legal space and nothing else`() {
        assertEquals(1, AppSettings.PORT_RANGE.first)
        assertEquals(65_535, AppSettings.PORT_RANGE.last)
    }

    @Test
    fun `a name longer than the limit is truncated before it reaches the wire`() {
        // The server rejects over-long names; the client must not be the one that gets refused.
        val url = AppSettings(displayName = "x".repeat(80)).webSocketUrl()
        val name = url.substringAfter("name=").substringBefore("&")
        assertEquals(AppSettings.MAX_NAME_LENGTH, name.length)
    }

    @Test
    fun `a unicode name survives encoding`() {
        val url = AppSettings(displayName = "Ann–Bö").webSocketUrl()
        assertTrue("expected percent-encoding, got $url", url.contains("name=Ann%E2%80%93B%C3%B6"))
    }

    @Test
    fun `an empty name still produces a well formed url`() {
        val url = AppSettings(displayName = "").webSocketUrl()
        assertTrue("got $url", url.endsWith("?name=&v=1"))
    }

    @Test
    fun `the url always carries the protocol version`() {
        // The server refuses a connection without it; forgetting it is a silent lockout.
        assertTrue(AppSettings().webSocketUrl().contains("v=1"))
    }

    @Test
    fun `an ipv4 host and a hostname are both left intact`() {
        assertTrue(AppSettings(serverHost = "192.168.1.20").webSocketUrl().startsWith("ws://192.168.1.20:"))
        assertTrue(AppSettings(serverHost = "relay.local").webSocketUrl().startsWith("ws://relay.local:"))
    }

    @Test
    fun `default host is the emulator loopback to the host machine`() {
        // localhost inside an emulator is the emulator itself, not the dev machine.
        assertEquals("10.0.2.2", AppSettings().serverHost)
    }

    // --- transport security -----------------------------------------------------------------

    @Test
    fun `the scheme follows the encryption toggle`() {
        val plain = AppSettings(serverHost = "relay.example.com", serverPort = 8000, channel = 3)

        assertEquals("ws://relay.example.com:8000/channel/3", plain.displayUrl())
        assertEquals(
            "wss://relay.example.com:8000/channel/3",
            plain.copy(useTls = true).displayUrl(),
        )
        assertTrue(plain.copy(useTls = true).webSocketUrl().startsWith("wss://"))
    }

    @Test
    fun `the displayed url carries no credentials`() {
        val settings = AppSettings(useTls = true, accessToken = "s3cret", displayName = "Ann")

        assertFalse(settings.displayUrl().contains("s3cret"))
        assertFalse(settings.displayUrl().contains("Ann"))
    }

    @Test
    fun `the endpoint carries the pin only when encryption is on`() {
        val pin = "FD0EFB7BD3BB639FA169910467D1C65C3302269A87C899C2F05DE933CB500689"
        val settings = AppSettings(certificateSha256 = pin)

        // A pin left over from an encrypted relay must not be applied to a ws:// connection,
        // where it would silently do nothing and imply protection that is not there.
        assertEquals("", settings.endpoint().pinnedSha256)
        assertEquals(pin, settings.copy(useTls = true).endpoint().pinnedSha256)
    }

    @Test
    fun `the endpoint normalizes a pasted fingerprint`() {
        val settings = AppSettings(
            useTls = true,
            certificateSha256 = "fd:0e:fb:7b:d3:bb:63:9f:a1:69:91:04:67:d1:c6:5c:" +
                "33:02:26:9a:87:c8:99:c2:f0:5d:e9:33:cb:50:06:89",
        )

        assertEquals(
            "FD0EFB7BD3BB639FA169910467D1C65C3302269A87C899C2F05DE933CB500689",
            settings.endpoint().pinnedSha256,
        )
    }

    @Test
    fun `an unusable fingerprint becomes no pin rather than a pin that matches nothing`() {
        val settings = AppSettings(useTls = true, certificateSha256 = "FD:0E")

        assertEquals("", settings.endpoint().pinnedSha256)
    }

    @Test
    fun `the endpoint trims the access token`() {
        assertEquals("s3cret", AppSettings(accessToken = "  s3cret  ").endpoint().accessToken)
        assertEquals("", AppSettings().endpoint().accessToken)
    }

    @Test
    fun `the trust profile changes with the pin and the scheme, not with the channel`() {
        val pin = "FD0EFB7BD3BB639FA169910467D1C65C3302269A87C899C2F05DE933CB500689"
        val secure = AppSettings(useTls = true, certificateSha256 = pin)

        assertEquals(
            secure.endpoint().trustProfile,
            secure.copy(channel = 9, displayName = "Someone else").endpoint().trustProfile,
        )
        assertNotEquals(secure.endpoint().trustProfile, secure.copy(useTls = false).endpoint().trustProfile)
        assertNotEquals(
            secure.endpoint().trustProfile,
            secure.copy(certificateSha256 = "").endpoint().trustProfile,
        )
    }

    @Test
    fun `only a wss url counts as secure`() {
        assertTrue(AppSettings(useTls = true).endpoint().isSecure)
        assertFalse(AppSettings().endpoint().isSecure)
    }
}
