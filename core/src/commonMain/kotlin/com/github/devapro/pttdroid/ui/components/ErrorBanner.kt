package com.github.devapro.pttdroid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.shared.resources.*
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.jetbrains.compose.resources.stringResource

/**
 * The last transport or protocol error, until the user waves it away.
 *
 * Previously this was a line of small red text wedged under the button, with no way to clear it:
 * a stale "connection refused" from twenty minutes ago sat there for the rest of the session and
 * looked like a live fault.
 */
@Composable
fun ErrorBanner(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Survives the message going null so the banner does not blank out a frame before it
    // finishes collapsing.
    var lastMessage by remember { mutableStateOf(message) }
    LaunchedEffect(message) { if (message != null) lastMessage = message }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = lastMessage.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close),
                        contentDescription = stringResource(Res.string.cd_dismiss_error),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun ErrorBannerPreview() {
    PTTdroidTheme {
        ErrorBanner(message = "Connection refused: 10.0.2.2:8000", onDismiss = {})
    }
}
