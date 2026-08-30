package com.github.devapro.pttdroid.ui.components

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.ui.PttUiStatus
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import com.github.devapro.pttdroid.ui.theme.color

/** Near-black. Every status accent is a mid-to-light tone, so dark content wins on contrast. */
private val OnSignal = Color(0xFF08111A)

/**
 * The push-to-talk control: one disc that is simultaneously the only button on the screen and
 * the primary readout of the session.
 *
 * Three things it has to survive that a plain `Button` does not:
 *
 * - **You are not looking at it.** The moment it becomes safe to speak is not the press, it is
 *   the server's floor grant a beat later, so the confirming haptic fires on the grant.
 * - **Colour alone is not enough.** Every state also changes the word on the face and the glyph,
 *   which is what makes it usable for a colour-blind user and readable in sunlight.
 * - **Hold-to-talk is inaccessible.** TalkBack cannot express a press-and-hold, so the semantics
 *   below expose a plain toggle action alongside the touch gesture.
 *
 * [diameter] is passed in rather than fixed, because the caller is the only thing that knows how
 * much room is left after the status card — the previous hardcoded 240.dp overflowed in
 * landscape and on small screens.
 */
@Composable
fun PTTButton(
    status: PttUiStatus,
    enabled: Boolean,
    diameter: Dp,
    onPressStart: () -> Unit,
    onPressStop: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    // The gesture detector below is keyed on `Unit` and never restarts, so without these it
    // would hold the callbacks and the enabled flag from the first composition forever.
    val pressStart by rememberUpdatedState(onPressStart)
    val pressStop by rememberUpdatedState(onPressStop)
    val live = rememberUpdatedState(enabled)

    var pressed by remember { mutableStateOf(false) }
    var previousStatus by remember { mutableStateOf(status) }

    LaunchedEffect(status) {
        val was = previousStatus
        previousStatus = status
        when {
            status == PttUiStatus.TRANSMITTING && was != PttUiStatus.TRANSMITTING ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            was == PttUiStatus.TRANSMITTING && status != PttUiStatus.TRANSMITTING ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // Three appearances, not two. A disabled control is not automatically a colourless one:
    // when the *channel* is why you cannot press — someone else is talking, or the socket is
    // down — the disc is still the readout, and greying it out throws away the one signal
    // visible from across a room. It goes hollow instead, keeping the channel's colour as a
    // ring. Flat grey is reserved for the case where the fault is ours: no microphone.
    val scheme = MaterialTheme.colorScheme
    val micMissing = !enabled && status.isControlLive
    val faceColor = when {
        enabled -> status.color
        micMissing -> scheme.surfaceVariant
        else -> scheme.surface
    }
    val ringColor = if (!enabled && !micMissing) status.color else null
    val contentColor = when {
        enabled -> OnSignal
        micMissing -> scheme.onSurfaceVariant
        else -> status.color
    }
    val caption = stringResource(status.captionRes)
    val stateLabel = stringResource(status.labelRes)
    val toggleLabel = stringResource(R.string.cd_ptt_toggle)

    val depth by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "ptt-depth",
    )
    // Composed only while it is wanted, so the animation clock is not driven for a button that
    // is sitting idle in the foreground for hours. It runs while *receiving* too: an incoming
    // transmission is exactly when a still picture is least useful.
    val pulse = if (status.isOnAir) pulseProgress() else null

    Box(
        modifier = modifier
            .size(diameter)
            .scale(depth)
            // Keyed on Unit and gated from inside, deliberately. Keying on `enabled` — or
            // hanging the whole modifier off it — tears the detector down the instant state
            // changes under a held finger, and the release half of the gesture is simply lost.
            // The floor stays held, the microphone stays open, and nobody else on the channel
            // can talk again. The release therefore lives in a `finally`: whatever happens to
            // this gesture, letting go of the floor happens too.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!live.value) return@awaitEachGesture
                    down.consume()

                    pressed = true
                    pressStart()
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                        pressStop()
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = toggleLabel
                stateDescription = stateLabel
                if (enabled) onClick(label = toggleLabel) { onToggle(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val outer = size.minDimension / 2f
            val disc = outer * 0.84f

            pulse?.let { progress ->
                // Two rings a half-cycle apart, so the ripple reads as continuous rather than
                // as a single ring that keeps restarting.
                for (offset in listOf(0f, 0.5f)) {
                    val p = (progress + offset) % 1f
                    drawCircle(
                        color = status.color.copy(alpha = 0.40f * (1f - p)),
                        radius = disc + (outer - disc) * p,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }

            drawCircle(color = faceColor, radius = disc)
            ringColor?.let {
                drawCircle(color = it, radius = disc, style = Stroke(width = 5.dp.toPx()))
            }
            // A rim rather than a shadow: shadows disappear on a black background, an inset
            // highlight does not, and it is what makes the disc read as a physical key.
            drawCircle(
                color = OnSignal.copy(alpha = if (enabled) 0.18f else 0.08f),
                radius = disc * 0.88f,
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(status.glyph()),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(diameter * 0.17f),
            )
            Text(
                text = caption,
                textAlign = TextAlign.Center,
                color = contentColor,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = (diameter.value * 0.125f).sp,
                ),
            )
        }
    }
}

/** 0..1, looping. Only ever composed while the button is actually on air. */
@Composable
private fun pulseProgress(): Float {
    val transition = rememberInfiniteTransition(label = "ptt-pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
        label = "ptt-pulse-progress",
    )
    return progress
}

@DrawableRes
private fun PttUiStatus.glyph(): Int = when (this) {
    PttUiStatus.OFFLINE -> R.drawable.ic_mic_off
    PttUiStatus.RECEIVING -> R.drawable.ic_volume_up
    else -> R.drawable.ic_mic
}

@Preview(widthDp = 360, heightDp = 320)
@Composable
private fun PTTButtonReadyPreview() {
    PTTdroidTheme {
        PTTButton(
            status = PttUiStatus.READY,
            enabled = true,
            diameter = 260.dp,
            onPressStart = {},
            onPressStop = {},
            onToggle = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 320)
@Composable
private fun PTTButtonOnAirPreview() {
    PTTdroidTheme {
        PTTButton(
            status = PttUiStatus.TRANSMITTING,
            enabled = true,
            diameter = 260.dp,
            onPressStart = {},
            onPressStop = {},
            onToggle = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 320)
@Composable
private fun PTTButtonReceivingPreview() {
    PTTdroidTheme {
        PTTButton(
            status = PttUiStatus.RECEIVING,
            enabled = false,
            diameter = 260.dp,
            onPressStart = {},
            onPressStop = {},
            onToggle = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 320)
@Composable
private fun PTTButtonOfflinePreview() {
    PTTdroidTheme {
        PTTButton(
            status = PttUiStatus.OFFLINE,
            enabled = false,
            diameter = 260.dp,
            onPressStart = {},
            onPressStop = {},
            onToggle = {},
        )
    }
}
