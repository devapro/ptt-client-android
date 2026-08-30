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
import androidx.test.platform.app.InstrumentationRegistry
import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.ThemeMode
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
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

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
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

    private fun string(id: Int) = context.getString(id)

    @Test
    fun the_exact_url_it_will_dial_is_shown() {
        show(AppSettings(serverHost = "10.0.2.2", serverPort = 8000, channel = 3))

        rule.onNodeWithText(
            string(R.string.settings_endpoint).format("ws://10.0.2.2:8000/channel/3"),
        ).assertIsDisplayed()
    }

    @Test
    fun a_blank_host_cannot_be_saved() {
        show()

        rule.onNodeWithText("10.0.2.2").performTextClearance()

        rule.onNodeWithText(string(R.string.error_host_blank)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_save)).assertIsNotEnabled()
    }

    @Test
    fun a_port_outside_the_legal_range_cannot_be_saved() {
        show()

        rule.onNodeWithText("8000").performTextClearance()
        rule.onNodeWithText(string(R.string.settings_port)).performTextInput("70000")

        rule.onNodeWithText(string(R.string.error_port_invalid)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_save)).assertIsNotEnabled()
    }

    @Test
    fun a_valid_form_saves_what_was_typed() {
        show()

        rule.onNodeWithText("10.0.2.2").performTextClearance()
        rule.onNodeWithText(string(R.string.settings_host)).performTextInput("relay.local")
        rule.onNodeWithText(string(R.string.settings_save)).assertIsEnabled().performClick()

        rule.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals("relay.local", saved.first().serverHost)
        }
    }

    @Test
    fun the_theme_can_be_forced_independently_of_the_system() {
        show()

        rule.onNodeWithText(string(R.string.settings_theme_dark)).performScrollTo().performClick()
        rule.onNodeWithText(string(R.string.settings_save)).performClick()

        rule.runOnIdle { assertEquals(ThemeMode.DARK, saved.first().themeMode) }
    }

    @Test
    fun the_stored_theme_is_the_one_shown_as_selected() {
        show(AppSettings(themeMode = ThemeMode.LIGHT))

        rule.onNodeWithText(string(R.string.settings_theme_light)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_save)).performClick()

        rule.runOnIdle { assertEquals(ThemeMode.LIGHT, saved.first().themeMode) }
    }

    // --- security ---------------------------------------------------------------------------

    private val fingerprint =
        "FD:0E:FB:7B:D3:BB:63:9F:A1:69:91:04:67:D1:C6:5C:" +
            "33:02:26:9A:87:C8:99:C2:F0:5D:E9:33:CB:50:06:89"

    @Test
    fun turning_on_encryption_changes_the_url_it_will_dial() {
        show(AppSettings(serverHost = "relay.local", serverPort = 8443, channel = 2))

        rule.onNodeWithText(string(R.string.settings_tls)).performScrollTo().performClick()

        rule.onNodeWithText(
            string(R.string.settings_endpoint).format("wss://relay.local:8443/channel/2"),
        ).assertIsDisplayed()
    }

    @Test
    fun the_fingerprint_field_only_appears_once_encryption_is_on() {
        show()

        rule.onNodeWithText(string(R.string.settings_fingerprint)).assertDoesNotExist()

        rule.onNodeWithText(string(R.string.settings_tls)).performScrollTo().performClick()

        rule.onNodeWithText(string(R.string.settings_fingerprint)).assertIsDisplayed()
    }

    @Test
    fun a_half_typed_fingerprint_cannot_be_saved() {
        show(AppSettings(useTls = true))

        rule.onNodeWithText(string(R.string.settings_fingerprint)).performScrollTo()
            .performTextInput("FD:0E:FB")

        rule.onNodeWithText(string(R.string.error_fingerprint_invalid)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_save)).assertIsNotEnabled()
    }

    @Test
    fun a_pasted_fingerprint_is_stored_without_its_colons() {
        show(AppSettings(useTls = true))

        rule.onNodeWithText(string(R.string.settings_fingerprint)).performScrollTo()
            .performTextInput(fingerprint)
        rule.onNodeWithText(string(R.string.settings_save)).assertIsEnabled().performClick()

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

        rule.onNodeWithText(string(R.string.settings_save)).assertIsEnabled().performClick()

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

        rule.onNodeWithText(string(R.string.settings_show)).performScrollTo().performClick()

        rule.onNodeWithText(masked).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.settings_hide)).assertIsDisplayed()
    }

    @Test
    fun the_access_token_is_saved_trimmed() {
        show()

        rule.onNodeWithText(string(R.string.settings_token)).performScrollTo()
            .performTextInput("  s3cret  ")
        rule.onNodeWithText(string(R.string.settings_save)).performClick()

        rule.runOnIdle { assertEquals("s3cret", saved.first().accessToken) }
    }

    @Test
    fun hosting_a_relay_while_asking_for_encryption_is_called_out() {
        // The on-device relay is plaintext only, so this pair can never connect.
        show(AppSettings(useTls = true, hostServerEnabled = true))

        rule.onNodeWithText(string(R.string.settings_tls_host_conflict))
            .performScrollTo().assertIsDisplayed()
    }
}
