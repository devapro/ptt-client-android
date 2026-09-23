package com.github.devapro.pttdroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import com.github.devapro.pttdroid.shared.resources.*
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Where the audio comes out, and how loud — one pill, matching [ChannelSelector]'s shape.
 *
 * Both halves are here rather than in Settings because both are things you change *during* a
 * conversation, with the phone already in your hand: you put it to your ear because this one is
 * not for the room, or you turn it down because you have walked indoors. A setting you reach by
 * opening a gear icon is a setting you do not change while somebody is talking.
 *
 * Deliberately colourless. `docs/ui-design.md`'s first rule is that exactly one thing on the
 * screen is saturated and it is the state of the channel; a slider painted in `primary` would be
 * a second green element competing with the one that means "the channel is yours". The selected
 * route is shown by inverting it — dark glyph on a light key — which also survives being
 * screenshotted, colour-blind, or in direct sun, the same reason the PTT button changes its
 * word and its glyph and not only its colour.
 *
 * The slider keeps its own [live] copy of the level so the thumb tracks the finger without
 * waiting for a DataStore round trip. [onVolumeChange] fires continuously and only reaches the
 * speaker; [onVolumeCommit] fires once on release and is what gets written down. That split is
 * why keying `remember` on [volume] is safe: [volume] cannot change mid-drag, because nothing
 * mid-drag writes it.
 */
@Composable
fun AudioOutputControl(
    output: AudioOutput,
    volume: Float,
    canRoute: Boolean,
    onOutputChange: (AudioOutput) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var live by remember(volume) { mutableStateOf(AppSettings.clampVolume(volume)) }
    // Resolved outside the semantics lambda: that block is not a composable scope.
    val volumeDescription = stringResource(Res.string.cd_audio_volume)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when {
                    !canRoute -> Res.string.audio_volume
                    output == AudioOutput.EARPIECE -> Res.string.audio_out_earpiece
                    else -> Res.string.audio_out_speaker
                },
            ).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (canRoute) {
                    RouteButton(
                        icon = Res.drawable.ic_volume_up,
                        description = Res.string.cd_audio_out_speaker,
                        selected = output == AudioOutput.SPEAKER,
                        onClick = { onOutputChange(AudioOutput.SPEAKER) },
                    )
                    RouteButton(
                        icon = Res.drawable.ic_earpiece,
                        description = Res.string.cd_audio_out_earpiece,
                        selected = output == AudioOutput.EARPIECE,
                        onClick = { onOutputChange(AudioOutput.EARPIECE) },
                    )
                } else {
                    // Nothing to switch between here, so the glyph is a label, not a target.
                    Icon(
                        painter = painterResource(Res.drawable.ic_volume_up),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).size(22.dp),
                    )
                }

                Slider(
                    value = live,
                    onValueChange = {
                        live = it
                        onVolumeChange(it)
                    },
                    onValueChangeFinished = { onVolumeCommit(live) },
                    valueRange = AppSettings.VOLUME_RANGE,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        // Asymmetric: the thumb sits on the value, so at 100% it would
                        // otherwise ride the pill's rounded edge.
                        .padding(start = 12.dp, end = 18.dp)
                        .semantics { contentDescription = volumeDescription },
                )
            }
        }
    }
}

/**
 * Selected is drawn by inversion, not by hue: `onSurface` fill with `surface` content, against
 * the unselected key's `surface` fill. See the class KDoc for why this control has no colour of
 * its own to spend.
 */
@Composable
private fun RouteButton(
    icon: DrawableResource,
    description: StringResource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Preview
@Composable
private fun AudioOutputControlSpeakerPreview() {
    PTTdroidTheme {
        AudioOutputControl(
            output = AudioOutput.SPEAKER,
            volume = 0.8f,
            canRoute = true,
            onOutputChange = {},
            onVolumeChange = {},
            onVolumeCommit = {},
        )
    }
}

@Preview
@Composable
private fun AudioOutputControlDesktopPreview() {
    PTTdroidTheme {
        AudioOutputControl(
            output = AudioOutput.SPEAKER,
            volume = 0.35f,
            canRoute = false,
            onOutputChange = {},
            onVolumeChange = {},
            onVolumeCommit = {},
        )
    }
}
