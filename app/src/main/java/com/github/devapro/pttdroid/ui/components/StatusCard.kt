package com.github.devapro.pttdroid.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.ui.PttUiStatus
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import com.github.devapro.pttdroid.ui.theme.color

/**
 * The readout: what the channel is doing, and the one control that owns the session.
 *
 * Its second line changes job with the state, because what you need to know changes with it.
 * Connected, the useful fact is how many radios are listening; offline, it is which address the
 * app is failing to reach — that is the whole debugging story for a self-hosted relay, and it
 * used to require opening Settings to see.
 *
 * A connect/disconnect control lives here because until now there was none anywhere: the session
 * started as a side effect of granting the microphone permission and could not be stopped from
 * the app at all.
 */
@Composable
fun StatusCard(
    status: PttUiStatus,
    peers: Int,
    holderName: String?,
    endpoint: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when {
        status == PttUiStatus.RECEIVING && !holderName.isNullOrBlank() ->
            stringResource(R.string.status_receiving_from, holderName)

        else -> stringResource(status.labelRes)
    }

    val subtitle = when (status) {
        PttUiStatus.OFFLINE, PttUiStatus.CONNECTING -> endpoint
        else -> pluralStringResource(R.plurals.peers_online, peers, peers)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusDot(status)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (status == PttUiStatus.OFFLINE) {
                TextButton(onClick = onConnect) {
                    Text(stringResource(R.string.main_connect))
                }
            } else {
                TextButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.main_disconnect))
                }
            }
        }
    }
}

/**
 * Colour is the fast channel and the title text is the reliable one; the dot exists so the state
 * registers before the word is read. It breathes only while something is genuinely pending, which
 * is the difference between "still trying" and "given up".
 */
@Composable
private fun StatusDot(status: PttUiStatus) {
    val pending = status == PttUiStatus.CONNECTING || status == PttUiStatus.REQUESTING
    val alpha = if (pending) breathingAlpha() else 1f

    Canvas(modifier = Modifier.size(16.dp)) {
        val radius = size.minDimension / 2f
        drawCircle(color = status.color.copy(alpha = alpha * 0.22f), radius = radius)
        drawCircle(color = status.color.copy(alpha = alpha), radius = radius * 0.55f)
        drawCircle(
            color = status.color.copy(alpha = alpha * 0.7f),
            radius = radius * 0.85f,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

@Composable
private fun breathingAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "status-breath")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status-breath-alpha",
    )
    return alpha
}

@Preview(widthDp = 360)
@Composable
private fun StatusCardReadyPreview() {
    PTTdroidTheme {
        StatusCard(
            status = PttUiStatus.READY,
            peers = 3,
            holderName = null,
            endpoint = "10.0.2.2:8000",
            onConnect = {},
            onDisconnect = {},
        )
    }
}

@Preview(widthDp = 360)
@Composable
private fun StatusCardOfflinePreview() {
    PTTdroidTheme {
        StatusCard(
            status = PttUiStatus.OFFLINE,
            peers = 0,
            holderName = null,
            endpoint = "10.0.2.2:8000",
            onConnect = {},
            onDisconnect = {},
        )
    }
}
