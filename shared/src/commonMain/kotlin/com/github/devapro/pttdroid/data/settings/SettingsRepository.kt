package com.github.devapro.pttdroid.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings backed by DataStore, replacing the old `PrefManager` wrapper over the deprecated
 * `android.preference.PreferenceManager`.
 *
 * Takes the `DataStore<Preferences>` directly rather than an Android `Context`, so this class
 * lives in commonMain: the platform DI modules (`di/SharedDiAndroid.kt`, `di/SharedDiDesktop.kt`)
 * build the actual store — `data/settings/SettingsDataStore.android.kt` and
 * `SettingsDataStore.desktop.kt` — and bind it into the graph before this class is constructed.
 * See those files for the android/desktop file locations, and `docs/architecture.md` for why they
 * have to match what the old `preferencesDataStore(name = "ptt_settings")` delegate produced.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val storedHost = prefs[KEY_HOST]
        val storedPort = prefs[KEY_PORT]
        AppSettings(
            serverMode = ServerMode.restore(prefs[KEY_SERVER_MODE], storedHost, storedPort),
            customHost = storedHost ?: AppSettings.DEFAULT_HOST,
            customPort = storedPort ?: AppSettings.DEFAULT_PORT,
            displayName = prefs[KEY_NAME] ?: AppSettings.DEFAULT_NAME,
            broadcastStartBipEnabled = prefs[KEY_BROADCAST_START_BIP]
                ?: AppSettings.DEFAULT_BROADCAST_START_BIP_ENABLED,
            floatingButtonEnabled = prefs[KEY_FLOATING] ?: false,
            themeMode = ThemeMode.fromStorage(prefs[KEY_THEME]),
            languageMode = LanguageMode.fromStorage(prefs[KEY_LANGUAGE]),
            hostServerEnabled = prefs[KEY_HOST_SERVER] ?: false,
            useTls = prefs[KEY_USE_TLS] ?: AppSettings.DEFAULT_TLS,
            certificateSha256 = prefs[KEY_CERT_SHA256].orEmpty(),
            accessToken = prefs[KEY_ACCESS_TOKEN].orEmpty(),
            audioOutput = AudioOutput.fromStorage(prefs[KEY_AUDIO_OUTPUT]),
            playbackVolume = AppSettings.clampVolume(
                prefs[KEY_PLAYBACK_VOLUME] ?: AppSettings.DEFAULT_PLAYBACK_VOLUME,
            ),
            floatingButtonX = prefs[KEY_FLOATING_X] ?: 0,
            floatingButtonY = prefs[KEY_FLOATING_Y] ?: 300,
        )
    }

    /**
     * Writes every user-editable field in one transaction.
     *
     * Field-at-a-time writes meant the settings screen committed six times, emitting six
     * `AppSettings` values; a reconnect racing that could read a new host with the old port.
     * The floating-button position is deliberately not written here — the overlay owns it and
     * updates it as the user drags. [AppSettings.audioOutput]/[AppSettings.playbackVolume] are
     * left out for the same reason: the main screen owns them, changes them mid-conversation,
     * and has its own single-field setters below. This form has no control for either, so
     * writing them here could only ever write back a stale copy.
     */
    suspend fun save(settings: AppSettings) = edit { prefs ->
        prefs[KEY_SERVER_MODE] = settings.serverMode.name
        // The custom address is written in both modes: it is what Custom comes back to.
        prefs[KEY_HOST] = settings.customHost.trim()
        prefs[KEY_PORT] = settings.customPort.coerceIn(AppSettings.PORT_RANGE)
        prefs[KEY_CHANNEL] = AppSettings.clampChannel(settings.channel)
        prefs[KEY_NAME] = settings.displayName.trim().take(AppSettings.MAX_NAME_LENGTH)
            .ifEmpty { AppSettings.DEFAULT_NAME }
        prefs[KEY_BROADCAST_START_BIP] = settings.broadcastStartBipEnabled
        prefs[KEY_FLOATING] = settings.floatingButtonEnabled
        prefs[KEY_HOST_SERVER] = settings.hostServerEnabled
        prefs[KEY_THEME] = settings.themeMode.name
        prefs[KEY_LANGUAGE] = settings.languageMode.name
        prefs[KEY_USE_TLS] = settings.useTls
        // Stored normalized, so what is compared at handshake time never depends on how the
        // fingerprint happened to be pasted.
        prefs[KEY_CERT_SHA256] = CertificatePin.normalize(settings.certificateSha256)
        prefs[KEY_ACCESS_TOKEN] = settings.accessToken.trim().take(AppSettings.MAX_TOKEN_LENGTH)
    }

    suspend fun setChannel(channel: Int) = edit { prefs ->
        prefs[KEY_CHANNEL] = AppSettings.clampChannel(channel)
    }

    suspend fun setDisplayName(name: String) = edit { prefs ->
        prefs[KEY_NAME] = name.trim().take(AppSettings.MAX_NAME_LENGTH)
            .ifEmpty { AppSettings.DEFAULT_NAME }
    }

    suspend fun setFloatingButtonEnabled(enabled: Boolean) = edit { prefs ->
        prefs[KEY_FLOATING] = enabled
    }

    suspend fun setHostServerEnabled(enabled: Boolean) = edit { prefs ->
        prefs[KEY_HOST_SERVER] = enabled
    }

    suspend fun setAudioOutput(output: AudioOutput) = edit { prefs ->
        prefs[KEY_AUDIO_OUTPUT] = output.name
    }

    /**
     * Written once when the slider is let go, not on every value it passes through — a drag
     * across the whole track would otherwise be a hundred DataStore transactions, each of them
     * a full re-serialisation of the preferences file.
     */
    suspend fun setPlaybackVolume(volume: Float) = edit { prefs ->
        prefs[KEY_PLAYBACK_VOLUME] = AppSettings.clampVolume(volume)
    }

    suspend fun setFloatingButtonPosition(x: Int, y: Int) = edit { prefs ->
        prefs[KEY_FLOATING_X] = x
        prefs[KEY_FLOATING_Y] = y
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private companion object {
        val KEY_SERVER_MODE = stringPreferencesKey("server_mode")
        val KEY_HOST = stringPreferencesKey("server_host")
        val KEY_PORT = intPreferencesKey("server_port")
        val KEY_CHANNEL = intPreferencesKey("channel")
        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_BROADCAST_START_BIP = booleanPreferencesKey("broadcast_start_bip_enabled")
        val KEY_FLOATING = booleanPreferencesKey("floating_button_enabled")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_LANGUAGE = stringPreferencesKey("language_mode")
        val KEY_HOST_SERVER = booleanPreferencesKey("host_server_enabled")
        val KEY_USE_TLS = booleanPreferencesKey("use_tls")
        val KEY_CERT_SHA256 = stringPreferencesKey("certificate_sha256")
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_AUDIO_OUTPUT = stringPreferencesKey("audio_output")
        val KEY_PLAYBACK_VOLUME = floatPreferencesKey("playback_volume")
        val KEY_FLOATING_X = intPreferencesKey("floating_button_x")
        val KEY_FLOATING_Y = intPreferencesKey("floating_button_y")
    }
}
