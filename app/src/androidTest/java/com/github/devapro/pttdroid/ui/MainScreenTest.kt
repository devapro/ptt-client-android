package com.github.devapro.pttdroid.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.domain.ConnectionStatus
import com.github.devapro.pttdroid.domain.PttState
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the screen says, and what it dispatches.
 *
 * These are the cases a user hits when nothing is going right — no permission, no server, someone
 * else on the channel — which is exactly where the old build showed nothing at all.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val actions = mutableListOf<MainAction>()

    private val connected = PttState(status = ConnectionStatus.Connected, channel = 5, peers = 2)

    private fun show(state: ScreenState) {
        rule.setContent {
            PTTdroidTheme(darkTheme = true) {
                MainScreen(
                    state = state,
                    endpoint = "10.0.2.2:8000",
                    snackbarHostState = SnackbarHostState(),
                    onAction = { actions += it },
                )
            }
        }
    }

    private fun string(id: Int) = context.getString(id)

    @Test
    fun a_clear_channel_reports_ready_and_counts_the_radios() {
        show(ScreenState(ptt = connected, micPermissionGranted = true))

        rule.onNodeWithText(string(R.string.status_ready)).assertIsDisplayed()
        rule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.peers_online, 2, 2),
        ).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.ptt_hold_to_talk)).assertIsDisplayed()
    }

    @Test
    fun offline_shows_the_address_it_cannot_reach_rather_than_hiding_the_ui() {
        // The whole first-run debugging story for a self-hosted relay is "which address?".
        show(ScreenState(ptt = PttState(), micPermissionGranted = true))

        rule.onNodeWithText(string(R.string.status_disconnected)).assertIsDisplayed()
        rule.onNodeWithText("10.0.2.2:8000").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.ptt_offline)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.main_connect)).assertIsDisplayed()
    }

    @Test
    fun someone_else_talking_is_named_and_the_reason_is_spelled_out() {
        show(
            ScreenState(
                ptt = connected.copy(isFloorHeldByOther = true, floorHolderName = "Bob"),
                micPermissionGranted = true,
            ),
        )

        rule.onNodeWithText(string(R.string.status_receiving_from).format("Bob")).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.ptt_blocked)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.ptt_cap_busy)).assertIsDisplayed()
    }

    @Test
    fun a_missing_microphone_permission_offers_the_fix() {
        show(ScreenState(ptt = connected, micPermissionGranted = false))

        rule.onNodeWithText(string(R.string.permission_mic_required)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.permission_grant)).performClick()

        rule.runOnIdle { assertTrue(actions.contains(MainAction.InitConnection)) }
    }

    @Test
    fun stepping_the_channel_dispatches_the_new_number() {
        show(ScreenState(ptt = connected, micPermissionGranted = true))

        rule.onNodeWithContentDescription(string(R.string.cd_channel_up)).performClick()
        rule.onNodeWithContentDescription(string(R.string.cd_channel_down)).performClick()

        rule.runOnIdle {
            assertEquals(
                listOf(MainAction.SetChannel(6), MainAction.SetChannel(4)),
                actions.filterIsInstance<MainAction.SetChannel>(),
            )
        }
    }

    @Test
    fun the_channel_ends_disable_instead_of_silently_doing_nothing() {
        show(ScreenState(ptt = connected.copy(channel = 1), micPermissionGranted = true))

        rule.onNodeWithContentDescription(string(R.string.cd_channel_down)).assertIsNotEnabled()
        rule.onNodeWithContentDescription(string(R.string.cd_channel_up)).assertIsEnabled()
    }

    @Test
    fun the_channel_is_locked_while_transmitting() {
        // Switching channels mid-transmission would strand the floor on the old one.
        show(
            ScreenState(
                ptt = connected.copy(isTransmitting = true),
                micPermissionGranted = true,
            ),
        )

        rule.onNodeWithContentDescription(string(R.string.cd_channel_up)).assertIsNotEnabled()
        rule.onNodeWithContentDescription(string(R.string.cd_channel_down)).assertIsNotEnabled()
    }

    @Test
    fun an_error_can_be_dismissed() {
        show(
            ScreenState(
                ptt = PttState(lastError = "connection refused"),
                micPermissionGranted = true,
            ),
        )

        rule.onNodeWithText("connection refused").assertIsDisplayed()
        rule.onNodeWithContentDescription(string(R.string.cd_dismiss_error)).performClick()

        rule.runOnIdle { assertTrue(actions.contains(MainAction.DismissError)) }
    }

    @Test
    fun the_session_can_be_started_and_stopped_from_the_status_card() {
        show(ScreenState(ptt = connected, micPermissionGranted = true))

        rule.onNodeWithText(string(R.string.main_disconnect)).performClick()

        rule.runOnIdle { assertTrue(actions.contains(MainAction.Disconnect)) }
    }

    @Test
    fun the_gear_opens_settings() {
        show(ScreenState(ptt = connected, micPermissionGranted = true))

        rule.onNodeWithContentDescription(string(R.string.cd_settings)).performClick()

        rule.runOnIdle { assertTrue(actions.contains(MainAction.OpenSettings)) }
    }

    @Test
    fun a_pending_floor_request_is_visible_rather_than_looking_idle() {
        show(
            ScreenState(
                ptt = connected.copy(isRequestingFloor = true),
                micPermissionGranted = true,
            ),
        )

        rule.onNodeWithText(string(R.string.ptt_cap_wait)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.ptt_requesting)).assertIsDisplayed()
    }
}
