package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.AppSettings
import org.junit.Assert.assertEquals
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
}
