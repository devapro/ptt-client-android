package com.github.devapro.pttdroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.ServerMode
import com.github.devapro.pttdroid.data.settings.ThemeMode
import com.github.devapro.pttdroid.shared.resources.*
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The form's job is to make a broken relay address impossible to save, and to show what the
 * address it *will* dial actually looks like.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val saved = mutableListOf<AppSettings>()

    private fun show(settings: AppSettings = AppSettings()) {
        rule.setContent {
            PTTdroidTheme(darkTheme = true) {
                SettingsScreen(
                    settings = settings,
                    canDrawOverlay = true,
                    onSave = { saved += it },
                    onRequestOverlayPermission = {},
                    onBack = {},
                )
            }
        }
    }

    private fun string(resource: StringResource) = runBlocking { getString(resource) }

    private fun custom(host: String, port: Int = AppSettings.DEFAULT_PORT) = AppSettings(
        serverMode = ServerMode.CUSTOM,
        customHost = host,
        customPort = port,
    )

    /** Replaces the address field's contents, the way pasting over a selection would. */
    private fun typeAddress(text: String) {
        rule.onNodeWithText(string(Res.string.settings_server_url)).performScrollTo()
            .performTextClearance()
        rule.onNodeWithText(string(Res.string.settings_server_url)).performTextInput(text)
    }

    @Test
    fun the_exact_url_it_will_dial_is_shown() {
        // Computed from the same AppSettings.displayUrl() the screen calls, rather than a
        // hardcoded literal: relay.properties' default (what Default mode resolves to) is a
        // per-fork build setting, not a constant this test should assume a value for.
        val settings = AppSettings(channel = 3)
        show(settings)

        rule.onNodeWithText(
            string(Res.string.settings_endpoint).format(settings.displayUrl()),
        ).assertIsDisplayed()
    }

    @Test
    fun the_default_relay_is_a_choice_rather_than_a_field_to_fill_in() {
        show()

        rule.onNodeWithText(string(Res.string.settings_server_url)).assertDoesNotExist()
        rule.onNodeWithText(string(Res.string.settings_server_default_summary)).assertIsDisplayed()
    }

    @Test
    fun the_address_field_appears_only_once_custom_is_chosen() {
        show()

        rule.onNodeWithText(string(Res.string.settings_server_custom)).performClick()

        rule.onNodeWithText(string(Res.string.settings_server_url)).assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_server_default_summary)).assertDoesNotExist()
    }

    @Test
    fun a_stored_custom_address_comes_back_as_host_and_port() {
        show(custom("relay.local", 9000))

        rule.onNodeWithText("relay.local:9000").assertIsDisplayed()
    }

    @Test
    fun a_blank_address_cannot_be_saved() {
        show(custom("relay.local"))

        rule.onNodeWithText(string(Res.string.settings_server_url)).performTextClearance()

        rule.onNodeWithText(string(Res.string.error_host_blank)).assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_save)).assertIsNotEnabled()
    }

    @Test
    fun a_port_outside_the_legal_range_cannot_be_saved() {
        show(custom("relay.local"))

        typeAddress("relay.local:70000")

        rule.onNodeWithText(string(Res.string.error_port_invalid)).assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_save)).assertIsNotEnabled()
    }

    @Test
    fun a_valid_form_saves_what_was_typed() {
        show(custom("relay.local"))

        typeAddress("relay.example.com:9000")
        rule.onNodeWithText(string(Res.string.settings_save)).assertIsEnabled().performClick()

        rule.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals("relay.example.com", saved.first().serverHost)
            assertEquals(9000, saved.first().serverPort)
        }
    }

    @Test
    fun switching_to_default_keeps_the_custom_address_for_later() {
        show(custom("relay.local", 9000))

        rule.onNodeWithText(string(Res.string.settings_server_default)).performClick()
        rule.onNodeWithText(string(Res.string.settings_save)).performClick()

        rule.runOnIdle {
            val settings = saved.first()
            assertEquals(AppSettings.DEFAULT_HOST, settings.serverHost)
            assertEquals("relay.local", settings.customHost)
            assertEquals(9000, settings.customPort)
        }
    }

    @Test
    fun a_pasted_tunnel_url_brings_its_port_and_turns_encryption_on() {
        show(custom("relay.local"))

        typeAddress("https://something.ngrok-free.app")

        rule.onNodeWithText(
            string(Res.string.settings_endpoint)
                .format("wss://something.ngrok-free.app:443/channel/1"),
        ).assertIsDisplayed()

        rule.onNodeWithText(string(Res.string.settings_save)).performClick()

        rule.runOnIdle {
            val settings = saved.first()
            assertEquals("something.ngrok-free.app", settings.serverHost)
            assertEquals(443, settings.serverPort)
            assertEquals(true, settings.useTls)
        }
    }

    @Test
    fun turning_encryption_off_after_a_pasted_url_keeps_the_port_it_implied() {
        show(custom("relay.local"))

        typeAddress("https://something.ngrok-free.app")
        rule.onNodeWithText(string(Res.string.settings_tls)).performScrollTo().performClick()

        rule.onNodeWithText("something.ngrok-free.app:443").assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_save)).performClick()

        rule.runOnIdle {
            val settings = saved.first()
            assertEquals(443, settings.serverPort)
            assertEquals(false, settings.useTls)
        }
    }

    @Test
    fun credentials_in_the_address_are_refused_rather_than_sent_to_every_proxy_on_the_way() {
        show(custom("relay.local"))

        typeAddress("wss://ann:hunter2@relay.local")

        rule.onNodeWithText(string(Res.string.error_host_credentials)).assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_save)).assertIsNotEnabled()
    }

    @Test
    fun the_theme_can_be_forced_independently_of_the_system() {
        show()

        rule.onNodeWithText(string(Res.string.settings_theme_dark)).performScrollTo().performClick()
        rule.onNodeWithText(string(Res.string.settings_save)).performClick()

        rule.runOnIdle { assertEquals(ThemeMode.DARK, saved.first().themeMode) }
    }

    @Test
    fun the_stored_theme_is_the_one_shown_as_selected() {
        show(AppSettings(themeMode = ThemeMode.LIGHT))

        rule.onNodeWithText(string(Res.string.settings_theme_light)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_save)).performClick()

        rule.runOnIdle { assertEquals(ThemeMode.LIGHT, saved.first().themeMode) }
    }

    @Test
    fun languageSelector_isDisplayed() {
        show()

        rule.onNodeWithText(string(Res.string.settings_language)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_language_system)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_language_en)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_language_ru)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_language_sr)).performScrollTo().assertIsDisplayed()
    }

    // --- security ---------------------------------------------------------------------------

    private val fingerprint =
        "FD:0E:FB:7B:D3:BB:63:9F:A1:69:91:04:67:D1:C6:5C:" +
            "33:02:26:9A:87:C8:99:C2:F0:5D:E9:33:CB:50:06:89"

    @Test
    fun turning_on_encryption_changes_the_url_it_will_dial() {
        // useTls = false explicitly: this test's whole premise is the switch being off before
        // the click, which custom()'s default only guarantees independently of whatever
        // relay.properties' DEFAULT_TLS happens to be.
        show(custom("relay.local", 8443).copy(channel = 2, useTls = false))

        rule.onNodeWithText(string(Res.string.settings_tls)).performScrollTo().performClick()

        rule.onNodeWithText(
            string(Res.string.settings_endpoint).format("wss://relay.local:8443/channel/2"),
        ).assertIsDisplayed()
    }

    @Test
    fun the_fingerprint_field_only_appears_once_encryption_is_on() {
        // Same reasoning as turning_on_encryption_changes_the_url_it_will_dial: start from an
        // explicit "off" rather than whatever relay.properties' DEFAULT_TLS resolves to.
        show(AppSettings(useTls = false))

        rule.onNodeWithText(string(Res.string.settings_fingerprint)).assertDoesNotExist()

        rule.onNodeWithText(string(Res.string.settings_tls)).performScrollTo().performClick()

        rule.onNodeWithText(string(Res.string.settings_fingerprint)).assertIsDisplayed()
    }

    @Test
    fun a_half_typed_fingerprint_cannot_be_saved() {
        show(AppSettings(useTls = true))

        rule.onNodeWithText(string(Res.string.settings_fingerprint)).performScrollTo()
            .performTextInput("FD:0E:FB")

        rule.onNodeWithText(string(Res.string.error_fingerprint_invalid)).assertIsDisplayed()
        rule.onNodeWithText(string(Res.string.settings_save)).assertIsNotEnabled()
    }

    @Test
    fun a_pasted_fingerprint_is_stored_without_its_colons() {
        show(AppSettings(useTls = true))

        rule.onNodeWithText(string(Res.string.settings_fingerprint)).performScrollTo()
            .performTextInput(fingerprint)
        rule.onNodeWithText(string(Res.string.settings_save)).assertIsEnabled().performClick()

        rule.runOnIdle {
            assertEquals(
                "FD0EFB7BD3BB639FA169910467D1C65C3302269A87C899C2F05DE933CB500689",
                saved.first().certificateSha256,
            )
        }
    }

    @Test
    fun an_empty_fingerprint_is_allowed_because_a_tunnel_needs_no_pin() {
        show(AppSettings(useTls = true))

        rule.onNodeWithText(string(Res.string.settings_save)).assertIsEnabled().performClick()

        rule.runOnIdle {
            assertEquals(true, saved.first().useTls)
            assertEquals("", saved.first().certificateSha256)
        }
    }

    @Test
    fun the_access_token_is_masked_until_asked_for() {
        // Asserted on the rendered text, not on the node's value: a masked field still reports
        // its raw contents to the accessibility tree, so matching on the token itself would
        // pass whether or not anything was actually hidden.
        val masked = "\u2022".repeat("s3cret-token".length)
        show(AppSettings(accessToken = "s3cret-token"))

        rule.onNodeWithText(masked).assertIsDisplayed()

        rule.onNodeWithText(string(Res.string.settings_show)).performScrollTo().performClick()

        rule.onNodeWithText(masked).assertDoesNotExist()
        rule.onNodeWithText(string(Res.string.settings_hide)).assertIsDisplayed()
    }

    @Test
    fun the_access_token_is_saved_trimmed() {
        show()

        rule.onNodeWithText(string(Res.string.settings_token)).performScrollTo()
            .performTextInput("  s3cret  ")
        rule.onNodeWithText(string(Res.string.settings_save)).performClick()

        rule.runOnIdle { assertEquals("s3cret", saved.first().accessToken) }
    }

    @Test
    fun hosting_a_relay_while_asking_for_encryption_is_called_out() {
        // The on-device relay is plaintext only, so this pair can never connect.
        show(AppSettings(useTls = true, hostServerEnabled = true))

        rule.onNodeWithText(string(Res.string.settings_tls_host_conflict))
            .performScrollTo().assertIsDisplayed()
    }
}
