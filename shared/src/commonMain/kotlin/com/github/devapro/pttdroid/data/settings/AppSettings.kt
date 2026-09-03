package com.github.devapro.pttdroid.data.settings

import com.github.devapro.pttdroid.network.PttEndpoint
import com.github.devapro.pttdroid.shared.generated.RelayDefaults
import io.ktor.http.encodeURLParameter

/**
 * User-configurable settings.
 *
 * The relay address used to be a hardcoded LAN address compiled into the socket class, which made
 * the app unusable on any other network. It is now a choice between the address the app ships with
 * and one the user types: [customHost] and [customPort] are what they typed, and [serverHost] and
 * [serverPort] are what the transport actually dials. Everything downstream reads the latter pair
 * and never has to know which mode is in force.
 */
data class AppSettings(
    val serverMode: ServerMode = ServerMode.DEFAULT,
    /** Kept even while [serverMode] is [ServerMode.DEFAULT], so switching back does not lose it. */
    val customHost: String = DEFAULT_HOST,
    val customPort: Int = DEFAULT_PORT,
    val channel: Int = DEFAULT_CHANNEL,
    val displayName: String = DEFAULT_NAME,
    val floatingButtonEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val languageMode: LanguageMode = LanguageMode.SYSTEM,
    /** Run an on-device relay so no separate server is needed on a LAN. */
    val hostServerEnabled: Boolean = false,
    /** `wss://` instead of `ws://`. */
    val useTls: Boolean = DEFAULT_TLS,
    /**
     * SHA-256 fingerprint of the relay's certificate. Empty means "verify the normal way,
     * against the device's certificate authorities" — which is what a tunnel or a reverse
     * proxy with a real certificate needs. Non-empty pins one exact certificate, which is what
     * a self-signed relay needs.
     */
    val certificateSha256: String = "",
    /** Shared secret the relay requires, sent as a header. Empty when the relay is open. */
    val accessToken: String = "",
    val floatingButtonX: Int = 0,
    val floatingButtonY: Int = 300,
) {
    val serverHost: String get() = if (serverMode.isCustom) customHost.trim() else DEFAULT_HOST

    val serverPort: Int get() = if (serverMode.isCustom) customPort else DEFAULT_PORT

    val scheme: String get() = if (useTls) "wss" else "ws"

    /** The address as the user should see it, with no credentials in it. */
    fun displayUrl(): String = "$scheme://$serverHost:$serverPort/channel/$channel"

    fun webSocketUrl(): String =
        "$scheme://$serverHost:$serverPort/channel/$channel" +
            "?name=${encodeName(displayName)}&v=1"

    /** Everything the transport needs to open this connection, and nothing it does not. */
    fun endpoint(): PttEndpoint = PttEndpoint(
        url = webSocketUrl(),
        pinnedSha256 = if (useTls) CertificatePin.normalize(certificateSha256) else "",
        accessToken = accessToken.trim(),
    )

    companion object {
        /**
         * The address behind [ServerMode.DEFAULT], set at build time by `defaultRelay` in
         * `relay.properties` — a fork with its own relay changes that one line rather than
         * anything here, and a packaged build then arrives already pointing at it.
         *
         * What this repository ships is `ws://10.0.2.2:8000`: an Android emulator reaches a
         * server on the host machine at 10.0.2.2, since `localhost` inside an emulator is the
         * emulator itself. That is useful for development and reaches nothing on a real handset,
         * which is the honest state of things while there is no public relay to point at.
         */
        val DEFAULT_HOST: String = RelayDefaults.HOST
        val DEFAULT_PORT: Int = RelayDefaults.PORT

        /** Whether that address is `wss://`, and so whether a fresh install starts encrypted. */
        val DEFAULT_TLS: Boolean = RelayDefaults.TLS
        const val DEFAULT_CHANNEL: Int = 1
        const val DEFAULT_NAME: String = "Anon"

        val CHANNEL_RANGE: IntRange = 1..99
        val PORT_RANGE: IntRange = 1..65_535
        const val MAX_NAME_LENGTH: Int = 32

        /** Long enough for `openssl rand -base64 48`, which is more than anyone needs. */
        const val MAX_TOKEN_LENGTH: Int = 128

        fun clampChannel(value: Int): Int = value.coerceIn(CHANNEL_RANGE)

        // `spaceToPlus = true` matches java.net.URLEncoder.encode(name, "UTF-8")'s
        // application/x-www-form-urlencoded behaviour byte-for-byte (space -> '+', everything
        // else percent-encoded with uppercase hex) for every case the tests cover — see the
        // Phase 2 migration report for the one untested edge case ('*'/'~') where they diverge.
        private fun encodeName(name: String): String =
            name.take(MAX_NAME_LENGTH).encodeURLParameter(spaceToPlus = true)
    }
}
