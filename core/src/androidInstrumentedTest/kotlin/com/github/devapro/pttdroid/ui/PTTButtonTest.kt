package com.github.devapro.pttdroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.devapro.pttdroid.shared.resources.*
import com.github.devapro.pttdroid.ui.components.PTTButton
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gesture, not the pixels.
 *
 * The interesting cases are all about what happens when the session state changes underneath a
 * finger that is already down — which is the normal case, since the floor grant always arrives
 * mid-press — and the one thing that must never happen is losing the release.
 */
@RunWith(AndroidJUnit4::class)
class PTTButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private val toggleLabel = runBlocking { getString(Res.string.cd_ptt_toggle) }

    private var started = 0
    private var stopped = 0
    private var toggled = 0

    private fun setButton(
        status: PttUiStatus = PttUiStatus.READY,
        enabled: () -> Boolean = { true },
        statusOf: () -> PttUiStatus = { status },
    ) {
        rule.setContent {
            PTTdroidTheme {
                PTTButton(
                    status = statusOf(),
                    enabled = enabled(),
                    diameter = 220.dp,
                    onPressStart = { started++ },
                    onPressStop = { stopped++ },
                    onToggle = { toggled++ },
                )
            }
        }
    }

    @Test
    fun holding_starts_on_touch_down_not_on_release() {
        setButton()

        rule.onNodeWithContentDescription(toggleLabel).performTouchInput { down(center) }

        // The microphone request has to leave on the way down, or the first word is lost.
        rule.runOnIdle { assertEquals(1, started) }
        rule.runOnIdle { assertEquals(0, stopped) }

        rule.onNodeWithContentDescription(toggleLabel).performTouchInput { up() }
        rule.runOnIdle { assertEquals(1, stopped) }
    }

    @Test
    fun the_release_still_fires_when_the_button_is_disabled_mid_press() {
        // Regression, known-issues #20. A disabled Compose button drops its gesture detector; if
        // the release lives inside that gesture it is simply lost, and the talk floor stays held
        // with the microphone open until the app is restarted.
        var enabled by mutableStateOf(true)
        setButton(enabled = { enabled })

        rule.onNodeWithContentDescription(toggleLabel).performTouchInput { down(center) }
        rule.runOnIdle { enabled = false }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(toggleLabel).performTouchInput { up() }

        rule.runOnIdle {
            assertEquals(1, started)
            assertEquals("the floor was never released", 1, stopped)
        }
    }

    @Test
    fun the_release_still_fires_when_the_status_changes_mid_press() {
        // The ordinary case: the grant lands while the finger is still down.
        var status by mutableStateOf(PttUiStatus.READY)
        setButton(statusOf = { status })

        rule.onNodeWithContentDescription(toggleLabel).performTouchInput { down(center) }
        rule.runOnIdle { status = PttUiStatus.TRANSMITTING }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(toggleLabel).performTouchInput { up() }

        rule.runOnIdle {
            assertEquals(1, started)
            assertEquals(1, stopped)
        }
    }

    @Test
    fun a_dead_control_ignores_touches() {
        setButton(status = PttUiStatus.RECEIVING, enabled = { false })

        rule.onNodeWithContentDescription(toggleLabel).performTouchInput { down(center) }
        rule.onNodeWithContentDescription(toggleLabel).performTouchInput { up() }

        rule.runOnIdle {
            assertEquals("must not request the floor while someone else holds it", 0, started)
            assertEquals(0, stopped)
        }
    }

    @Test
    fun the_face_says_what_the_state_is_without_relying_on_colour() {
        setButton(status = PttUiStatus.TRANSMITTING)
        rule.onNodeWithText(runBlocking { getString(Res.string.ptt_cap_on_air) }).assertIsDisplayed()
    }

    @Test
    fun talkback_gets_a_toggle_action_since_it_cannot_express_a_hold() {
        setButton()

        rule.onNodeWithContentDescription(toggleLabel)
            .performSemanticsAction(SemanticsActions.OnClick)

        rule.runOnIdle { assertEquals(1, toggled) }
    }

    @Test
    fun a_dead_control_offers_no_click_action_either() {
        // Otherwise a screen reader announces an action that silently does nothing.
        setButton(status = PttUiStatus.OFFLINE, enabled = { false })

        rule.onNodeWithContentDescription(toggleLabel).assertHasNoClickAction()
    }
}
