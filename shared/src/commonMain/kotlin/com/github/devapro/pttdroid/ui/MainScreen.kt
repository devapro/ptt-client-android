package com.github.devapro.pttdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.domain.ConnectionStatus
import com.github.devapro.pttdroid.domain.PttState
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.shared.resources.*
import com.github.devapro.pttdroid.ui.components.ChannelSelector
import com.github.devapro.pttdroid.ui.components.ErrorBanner
import com.github.devapro.pttdroid.ui.components.PTTButton
import com.github.devapro.pttdroid.ui.components.StatusCard
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.jetbrains.compose.resources.stringResource

/**
 * The radio.
 *
 * Laid out around one claim: the button is the screen. Everything above it is a readout that
 * has to be legible in a glance and then stop competing for attention, and the button itself
 * sits in the bottom half where a thumb actually lands — the previous layout stacked everything
 * from the top and left the reachable third of the phone empty.
 *
 * In landscape the readout moves beside the button instead of above it, because the vertical
 * stack squeezed a 240.dp disc into whatever was left, which was not enough.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: ScreenState,
    endpoint: String,
    snackbarHostState: SnackbarHostState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ptt = state.ptt
    val status = PttUiStatus.of(ptt)
    val canPress = status.isControlLive && state.micPermissionGranted

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { onAction(MainAction.OpenSettings) }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_settings),
                            contentDescription = stringResource(Res.string.cd_settings),
                        )
                    }
                },
            )
        },
    ) { insets ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp),
        ) {
            // Captured here: inside the Row/Column below, `maxWidth` is no longer in scope.
            val availableWidth = maxWidth
            val availableHeight = maxHeight

            if (availableWidth > availableHeight) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Header(status, ptt, endpoint, onAction)
                        Spacer(modifier = Modifier.height(20.dp))
                        ChannelSelector(
                            channel = ptt.channel,
                            enabled = !ptt.isTransmitting,
                            onChannelChange = { onAction(MainAction.SetChannel(it)) },
                        )
                    }
                    TalkControl(
                        status = status,
                        enabled = canPress,
                        diameter = discDiameter(availableWidth * 0.40f, availableHeight * 0.78f),
                        micPermissionGranted = state.micPermissionGranted,
                        isTransmitting = ptt.isTransmitting,
                        onAction = onAction,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = READOUT_MAX_WIDTH)
                        .align(Alignment.TopCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Header(status, ptt, endpoint, onAction)

                    // Everything the thumb has to reach is grouped below this gap; everything
                    // above it is a readout. Pinning only the button to the bottom left the
                    // channel stepper stranded at the top of a 6" phone, out of reach of the
                    // hand that is holding it.
                    Spacer(modifier = Modifier.weight(1f))

                    ChannelSelector(
                        channel = ptt.channel,
                        // Switching channels mid-transmission would strand the floor.
                        enabled = !ptt.isTransmitting,
                        onChannelChange = { onAction(MainAction.SetChannel(it)) },
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    TalkControl(
                        status = status,
                        enabled = canPress,
                        diameter = discDiameter(availableWidth * 0.86f, availableHeight * 0.46f),
                        micPermissionGranted = state.micPermissionGranted,
                        isTransmitting = ptt.isTransmitting,
                        onAction = onAction,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/** The readout: what the channel is doing, and anything that has gone wrong. */
@Composable
private fun Header(
    status: PttUiStatus,
    ptt: PttState,
    endpoint: String,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Capped, not stretched: on a tablet a full-width status card is a metre of empty surface
    // with two words in the corner.
    Column(modifier = modifier.widthIn(max = READOUT_MAX_WIDTH)) {
    ErrorBanner(
        message = ptt.lastError,
        onDismiss = { onAction(MainAction.DismissError) },
        modifier = Modifier.padding(bottom = 12.dp),
    )

    StatusCard(
        status = status,
        peers = ptt.peers,
        holderName = ptt.floorHolderName,
        endpoint = endpoint,
        onConnect = { onAction(MainAction.InitConnection) },
        onDisconnect = { onAction(MainAction.Disconnect) },
    )
    }
}

/** The button, plus the one line that says why it will not do anything. */
@Composable
private fun TalkControl(
    status: PttUiStatus,
    enabled: Boolean,
    diameter: Dp,
    micPermissionGranted: Boolean,
    isTransmitting: Boolean,
    onAction: (MainAction) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PTTButton(
            status = status,
            enabled = enabled,
            diameter = diameter,
            onPressStart = { onAction(MainAction.Speak) },
            onPressStop = { onAction(MainAction.StopSpeak) },
            onToggle = {
                onAction(if (isTransmitting) MainAction.StopSpeak else MainAction.Speak)
            },
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Say why the control is unusable rather than hiding the interface, which is what the
        // original build did whenever the socket was down.
        Text(
            text = stringResource(
                when {
                    !micPermissionGranted -> Res.string.permission_mic_required
                    status == PttUiStatus.OFFLINE -> Res.string.ptt_offline
                    status == PttUiStatus.CONNECTING -> Res.string.status_connecting
                    status == PttUiStatus.RECEIVING -> Res.string.ptt_blocked
                    status == PttUiStatus.REQUESTING -> Res.string.ptt_requesting
                    status == PttUiStatus.TRANSMITTING -> Res.string.ptt_release_to_stop
                    else -> Res.string.ptt_hold_to_talk
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // The dead-button case that actually has a fix: offer the fix.
        if (!micPermissionGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onAction(MainAction.InitConnection) },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(Res.string.permission_grant))
            }
        }
    }
}

/**
 * Fit the disc to whatever is actually left over. Clamped at both ends: below ~150.dp it stops
 * being a hold target you can hit without looking, above ~320.dp it stops looking deliberate.
 */
private fun discDiameter(widthBudget: Dp, heightBudget: Dp): Dp =
    minOf(widthBudget, heightBudget).coerceIn(150.dp, 340.dp)

/**
 * Neither the readout nor the controls get wider than this. Past it the status card becomes a
 * long empty bar and the channel stepper's two buttons drift apart, which is the layout this
 * redesign replaced.
 */
private val READOUT_MAX_WIDTH = 520.dp

@Preview(name = "Ready", widthDp = 380, heightDp = 800)
@Composable
private fun MainScreenReadyPreview() {
    PTTdroidTheme {
        MainScreen(
            state = ScreenState(
                ptt = PttState(status = ConnectionStatus.Connected, channel = 4, peers = 3),
                micPermissionGranted = true,
            ),
            endpoint = "10.0.2.2:8000",
            snackbarHostState = remember { SnackbarHostState() },
            onAction = {},
        )
    }
}

@Preview(name = "On air", widthDp = 380, heightDp = 800)
@Composable
private fun MainScreenOnAirPreview() {
    PTTdroidTheme {
        MainScreen(
            state = ScreenState(
                ptt = PttState(
                    status = ConnectionStatus.Connected,
                    channel = 4,
                    peers = 3,
                    isTransmitting = true,
                ),
                micPermissionGranted = true,
            ),
            endpoint = "10.0.2.2:8000",
            snackbarHostState = remember { SnackbarHostState() },
            onAction = {},
        )
    }
}

@Preview(name = "Offline with error", widthDp = 380, heightDp = 800)
@Composable
private fun MainScreenOfflinePreview() {
    PTTdroidTheme {
        MainScreen(
            state = ScreenState(
                ptt = PttState(lastError = "Connection refused"),
                micPermissionGranted = true,
            ),
            endpoint = AppSettings().let { "${it.serverHost}:${it.serverPort}" },
            snackbarHostState = remember { SnackbarHostState() },
            onAction = {},
        )
    }
}
