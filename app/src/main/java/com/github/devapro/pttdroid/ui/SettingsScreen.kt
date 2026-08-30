package com.github.devapro.pttdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.CertificatePin
import com.github.devapro.pttdroid.data.settings.ThemeMode
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme

/**
 * Relay address, identity, channel and the two hands-free toggles.
 *
 * The server address being editable at all is the point — it used to be a LAN IP compiled into
 * the socket class. Given that, the screen shows the exact URL it will dial: for a self-hosted
 * relay, "which address is this thing actually using" is the entire first-run debugging story,
 * and it was previously only discoverable from logcat.
 *
 * Save sits in a bottom bar rather than at the end of the scroll, so it is reachable with the
 * keyboard up and without hunting for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    canDrawOverlay: Boolean,
    onSave: (AppSettings) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var host by remember(settings) { mutableStateOf(settings.serverHost) }
    var port by remember(settings) { mutableStateOf(settings.serverPort.toString()) }
    var name by remember(settings) { mutableStateOf(settings.displayName) }
    var channel by remember(settings) { mutableStateOf(settings.channel.toString()) }
    var floating by remember(settings) { mutableStateOf(settings.floatingButtonEnabled) }
    var hostServer by remember(settings) { mutableStateOf(settings.hostServerEnabled) }
    var theme by remember(settings) { mutableStateOf(settings.themeMode) }
    var useTls by remember(settings) { mutableStateOf(settings.useTls) }
    var fingerprint by remember(settings) {
        mutableStateOf(CertificatePin.format(settings.certificateSha256))
    }
    var token by remember(settings) { mutableStateOf(settings.accessToken) }
    var tokenVisible by remember { mutableStateOf(false) }

    val portValue = port.toIntOrNull()
    val channelValue = channel.toIntOrNull()

    val hostError = host.isBlank()
    val portError = portValue == null || portValue !in AppSettings.PORT_RANGE
    val channelError = channelValue == null || channelValue !in AppSettings.CHANNEL_RANGE
    val nameError = name.length > AppSettings.MAX_NAME_LENGTH
    val fingerprintError = useTls && !CertificatePin.isAcceptable(fingerprint)
    val tokenError = token.length > AppSettings.MAX_TOKEN_LENGTH
    val hasError = hostError || portError || channelError || nameError ||
        fingerprintError || tokenError

    val edited = settings.copy(
        serverHost = host.trim(),
        serverPort = portValue ?: settings.serverPort,
        displayName = name.trim().ifEmpty { AppSettings.DEFAULT_NAME },
        channel = channelValue ?: settings.channel,
        floatingButtonEnabled = floating,
        hostServerEnabled = hostServer,
        themeMode = theme,
        useTls = useTls,
        certificateSha256 = CertificatePin.normalize(fingerprint),
        accessToken = token.trim(),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.imePadding(),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSave(edited) },
                        enabled = !hasError,
                        modifier = Modifier
                            .widthIn(max = FORM_MAX_WIDTH)
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(text = stringResource(R.string.settings_save))
                    }
                }
            }
        },
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
        Column(
            modifier = Modifier
                // A form is read line by line. Left to fill a tablet it becomes text fields a
                // metre wide, which is the least readable measure there is.
                .widthIn(max = FORM_MAX_WIDTH)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = stringResource(R.string.settings_server),
                caption = stringResource(R.string.settings_server_caption),
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.settings_host)) },
                    isError = hostError,
                    supportingText = {
                        if (hostError) Text(stringResource(R.string.error_host_blank))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.settings_port)) },
                    isError = portError,
                    supportingText = {
                        if (portError) Text(stringResource(R.string.error_port_invalid))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!hasError) {
                    Text(
                        text = stringResource(R.string.settings_endpoint, edited.displayUrl()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(
                title = stringResource(R.string.settings_security),
                caption = stringResource(R.string.settings_security_caption),
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_tls),
                    summary = stringResource(R.string.settings_tls_summary),
                    checked = useTls,
                    onCheckedChange = { useTls = it },
                )

                if (useTls) {
                    OutlinedTextField(
                        value = fingerprint,
                        onValueChange = { fingerprint = it },
                        label = { Text(stringResource(R.string.settings_fingerprint)) },
                        isError = fingerprintError,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (fingerprintError) {
                                        R.string.error_fingerprint_invalid
                                    } else {
                                        R.string.settings_fingerprint_summary
                                    },
                                ),
                            )
                        },
                        singleLine = false,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (useTls && hostServer) {
                    // The on-device relay speaks plaintext only, so this pair can never connect.
                    Text(
                        text = stringResource(R.string.settings_tls_host_conflict),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.settings_token)) },
                    isError = tokenError,
                    supportingText = {
                        Text(
                            stringResource(
                                if (tokenError) {
                                    R.string.error_token_too_long
                                } else {
                                    R.string.settings_token_summary
                                },
                            ),
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { tokenVisible = !tokenVisible }) {
                            Text(
                                stringResource(
                                    if (tokenVisible) R.string.settings_hide else R.string.settings_show,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(
                title = stringResource(R.string.settings_identity),
                caption = stringResource(R.string.settings_identity_caption),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_display_name)) },
                    isError = nameError,
                    supportingText = {
                        if (nameError) Text(stringResource(R.string.error_name_too_long))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.settings_channel)) },
                    isError = channelError,
                    supportingText = {
                        if (channelError) Text(stringResource(R.string.error_channel_invalid))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = stringResource(R.string.settings_appearance)) {
                ThemePicker(selected = theme, onSelect = { theme = it })
            }

            SectionCard(title = stringResource(R.string.settings_hands_free)) {
                ToggleRow(
                    title = stringResource(R.string.settings_floating),
                    summary = stringResource(R.string.settings_floating_summary),
                    checked = floating,
                    onCheckedChange = { floating = it },
                )

                // "Draw over other apps" is a special permission: it cannot be granted from a
                // runtime dialog, only from a Settings screen we send the user to.
                if (floating && !canDrawOverlay) {
                    Text(
                        text = stringResource(R.string.settings_overlay_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onRequestOverlayPermission) {
                        Text(text = stringResource(R.string.settings_grant_overlay))
                    }
                }

                ToggleRow(
                    title = stringResource(R.string.settings_host_server),
                    summary = stringResource(R.string.settings_host_server_summary),
                    checked = hostServer,
                    onCheckedChange = { hostServer = it },
                )
            }
        }
        }
    }
}

/** Past this the form stops being a column of fields and becomes a wall of them. */
private val FORM_MAX_WIDTH = 640.dp

/**
 * System / Light / Dark. Three options is exactly the case a segmented control is for — all of
 * them visible, one tap to change, no menu to open and no state hidden behind a label.
 */
@Composable
private fun ThemePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.SYSTEM to R.string.settings_theme_system,
        ThemeMode.LIGHT to R.string.settings_theme_light,
        ThemeMode.DARK to R.string.settings_theme_dark,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    caption: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // The whole row toggles, not just the switch. A 32dp switch at the far edge of a tablet is
    // a needlessly small target, and it leaves the label — the part that says what the setting
    // does — inert to both touch and a screen reader.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Null: the row above owns the click, so the switch must not add a second target.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Preview(widthDp = 380, heightDp = 900)
@Composable
private fun SettingsScreenPreview() {
    PTTdroidTheme {
        SettingsScreen(
            settings = AppSettings(),
            canDrawOverlay = false,
            onSave = {},
            onRequestOverlayPermission = {},
            onBack = {},
        )
    }
}
