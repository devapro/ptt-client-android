package com.github.devapro.pttdroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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

        rule.onNodeWithText(string(R.string.settings_theme_dark)).performClick()
        rule.onNodeWithText(string(R.string.settings_save)).performClick()

        rule.runOnIdle { assertEquals(ThemeMode.DARK, saved.first().themeMode) }
    }

    @Test
    fun the_stored_theme_is_the_one_shown_as_selected() {
        show(AppSettings(themeMode = ThemeMode.LIGHT))

        rule.onNodeWithText(string(R.string.settings_theme_light)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_save)).performClick()

        rule.runOnIdle { assertEquals(ThemeMode.LIGHT, saved.first().themeMode) }
    }
}
