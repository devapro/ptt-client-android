package com.github.devapro.pttdroid.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import com.github.devapro.pttdroid.data.settings.LanguageMode
import com.github.devapro.pttdroid.data.settings.applyLanguagePreference
import com.github.devapro.pttdroid.data.settings.ServerAddress
import com.github.devapro.pttdroid.data.settings.ServerMode
import com.github.devapro.pttdroid.data.settings.ThemeMode
import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsFormState
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.features.api.settings.SettingsEdit
import com.github.devapro.pttdroid.shared.resources.*
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Relay address, identity, channel, broadcast-start bip and the two hands-free toggles.
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
 *
 * The screen is a pure function of [SettingsState] — every field renders `state.form.x` and every
 * edit is emitted as `onAction(SettingsAction.EditSettings(SettingsEdit.X(...)))` rather than
 * held locally. Local state here (the previous `remember(settings)` holders) is what let an
 * unrelated settings write while this screen was open silently wipe in-progress edits;
 * [SettingsFormState] is the single source of truth now, seeded once when Settings is opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    // Defaults to this platform's own capability so Android/desktop callers (and existing tests)
    // need no change; iOS's App() (in :ui-shell) also relies on this default. See
    // domain/PlatformCapabilities.kt — InternalPttServer (the thing this setting turns on) has no
    // iOS actual at all.
    canHostRelay: Boolean = com.github.devapro.pttdroid.domain.canHostRelay,
    canShowFloatingButton: Boolean = com.github.devapro.pttdroid.domain.canShowFloatingButton,
    canUseHardwareButtons: Boolean = com.github.devapro.pttdroid.domain.canUseHardwareButtons,
) {
    // The form exists exactly while the Settings screen is up (see SettingsState.form); the
    // host only renders this branch once OpenSettingsReducer has seeded it, and renders
    // nothing in its place otherwise — never a half-rendered form.
    val form = state.form ?: return

    // R9: the language chosen in the still-unsaved form relabels this screen immediately,
    // without Save and without touching persistence.
    //
    // `stringResource()` resolves against the process default locale, but every lookup here
    // caches its last resolution (and the whole screen is a pure function of the state, so a
    // language edit invalidates nothing on its own) — the label refresh therefore needs both
    // levers, applied during composition rather than from a `LaunchedEffect`, exactly like the
    // host roots (`desktopApp/Main.kt`, `:ui-shell`'s `App.kt`) apply a *persisted* change:
    //   1. `applyLanguagePreference` swaps the locale the string tables resolve against;
    //   2. `key(displayLanguage)` discards and re-composes the screen subtree, so every
    //      `stringResource` call below re-runs instead of reusing its cached string.
    // A discarded subtree is safe here precisely because nothing lives in it: the values are all
    // in `form`. On leaving the screen — closed unsaved, or saved — the `onDispose` restores the
    // language this screen was entered with, so a never-saved choice can never leak into the
    // rest of the app; a saved change then lands through the persisted path (Android
    // `recreate()`, desktop/iOS `key(language)` at the root) as before. On iOS
    // `applyLanguagePreference` only takes effect at next launch (no runtime locale lever —
    // see its iosMain actual), so the labels there keep waiting for the restart.
    val displayLanguage = form.languageMode
    val entryLanguage = remember { displayLanguage }
    remember(displayLanguage) { applyLanguagePreference(displayLanguage) }
    DisposableEffect(Unit) {
        onDispose { applyLanguagePreference(entryLanguage) }
    }

    key(displayLanguage) {
        SettingsScreenContent(
            form = form,
            canDrawOverlay = state.canDrawOverlay,
            canHostRelay = canHostRelay,
            canShowFloatingButton = canShowFloatingButton,
            canUseHardwareButtons = canUseHardwareButtons,
            onAction = onAction,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    form: SettingsFormState,
    canDrawOverlay: Boolean,
    canHostRelay: Boolean,
    canShowFloatingButton: Boolean,
    canUseHardwareButtons: Boolean,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier,
) {
    val endpointUrl = form.toSettings().displayUrl()

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
                    IconButton(onClick = { onAction(SettingsAction.Close) }) {
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
                // Scaffold pads its *body* for window insets but never its bars — a bar is
                // expected to consume its own — and this app draws edge-to-edge (targetSdk 36
                // enforces it). Without this the Save button renders underneath the navigation
                // bar, or a tablet's taskbar, where it cannot be read or tapped.
                //
                // Bottom-only `safeDrawing` rather than the `imePadding()` this used to have:
                // safeDrawing already includes the IME, so the button still lifts above an open
                // keyboard, and it adds the navigation bar, the gesture handle and a bottom
                // display cutout, all of which `imePadding()` on its own ignored. On desktop
                // these insets are zero, so nothing changes there.
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                ),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onAction(SettingsAction.SaveSettings) },
                        enabled = !form.hasError,
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
            ServerSection(
                serverMode = form.serverMode,
                address = form.address,
                addressProblem = form.addressProblem,
                hasError = form.hasError,
                endpointUrl = endpointUrl,
                onAction = onAction,
            )

            SecuritySection(
                secure = form.secure,
                fingerprint = form.fingerprint,
                fingerprintError = form.fingerprintError,
                relayConflict = form.relayConflict,
                accessToken = form.accessToken,
                accessTokenVisible = form.accessTokenVisible,
                accessTokenError = form.accessTokenError,
                onAction = onAction,
            )

            IdentitySection(
                displayName = form.displayName,
                displayNameError = form.displayNameError,
                channel = form.channel,
                channelError = form.channelError,
                onAction = onAction,
            )

            BroadcastSection(
                broadcastStartBipEnabled = form.broadcastStartBipEnabled,
                onAction = onAction,
            )

            AppearanceSection(
                themeMode = form.themeMode,
                onAction = onAction,
            )

            LanguageSection(
                languageMode = form.languageMode,
                onAction = onAction,
            )

            if (canShowFloatingButton || canHostRelay || canUseHardwareButtons) {
                HandsFreeSection(
                    floatingButtonEnabled = form.floatingButtonEnabled,
                    canDrawOverlay = canDrawOverlay,
                    hostServerEnabled = form.hostServerEnabled,
                    canHostRelay = canHostRelay,
                    canShowFloatingButton = canShowFloatingButton,
                    hardwareButtonsEnabled = form.hardwareButtonsEnabled,
                    canUseHardwareButtons = canUseHardwareButtons,
                    onAction = onAction,
                )
            }
        }
        }
    }
}

/** Relay address: Default/Custom choice, the address box under Custom, and the resolved URL. */
@Composable
private fun ServerSection(
    serverMode: ServerMode,
    address: String,
    addressProblem: ServerAddress.Problem?,
    hasError: Boolean,
    endpointUrl: String,
    onAction: (SettingsAction) -> Unit,
) {
    SectionCard(
        title = stringResource(Res.string.settings_server),
        caption = stringResource(Res.string.settings_server_caption),
    ) {
        SegmentedChoice(
            options = SERVER_MODES,
            selected = serverMode,
            onSelect = { onAction(SettingsAction.EditSettings(SettingsEdit.Mode(it))) },
        )

        if (serverMode.isCustom) {
            OutlinedTextField(
                value = address,
                onValueChange = { onAction(SettingsAction.EditSettings(SettingsEdit.Address(it))) },
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
                text = stringResource(Res.string.settings_endpoint, endpointUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** TLS switch, the fingerprint box it gates, the relay/TLS conflict warning, and the access token. */
@Composable
private fun SecuritySection(
    secure: Boolean,
    fingerprint: String,
    fingerprintError: Boolean,
    relayConflict: Boolean,
    accessToken: String,
    accessTokenVisible: Boolean,
    accessTokenError: Boolean,
    onAction: (SettingsAction) -> Unit,
) {
    SectionCard(
        title = stringResource(Res.string.settings_security),
        caption = stringResource(Res.string.settings_security_caption),
    ) {
        ToggleRow(
            title = stringResource(Res.string.settings_tls),
            summary = stringResource(Res.string.settings_tls_summary),
            checked = secure,
            // The switch takes the scheme back from the address field: SettingsFormState.apply's
            // SettingsEdit.Tls branch resolves the address to host:port first, which keeps the
            // port the scheme implied — dropping a `https://` would otherwise silently take 443
            // with it.
            onCheckedChange = { onAction(SettingsAction.EditSettings(SettingsEdit.Tls(it))) },
        )

        if (secure) {
            OutlinedTextField(
                value = fingerprint,
                onValueChange = { onAction(SettingsAction.EditSettings(SettingsEdit.Fingerprint(it))) },
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

        if (relayConflict) {
            // The on-device relay speaks plaintext only, so this pair can never connect.
            Text(
                text = stringResource(Res.string.settings_tls_host_conflict),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = accessToken,
            onValueChange = { onAction(SettingsAction.EditSettings(SettingsEdit.AccessToken(it))) },
            label = { Text(stringResource(Res.string.settings_token)) },
            isError = accessTokenError,
            supportingText = {
                Text(
                    stringResource(
                        if (accessTokenError) {
                            Res.string.error_token_too_long
                        } else {
                            Res.string.settings_token_summary
                        },
                    ),
                )
            },
            singleLine = true,
            visualTransformation = if (accessTokenVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(
                    onClick = {
                        onAction(
                            SettingsAction.EditSettings(SettingsEdit.AccessTokenVisible(!accessTokenVisible)),
                        )
                    },
                ) {
                    Text(
                        stringResource(
                            if (accessTokenVisible) Res.string.settings_hide else Res.string.settings_show,
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
}

/** Display name and channel number. */
@Composable
private fun IdentitySection(
    displayName: String,
    displayNameError: Boolean,
    channel: String,
    channelError: Boolean,
    onAction: (SettingsAction) -> Unit,
) {
    SectionCard(
        title = stringResource(Res.string.settings_identity),
        caption = stringResource(Res.string.settings_identity_caption),
    ) {
        OutlinedTextField(
            value = displayName,
            onValueChange = { onAction(SettingsAction.EditSettings(SettingsEdit.DisplayName(it))) },
            label = { Text(stringResource(Res.string.settings_display_name)) },
            isError = displayNameError,
            supportingText = {
                if (displayNameError) Text(stringResource(Res.string.error_name_too_long))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = channel,
            onValueChange = { onAction(SettingsAction.EditSettings(SettingsEdit.Channel(it))) },
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
}

/** The start-of-transmission bip toggle. */
@Composable
private fun BroadcastSection(
    broadcastStartBipEnabled: Boolean,
    onAction: (SettingsAction) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.settings_broadcast)) {
        ToggleRow(
            title = stringResource(Res.string.settings_broadcast_start_bip),
            summary = stringResource(Res.string.settings_broadcast_start_bip_summary),
            checked = broadcastStartBipEnabled,
            onCheckedChange = {
                onAction(SettingsAction.EditSettings(SettingsEdit.BroadcastStartBip(it)))
            },
        )
    }
}

/** Theme choice. */
@Composable
private fun AppearanceSection(
    themeMode: ThemeMode,
    onAction: (SettingsAction) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.settings_appearance)) {
        SegmentedChoice(
            options = THEME_MODES,
            selected = themeMode,
            onSelect = { onAction(SettingsAction.EditSettings(SettingsEdit.Theme(it))) },
        )
    }
}

/** Language choice. */
@Composable
private fun LanguageSection(
    languageMode: LanguageMode,
    onAction: (SettingsAction) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.settings_language)) {
        SegmentedChoice(
            options = LANGUAGE_MODES,
            selected = languageMode,
            onSelect = { onAction(SettingsAction.EditSettings(SettingsEdit.Language(it))) },
        )
    }
}

/** Floating bubble (plus the overlay-permission escape hatch it needs) and the on-device relay. */
@Composable
private fun HandsFreeSection(
    floatingButtonEnabled: Boolean,
    canDrawOverlay: Boolean,
    hostServerEnabled: Boolean,
    canHostRelay: Boolean,
    canShowFloatingButton: Boolean,
    hardwareButtonsEnabled: Boolean,
    canUseHardwareButtons: Boolean,
    onAction: (SettingsAction) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.settings_hands_free)) {
        // Hidden where there is no overlay window to draw it in (desktop, iOS).
        if (canShowFloatingButton) {
            ToggleRow(
                title = stringResource(Res.string.settings_floating),
                summary = stringResource(Res.string.settings_floating_summary),
                checked = floatingButtonEnabled,
                onCheckedChange = { onAction(SettingsAction.EditSettings(SettingsEdit.FloatingButton(it))) },
            )
        }

        if (canUseHardwareButtons) {
            ToggleRow(
                title = stringResource(Res.string.settings_hardware_buttons),
                summary = stringResource(Res.string.settings_hardware_buttons_summary),
                checked = hardwareButtonsEnabled,
                onCheckedChange = { onAction(SettingsAction.EditSettings(SettingsEdit.HardwareButtons(it))) },
            )
        }

        // "Draw over other apps" is a special permission: it cannot be granted from a
        // runtime dialog, only from a Settings screen we send the user to.
        if (canShowFloatingButton && floatingButtonEnabled && !canDrawOverlay) {
            Text(
                text = stringResource(Res.string.settings_overlay_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { onAction(SettingsAction.RequestOverlayPermission) }) {
                Text(text = stringResource(Res.string.settings_grant_overlay))
            }
        }

        // Omitted entirely (not just disabled) on a platform that cannot host the relay
        // at all — InternalPttServer has no actual there. `hostServerEnabled` still
        // round-trips through `toSettings()` above so a stored value from another platform (or
        // an earlier install) is never silently dropped by opening this screen.
        if (canHostRelay) {
            ToggleRow(
                title = stringResource(Res.string.settings_host_server),
                summary = stringResource(Res.string.settings_host_server_summary),
                checked = hostServerEnabled,
                onCheckedChange = { onAction(SettingsAction.EditSettings(SettingsEdit.HostServer(it))) },
            )
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

private val LANGUAGE_MODES = listOf(
    LanguageMode.SYSTEM to Res.string.settings_language_system,
    LanguageMode.ENGLISH to Res.string.settings_language_en,
    LanguageMode.RUSSIAN to Res.string.settings_language_ru,
    LanguageMode.SERBIAN to Res.string.settings_language_sr,
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
            state = SettingsState(
                form = SettingsFormState.from(AppSettings()),
                canDrawOverlay = false,
            ),
            onAction = {},
        )
    }
}
