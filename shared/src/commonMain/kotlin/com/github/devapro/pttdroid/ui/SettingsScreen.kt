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
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.CertificatePin
import com.github.devapro.pttdroid.data.settings.ServerAddress
import com.github.devapro.pttdroid.data.settings.ServerMode
import com.github.devapro.pttdroid.data.settings.ThemeMode
import com.github.devapro.pttdroid.shared.resources.*
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Relay address, identity, channel and the two hands-free toggles.
 *
 * The server address being editable at all is the point — it used to be a LAN IP compiled into
 * the socket class. It is a choice rather than a permanent pair of fields, though: most people
 * never have a reason to touch it, so Default folds it away and only Custom shows the box.
 *
 * Either way the screen shows the exact URL it will dial. For a self-hosted relay, "which address
 * is this thing actually using" is the entire first-run debugging story, and it was previously
 * only discoverable from logcat — which is also what makes it safe for the address field to infer
 * a port from a pasted scheme, since the inference is spelled out before it can be saved.
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
    // Defaults to this platform's own capability so Android/desktop callers (and existing tests)
    // need no change; iOS's App() (ui/App.kt) also relies on this default. See
    // domain/PlatformCapabilities.kt — InternalPttServer (the thing this setting turns on) has no
    // iOS actual at all.
    canHostRelay: Boolean = com.github.devapro.pttdroid.domain.canHostRelay,
) {
    var serverMode by remember(settings) { mutableStateOf(settings.serverMode) }
    var address by remember(settings) {
        mutableStateOf("${settings.customHost}:${settings.customPort}")
    }
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

    val channelValue = channel.toIntOrNull()

    // Parsed in both modes so that what is in the box survives a save made under Default, but only
    // shown and enforced under Custom, where it is the thing being dialled.
    val parsedAddress = ServerAddress.parse(address)
    val typedAddress = parsedAddress as? ServerAddress.Valid
    val customAddress = typedAddress.takeIf { serverMode.isCustom }
    val addressProblem = (parsedAddress as? ServerAddress.Problem).takeIf { serverMode.isCustom }

    // A scheme spelled out in the address decides encryption; with none, the switch below owns it.
    val secure = customAddress?.secure ?: useTls

    val channelError = channelValue == null || channelValue !in AppSettings.CHANNEL_RANGE
    val nameError = name.length > AppSettings.MAX_NAME_LENGTH
    val fingerprintError = secure && !CertificatePin.isAcceptable(fingerprint)
    val tokenError = token.length > AppSettings.MAX_TOKEN_LENGTH
    val hasError = addressProblem != null || channelError || nameError ||
        fingerprintError || tokenError

    val edited = settings.copy(
        serverMode = serverMode,
        customHost = typedAddress?.host ?: settings.customHost,
        customPort = typedAddress?.port ?: settings.customPort,
        displayName = name.trim().ifEmpty { AppSettings.DEFAULT_NAME },
        channel = channelValue ?: settings.channel,
        floatingButtonEnabled = floating,
        hostServerEnabled = hostServer,
        themeMode = theme,
        useTls = secure,
        certificateSha256 = CertificatePin.normalize(fingerprint),
        accessToken = token.trim(),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = stringResource(Res.string.cd_back),
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
                        Text(text = stringResource(Res.string.settings_save))
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
                title = stringResource(Res.string.settings_server),
                caption = stringResource(Res.string.settings_server_caption),
            ) {
                SegmentedChoice(
                    options = SERVER_MODES,
                    selected = serverMode,
                    onSelect = { serverMode = it },
                )

                if (serverMode.isCustom) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(Res.string.settings_server_url)) },
                        isError = addressProblem != null,
                        supportingText = {
                            Text(stringResource(addressProblem.message()))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.settings_server_default_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!hasError) {
                    Text(
                        text = stringResource(Res.string.settings_endpoint, edited.displayUrl()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(
                title = stringResource(Res.string.settings_security),
                caption = stringResource(Res.string.settings_security_caption),
            ) {
                ToggleRow(
                    title = stringResource(Res.string.settings_tls),
                    summary = stringResource(Res.string.settings_tls_summary),
                    checked = secure,
                    onCheckedChange = { wanted ->
                        // The switch takes the scheme back from the address field. Resolving that
                        // address to host:port first keeps the port the scheme implied — dropping
                        // a `https://` would otherwise silently take 443 with it.
                        customAddress?.takeIf { it.secure != null }
                            ?.let { address = it.hostAndPort() }
                        useTls = wanted
                    },
                )

                if (secure) {
                    OutlinedTextField(
                        value = fingerprint,
                        onValueChange = { fingerprint = it },
                        label = { Text(stringResource(Res.string.settings_fingerprint)) },
                        isError = fingerprintError,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (fingerprintError) {
                                        Res.string.error_fingerprint_invalid
                                    } else {
                                        Res.string.settings_fingerprint_summary
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

                if (secure && hostServer) {
                    // The on-device relay speaks plaintext only, so this pair can never connect.
                    Text(
                        text = stringResource(Res.string.settings_tls_host_conflict),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(Res.string.settings_token)) },
                    isError = tokenError,
                    supportingText = {
                        Text(
                            stringResource(
                                if (tokenError) {
                                    Res.string.error_token_too_long
                                } else {
                                    Res.string.settings_token_summary
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
                                    if (tokenVisible) Res.string.settings_hide else Res.string.settings_show,
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
                title = stringResource(Res.string.settings_identity),
                caption = stringResource(Res.string.settings_identity_caption),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.settings_display_name)) },
                    isError = nameError,
                    supportingText = {
                        if (nameError) Text(stringResource(Res.string.error_name_too_long))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(Res.string.settings_channel)) },
                    isError = channelError,
                    supportingText = {
                        if (channelError) Text(stringResource(Res.string.error_channel_invalid))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = stringResource(Res.string.settings_appearance)) {
                SegmentedChoice(
                    options = THEME_MODES,
                    selected = theme,
                    onSelect = { theme = it },
                )
            }

            SectionCard(title = stringResource(Res.string.settings_hands_free)) {
                ToggleRow(
                    title = stringResource(Res.string.settings_floating),
                    summary = stringResource(Res.string.settings_floating_summary),
                    checked = floating,
                    onCheckedChange = { floating = it },
                )

                // "Draw over other apps" is a special permission: it cannot be granted from a
                // runtime dialog, only from a Settings screen we send the user to.
                if (floating && !canDrawOverlay) {
                    Text(
                        text = stringResource(Res.string.settings_overlay_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onRequestOverlayPermission) {
                        Text(text = stringResource(Res.string.settings_grant_overlay))
                    }
                }

                // Omitted entirely (not just disabled) on a platform that cannot host the relay
                // at all — InternalPttServer has no actual there. `hostServerEnabled` still
                // round-trips through `edited` above so a stored value from another platform (or
                // an earlier install) is never silently dropped by opening this screen.
                if (canHostRelay) {
                    ToggleRow(
                        title = stringResource(Res.string.settings_host_server),
                        summary = stringResource(Res.string.settings_host_server_summary),
                        checked = hostServer,
                        onCheckedChange = { hostServer = it },
                    )
                }
            }
        }
        }
    }
}

/** Past this the form stops being a column of fields and becomes a wall of them. */
private val FORM_MAX_WIDTH = 640.dp

private val SERVER_MODES = listOf(
    ServerMode.DEFAULT to Res.string.settings_server_default,
    ServerMode.CUSTOM to Res.string.settings_server_custom,
)

private val THEME_MODES = listOf(
    ThemeMode.SYSTEM to Res.string.settings_theme_system,
    ThemeMode.LIGHT to Res.string.settings_theme_light,
    ThemeMode.DARK to Res.string.settings_theme_dark,
)

/**
 * A short, closed set of choices — every option visible, one tap to change, nothing hidden behind
 * a label the way a dropdown hides it. Two or three options is exactly what this control is for.
 */
@Composable
private fun <T> SegmentedChoice(
    options: List<Pair<T, StringResource>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

/** One message per way the address can be unusable; the format hint when it is fine. */
private fun ServerAddress.Problem?.message(): StringResource = when (this) {
    null -> Res.string.settings_server_url_summary
    ServerAddress.Problem.EMPTY -> Res.string.error_host_blank
    ServerAddress.Problem.CREDENTIALS -> Res.string.error_host_credentials
    ServerAddress.Problem.PORT -> Res.string.error_port_invalid
    ServerAddress.Problem.MALFORMED -> Res.string.error_host_invalid
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
