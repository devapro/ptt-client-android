package com.github.devapro.pttdroid.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme

/**
 * Channel −/+ as a single pill.
 *
 * It used to be a full-width `SpaceEvenly` row, which threw − and + against opposite edges of
 * the phone: two targets a thumb cannot reach without regripping, on a screen whose whole
 * premise is one-handed use. Grouped tightly instead, and the number is width-locked so
 * stepping 9 → 10 does not shove the buttons sideways.
 *
 * Bounds are enforced here as well as in the reducer so the ends visibly disable rather than
 * silently doing nothing.
 */
@Composable
fun ChannelSelector(
    channel: Int,
    enabled: Boolean,
    onChannelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = AppSettings.CHANNEL_RANGE

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.channel_label).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            modifier = Modifier.padding(top = 6.dp).alpha(if (enabled) 1f else 0.45f),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StepButton(
                    icon = R.drawable.ic_minus,
                    description = R.string.cd_channel_down,
                    enabled = enabled && channel > range.first,
                    onClick = { onChannelChange(channel - 1) },
                )
                Text(
                    text = channel.toString().padStart(2, '0'),
                    modifier = Modifier.widthIn(min = 64.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StepButton(
                    icon = R.drawable.ic_plus,
                    description = R.string.cd_channel_up,
                    enabled = enabled && channel < range.last,
                    onClick = { onChannelChange(channel + 1) },
                )
            }
        }
    }
}

@Composable
private fun StepButton(
    @DrawableRes icon: Int,
    @StringRes description: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.size(26.dp),
        )
    }
}

@Preview
@Composable
private fun ChannelSelectorPreview() {
    PTTdroidTheme {
        ChannelSelector(channel = 3, enabled = true, onChannelChange = {})
    }
}

@Preview
@Composable
private fun ChannelSelectorLockedPreview() {
    PTTdroidTheme {
        ChannelSelector(channel = 99, enabled = false, onChannelChange = {})
    }
}
