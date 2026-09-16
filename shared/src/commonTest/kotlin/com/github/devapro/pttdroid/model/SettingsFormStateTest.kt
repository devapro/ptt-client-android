package com.github.devapro.pttdroid.model

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import com.github.devapro.pttdroid.data.settings.LanguageMode
import com.github.devapro.pttdroid.data.settings.ServerAddress
import com.github.devapro.pttdroid.data.settings.ServerMode
import com.github.devapro.pttdroid.data.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings form used to be reachable only from an emulator, sitting in 13 `remember` holders
 * inside `SettingsScreen`. [SettingsFormState] is where that logic lives now, so this pins the
 * exact behaviour of seeding, every [SettingsEdit] branch, and the derived validation/merge rules
 * — byte for byte what the composable used to compute in its body.
 */
class SettingsFormStateTest {

    private val pin = "FD0EFB7BD3BB639FA169910467D1C65C3302269A87C899C2F05DE933CB500689"
    private val formattedPin =
        "FD:0E:FB:7B:D3:BB:63:9F:A1:69:91:04:67:D1:C6:5C:33:02:26:9A:87:C8:99:C2:F0:5D:E9:33:CB:50:06:89"

    // A test-local literal port/host and an explicit useTls: these tests are about the form's own
    // logic, not whatever relay.properties ships as the default (see AppSettingsTest for the same
    // convention on the AppSettings side).
    private fun custom(host: String = "relay.local", port: Int = 9000) = AppSettings(
        serverMode = ServerMode.CUSTOM,
        customHost = host,
        customPort = port,
        useTls = false,
    )

    // --- seeding --------------------------------------------------------------------------

    @Test
    fun `seeding composes the address as host colon port`() {
        val form = SettingsFormState.from(custom("relay.local", 9000))

        assertEquals("relay.local:9000", form.address)
    }

    @Test
    fun `seeding formats a stored fingerprint with colons`() {
        val form = SettingsFormState.from(AppSettings(certificateSha256 = pin))

        assertEquals(formattedPin, form.fingerprint)
    }

    @Test
    fun `seeding formats an empty fingerprint as empty`() {
        val form = SettingsFormState.from(AppSettings(certificateSha256 = ""))

        assertEquals("", form.fingerprint)
    }

    @Test
    fun `seeding stringifies the channel`() {
        val form = SettingsFormState.from(AppSettings(channel = 42))

        assertEquals("42", form.channel)
    }

    @Test
    fun `seeding always starts with the token hidden`() {
        val form = SettingsFormState.from(AppSettings(accessToken = "s3cret"))

        assertFalse(form.accessTokenVisible)
    }

    @Test
    fun `seeding carries every other field across unchanged and keeps the stored settings`() {
        val settings = AppSettings(
            serverMode = ServerMode.CUSTOM,
            customHost = "relay.local",
            customPort = 9000,
            displayName = "Ann",
            broadcastStartBipEnabled = false,
            floatingButtonEnabled = true,
            hostServerEnabled = true,
            themeMode = ThemeMode.DARK,
            languageMode = LanguageMode.RUSSIAN,
            useTls = true,
            accessToken = "s3cret",
        )

        val form = SettingsFormState.from(settings)

        assertEquals(ServerMode.CUSTOM, form.serverMode)
        assertEquals("Ann", form.displayName)
        assertFalse(form.broadcastStartBipEnabled)
        assertTrue(form.floatingButtonEnabled)
        assertTrue(form.hostServerEnabled)
        assertEquals(ThemeMode.DARK, form.themeMode)
        assertEquals(LanguageMode.RUSSIAN, form.languageMode)
        assertTrue(form.useTls)
        assertEquals("s3cret", form.accessToken)
        assertEquals(settings, form.stored)
    }

    // --- apply(edit): every SettingsEdit branch --------------------------------------------

    @Test
    fun `edit Mode changes the server mode`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.Mode(ServerMode.CUSTOM))

        assertEquals(ServerMode.CUSTOM, form.serverMode)
    }

    @Test
    fun `edit Address replaces the address text`() {
        val form = SettingsFormState.from(custom())
            .apply(SettingsEdit.Address("relay.example.com:1234"))

        assertEquals("relay.example.com:1234", form.address)
    }

    @Test
    fun `edit DisplayName replaces the name`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.DisplayName("Bob"))

        assertEquals("Bob", form.displayName)
    }

    @Test
    fun `edit BroadcastStartBip flips the toggle`() {
        val form = SettingsFormState.from(AppSettings(broadcastStartBipEnabled = true))
            .apply(SettingsEdit.BroadcastStartBip(false))

        assertFalse(form.broadcastStartBipEnabled)
    }

    @Test
    fun `edit FloatingButton flips the toggle`() {
        val form = SettingsFormState.from(AppSettings(floatingButtonEnabled = false))
            .apply(SettingsEdit.FloatingButton(true))

        assertTrue(form.floatingButtonEnabled)
    }

    @Test
    fun `edit HostServer flips the toggle`() {
        val form = SettingsFormState.from(AppSettings(hostServerEnabled = false))
            .apply(SettingsEdit.HostServer(true))

        assertTrue(form.hostServerEnabled)
    }

    @Test
    fun `edit Theme changes the theme mode`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.Theme(ThemeMode.DARK))

        assertEquals(ThemeMode.DARK, form.themeMode)
    }

    @Test
    fun `edit Language changes the language mode`() {
        val form = SettingsFormState.from(AppSettings())
            .apply(SettingsEdit.Language(LanguageMode.SERBIAN))

        assertEquals(LanguageMode.SERBIAN, form.languageMode)
    }

    @Test
    fun `edit Fingerprint replaces the fingerprint text`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.Fingerprint("FD:0E"))

        assertEquals("FD:0E", form.fingerprint)
    }

    @Test
    fun `edit AccessToken replaces the token text`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.AccessToken("newtoken"))

        assertEquals("newtoken", form.accessToken)
    }

    @Test
    fun `edit AccessTokenVisible flips the visibility`() {
        val form = SettingsFormState.from(AppSettings())
            .apply(SettingsEdit.AccessTokenVisible(true))

        assertTrue(form.accessTokenVisible)

        val hiddenAgain = form.apply(SettingsEdit.AccessTokenVisible(false))
        assertFalse(hiddenAgain.accessTokenVisible)
    }

    // --- Channel: digit filter and the 2-character cap -------------------------------------

    @Test
    fun `channel edit drops non digit characters`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.Channel("a1b2c"))

        assertEquals("12", form.channel)
    }

    @Test
    fun `channel edit caps at two characters`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.Channel("12345"))

        assertEquals("12", form.channel)
    }

    @Test
    fun `channel edit with only letters becomes empty`() {
        val form = SettingsFormState.from(AppSettings(channel = 5))
            .apply(SettingsEdit.Channel("abc"))

        assertEquals("", form.channel)
    }

    // --- Tls edit: the address field gives the scheme back to the switch -------------------

    @Test
    fun `Tls edit sets useTls without touching an address that carries no scheme`() {
        val form = SettingsFormState.from(custom("relay.local", 9000)).apply(SettingsEdit.Tls(true))

        assertTrue(form.useTls)
        assertEquals("relay.local:9000", form.address)
    }

    @Test
    fun `Tls false keeps the port a pasted scheme implied`() {
        val withScheme = SettingsFormState.from(custom("relay.local", 9000))
            .apply(SettingsEdit.Address("https://something.ngrok-free.app"))

        val form = withScheme.apply(SettingsEdit.Tls(false))

        assertEquals("something.ngrok-free.app:443", form.address)
        assertFalse(form.useTls)
    }

    @Test
    fun `Tls true also takes the scheme back out of the address and keeps its port`() {
        val withScheme = SettingsFormState.from(custom("relay.local", 9000))
            .apply(SettingsEdit.Address("http://relay.example.com:8080"))

        val form = withScheme.apply(SettingsEdit.Tls(true))

        assertEquals("relay.example.com:8080", form.address)
        assertTrue(form.useTls)
    }

    // --- secure: the scheme wins over the switch, and the switch is the fallback -----------

    @Test
    fun `secure follows a pasted https scheme regardless of the tls switch`() {
        val form = SettingsFormState.from(custom("relay.local", 9000))
            .apply(SettingsEdit.Address("https://relay.example.com"))

        assertTrue(form.secure)
    }

    @Test
    fun `secure follows a pasted ws scheme regardless of the tls switch`() {
        val form = SettingsFormState.from(custom("relay.local", 9000).copy(useTls = true))
            .apply(SettingsEdit.Address("ws://relay.example.com"))

        assertFalse(form.secure)
    }

    @Test
    fun `secure falls back to useTls when the address carries no scheme`() {
        val secureForm = SettingsFormState.from(custom("relay.local", 9000).copy(useTls = true))
        assertTrue(secureForm.secure)

        val plainForm = secureForm.apply(SettingsEdit.Tls(false))
        assertFalse(plainForm.secure)
    }

    // --- addressProblem / customAddress: gated on ServerMode --------------------------------

    @Test
    fun `customAddress and addressProblem are both null under DEFAULT mode even with a broken address`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.Address("://broken"))

        assertNull(form.customAddress)
        assertNull(form.addressProblem)
    }

    @Test
    fun `customAddress is live under CUSTOM mode`() {
        val form = SettingsFormState.from(custom("relay.local", 9000))

        assertEquals("relay.local", form.customAddress?.host)
        assertEquals(9000, form.customAddress?.port)
        assertNull(form.addressProblem)
    }

    @Test
    fun `addressProblem is live under CUSTOM mode when the address cannot be used`() {
        val form = SettingsFormState.from(custom()).apply(SettingsEdit.Address(""))

        assertNull(form.customAddress)
        assertEquals(ServerAddress.Problem.EMPTY, form.addressProblem)
    }

    // --- channelError boundaries -------------------------------------------------------------

    @Test
    fun `channelError is true at zero - one below the legal range`() {
        assertTrue(SettingsFormState.from(AppSettings(channel = 0)).channelError)
    }

    @Test
    fun `channelError is false at one - the low end of the legal range`() {
        assertFalse(SettingsFormState.from(AppSettings(channel = 1)).channelError)
    }

    @Test
    fun `channelError is false at ninety nine - the high end of the legal range`() {
        assertFalse(SettingsFormState.from(AppSettings(channel = 99)).channelError)
    }

    @Test
    fun `channelError is true at one hundred - one above the legal range`() {
        assertTrue(SettingsFormState.from(AppSettings(channel = 100)).channelError)
    }

    @Test
    fun `channelError is true for a blank channel field`() {
        val form = SettingsFormState.from(AppSettings(channel = 5)).apply(SettingsEdit.Channel(""))

        assertTrue(form.channelError)
    }

    // --- displayNameError boundary at MAX_NAME_LENGTH ----------------------------------------

    @Test
    fun `displayNameError is false at exactly MAX_NAME_LENGTH`() {
        val name = "a".repeat(AppSettings.MAX_NAME_LENGTH)

        assertFalse(SettingsFormState.from(AppSettings(displayName = name)).displayNameError)
    }

    @Test
    fun `displayNameError is true one character over MAX_NAME_LENGTH`() {
        val name = "a".repeat(AppSettings.MAX_NAME_LENGTH + 1)

        assertTrue(SettingsFormState.from(AppSettings(displayName = name)).displayNameError)
    }

    // --- accessTokenError boundary at MAX_TOKEN_LENGTH ---------------------------------------

    @Test
    fun `accessTokenError is false at exactly MAX_TOKEN_LENGTH`() {
        val token = "a".repeat(AppSettings.MAX_TOKEN_LENGTH)

        assertFalse(SettingsFormState.from(AppSettings(accessToken = token)).accessTokenError)
    }

    @Test
    fun `accessTokenError is true one character over MAX_TOKEN_LENGTH`() {
        val token = "a".repeat(AppSettings.MAX_TOKEN_LENGTH + 1)

        assertTrue(SettingsFormState.from(AppSettings(accessToken = token)).accessTokenError)
    }

    // --- fingerprintError: half-typed vs empty vs colon-formatted, and only while secure ----

    @Test
    fun `fingerprintError is false for an empty fingerprint because a tunnel needs no pin`() {
        val form = SettingsFormState.from(AppSettings(useTls = true))

        assertFalse(form.fingerprintError)
    }

    @Test
    fun `fingerprintError is true for a half typed fingerprint while secure`() {
        val form = SettingsFormState.from(AppSettings(useTls = true))
            .apply(SettingsEdit.Fingerprint("FD:0E:FB"))

        assertTrue(form.fingerprintError)
    }

    @Test
    fun `fingerprintError is false for a complete colon formatted fingerprint`() {
        val form = SettingsFormState.from(AppSettings(useTls = true))
            .apply(SettingsEdit.Fingerprint(formattedPin))

        assertFalse(form.fingerprintError)
    }

    @Test
    fun `fingerprintError never applies on a plaintext connection`() {
        val form = SettingsFormState.from(AppSettings(useTls = false))
            .apply(SettingsEdit.Fingerprint("FD:0E"))

        assertFalse(form.fingerprintError)
    }

    // --- hasError aggregates every field ------------------------------------------------------

    @Test
    fun `hasError is false for an untouched freshly seeded form`() {
        assertFalse(SettingsFormState.from(AppSettings(useTls = false)).hasError)
    }

    @Test
    fun `hasError is true when a single field fails validation`() {
        val form = SettingsFormState.from(AppSettings(useTls = false))
            .apply(SettingsEdit.DisplayName("a".repeat(AppSettings.MAX_NAME_LENGTH + 1)))

        assertTrue(form.hasError)
    }

    // --- relayConflict: the on-device relay speaks plaintext only ---------------------------

    @Test
    fun `relayConflict is false when neither secure nor hosting is on`() {
        val form = SettingsFormState.from(AppSettings(useTls = false, hostServerEnabled = false))

        assertFalse(form.relayConflict)
    }

    @Test
    fun `relayConflict is false when only one of secure or hosting is on`() {
        val secureOnly = SettingsFormState.from(AppSettings(useTls = false, hostServerEnabled = false))
            .apply(SettingsEdit.Tls(true))
        assertFalse(secureOnly.relayConflict)

        val hostOnly = SettingsFormState.from(AppSettings(useTls = false, hostServerEnabled = false))
            .apply(SettingsEdit.HostServer(true))
        assertFalse(hostOnly.relayConflict)
    }

    @Test
    fun `relayConflict is true when both secure and hosting are on`() {
        val form = SettingsFormState.from(AppSettings(useTls = false, hostServerEnabled = false))
            .apply(SettingsEdit.Tls(true))
            .apply(SettingsEdit.HostServer(true))

        assertTrue(form.relayConflict)
    }

    // --- toSettings(): the final merge back into AppSettings --------------------------------

    @Test
    fun `toSettings preserves audioOutput playbackVolume and the floating button position`() {
        val stored = AppSettings(
            audioOutput = AudioOutput.EARPIECE,
            playbackVolume = 0.4f,
            floatingButtonX = 12,
            floatingButtonY = 34,
        )

        val settings = SettingsFormState.from(stored).toSettings()

        assertEquals(AudioOutput.EARPIECE, settings.audioOutput)
        assertEquals(0.4f, settings.playbackVolume)
        assertEquals(12, settings.floatingButtonX)
        assertEquals(34, settings.floatingButtonY)
    }

    @Test
    fun `toSettings trims the display name and falls back to the default when it is blank`() {
        val trimmed = SettingsFormState.from(AppSettings()).apply(SettingsEdit.DisplayName("  Ann  "))
        assertEquals("Ann", trimmed.toSettings().displayName)

        val blank = SettingsFormState.from(AppSettings()).apply(SettingsEdit.DisplayName("   "))
        assertEquals(AppSettings.DEFAULT_NAME, blank.toSettings().displayName)
    }

    @Test
    fun `toSettings trims the access token`() {
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.AccessToken("  s3cret  "))

        assertEquals("s3cret", form.toSettings().accessToken)
    }

    @Test
    fun `toSettings normalizes a pasted colon formatted fingerprint`() {
        val form = SettingsFormState.from(AppSettings(useTls = true))
            .apply(SettingsEdit.Fingerprint(formattedPin))

        assertEquals(pin, form.toSettings().certificateSha256)
    }

    @Test
    fun `toSettings keeps the stored customHost and customPort when the mode is DEFAULT`() {
        val stored = AppSettings(
            serverMode = ServerMode.DEFAULT,
            customHost = "relay.local",
            customPort = 9000,
        )

        val settings = SettingsFormState.from(stored).toSettings()

        assertEquals("relay.local", settings.customHost)
        assertEquals(9000, settings.customPort)
    }

    @Test
    fun `toSettings takes the typed host and port under CUSTOM mode`() {
        val form = SettingsFormState.from(custom("relay.local", 9000))
            .apply(SettingsEdit.Address("relay.example.com:1234"))

        val settings = form.toSettings()

        assertEquals("relay.example.com", settings.customHost)
        assertEquals(1234, settings.customPort)
    }

    @Test
    fun `toSettings carries the edited channel and server mode`() {
        val form = SettingsFormState.from(AppSettings())
            .apply(SettingsEdit.Channel("7"))
            .apply(SettingsEdit.Mode(ServerMode.CUSTOM))

        val settings = form.toSettings()

        assertEquals(7, settings.channel)
        assertEquals(ServerMode.CUSTOM, settings.serverMode)
    }

    @Test
    fun `toSettings uses secure rather than the raw switch when a scheme overrides it`() {
        val form = SettingsFormState.from(custom("relay.local", 9000))
            .apply(SettingsEdit.Address("https://relay.example.com"))

        assertTrue(form.toSettings().useTls)
    }
}
