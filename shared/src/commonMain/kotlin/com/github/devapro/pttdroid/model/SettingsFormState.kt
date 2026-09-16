package com.github.devapro.pttdroid.model

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.CertificatePin
import com.github.devapro.pttdroid.data.settings.LanguageMode
import com.github.devapro.pttdroid.data.settings.ServerAddress
import com.github.devapro.pttdroid.data.settings.ServerMode
import com.github.devapro.pttdroid.data.settings.ThemeMode

/**
 * The settings form, in progress and not yet saved.
 *
 * This used to be thirteen `remember { mutableStateOf }` holders inside `SettingsScreen`, keyed
 * `remember(settings)` on the repository flow — which meant any unrelated settings write while
 * Settings was open (a playback-volume save, an audio-output change from the main screen) re-ran
 * that key and silently wiped whatever the user had typed. Living in [ScreenState] instead, seeded
 * once by `OpenSettingsReducer` and mutated only by `EditSettingsReducer`, fixes that: nothing but
 * an explicit [SettingsEdit] can change it.
 *
 * [stored] is the persisted [AppSettings] this form was seeded from. It is what lets [toSettings]
 * carry forward every field the form does not edit — [AppSettings.audioOutput],
 * [AppSettings.playbackVolume], [AppSettings.floatingButtonX]/[AppSettings.floatingButtonY], and
 * [AppSettings.hostServerEnabled] on a platform that hides that row — instead of dropping them.
 */
data class SettingsFormState(
    val stored: AppSettings,
    val serverMode: ServerMode,
    val address: String,
    val displayName: String,
    val channel: String,
    val broadcastStartBipEnabled: Boolean,
    val floatingButtonEnabled: Boolean,
    val hostServerEnabled: Boolean,
    val themeMode: ThemeMode,
    val languageMode: LanguageMode,
    val useTls: Boolean,
    val fingerprint: String,
    val accessToken: String,
    val accessTokenVisible: Boolean = false,
) {
    private val parsedAddress: ServerAddress get() = ServerAddress.parse(address)
    private val typedAddress: ServerAddress.Valid? get() = parsedAddress as? ServerAddress.Valid
    private val channelValue: Int? get() = channel.toIntOrNull()

    /** Parsed in both modes so the box's contents survive a save made under Default. */
    val customAddress: ServerAddress.Valid? get() = typedAddress.takeIf { serverMode.isCustom }
    val addressProblem: ServerAddress.Problem?
        get() = (parsedAddress as? ServerAddress.Problem).takeIf { serverMode.isCustom }

    /** A scheme spelled out in the address decides encryption; with none, the TLS switch owns it. */
    val secure: Boolean get() = customAddress?.secure ?: useTls

    val channelError: Boolean get() = channelValue == null || channelValue !in AppSettings.CHANNEL_RANGE
    val displayNameError: Boolean get() = displayName.length > AppSettings.MAX_NAME_LENGTH
    val fingerprintError: Boolean get() = secure && !CertificatePin.isAcceptable(fingerprint)
    val accessTokenError: Boolean get() = accessToken.length > AppSettings.MAX_TOKEN_LENGTH
    val hasError: Boolean
        get() = addressProblem != null || channelError || displayNameError ||
            fingerprintError || accessTokenError

    /** The on-device relay speaks plaintext only, so this pair can never connect. */
    val relayConflict: Boolean get() = secure && hostServerEnabled

    /** The [AppSettings] this form would produce if saved right now. */
    fun toSettings(): AppSettings = stored.copy(
        serverMode = serverMode,
        customHost = typedAddress?.host ?: stored.customHost,
        customPort = typedAddress?.port ?: stored.customPort,
        displayName = displayName.trim().ifEmpty { AppSettings.DEFAULT_NAME },
        channel = channelValue ?: stored.channel,
        broadcastStartBipEnabled = broadcastStartBipEnabled,
        floatingButtonEnabled = floatingButtonEnabled,
        hostServerEnabled = hostServerEnabled,
        themeMode = themeMode,
        languageMode = languageMode,
        useTls = secure,
        certificateSha256 = CertificatePin.normalize(fingerprint),
        accessToken = accessToken.trim(),
    )

    /** Applies one field change. The single point [EditSettingsReducer] delegates to. */
    fun apply(edit: SettingsEdit): SettingsFormState = when (edit) {
        is SettingsEdit.Mode -> copy(serverMode = edit.value)
        is SettingsEdit.Address -> copy(address = edit.value)
        is SettingsEdit.DisplayName -> copy(displayName = edit.value)
        // Digits only, capped at two: the channel range never needs a third digit and letters
        // used to be accepted all the way to the "invalid channel" error text.
        is SettingsEdit.Channel -> copy(channel = edit.value.filter(Char::isDigit).take(2))
        is SettingsEdit.BroadcastStartBip -> copy(broadcastStartBipEnabled = edit.enabled)
        is SettingsEdit.FloatingButton -> copy(floatingButtonEnabled = edit.enabled)
        is SettingsEdit.HostServer -> copy(hostServerEnabled = edit.enabled)
        is SettingsEdit.Theme -> copy(themeMode = edit.value)
        is SettingsEdit.Language -> copy(languageMode = edit.value)
        // The switch takes the scheme back from the address field. Resolving that address to
        // host:port first keeps the port the scheme implied — dropping a `https://` would
        // otherwise silently take 443 with it.
        is SettingsEdit.Tls -> copy(
            address = customAddress?.takeIf { it.secure != null }?.hostAndPort() ?: address,
            useTls = edit.enabled,
        )

        is SettingsEdit.Fingerprint -> copy(fingerprint = edit.value)
        is SettingsEdit.AccessToken -> copy(accessToken = edit.value)
        is SettingsEdit.AccessTokenVisible -> copy(accessTokenVisible = edit.visible)
    }

    companion object {
        /** Seeds a fresh form from what is on disk. Called once, by `OpenSettingsReducer`. */
        fun from(settings: AppSettings): SettingsFormState = SettingsFormState(
            stored = settings,
            serverMode = settings.serverMode,
            address = "${settings.customHost}:${settings.customPort}",
            displayName = settings.displayName,
            channel = settings.channel.toString(),
            broadcastStartBipEnabled = settings.broadcastStartBipEnabled,
            floatingButtonEnabled = settings.floatingButtonEnabled,
            hostServerEnabled = settings.hostServerEnabled,
            themeMode = settings.themeMode,
            languageMode = settings.languageMode,
            useTls = settings.useTls,
            fingerprint = CertificatePin.format(settings.certificateSha256),
            accessToken = settings.accessToken,
            accessTokenVisible = false,
        )
    }
}
