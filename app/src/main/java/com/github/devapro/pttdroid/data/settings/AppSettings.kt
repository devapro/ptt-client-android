package com.github.devapro.pttdroid.data.settings

import com.github.devapro.pttdroid.network.PttEndpoint

/**
 * User-configurable settings.
 *
 * [serverHost] used to be a hardcoded LAN address compiled into the socket class, which made
 * the app unusable on any other network.
 */
data class AppSettings(
    val serverHost: String = DEFAULT_HOST,
    val serverPort: Int = DEFAULT_PORT,
    val channel: Int = DEFAULT_CHANNEL,
    val displayName: String = DEFAULT_NAME,
    val floatingButtonEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Run an on-device relay so no separate server is needed on a LAN. */
    val hostServerEnabled: Boolean = false,
    /** `wss://` instead of `ws://`. */
    val useTls: Boolean = false,
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
         * An Android emulator reaches a server on the host machine at 10.0.2.2 —
         * `localhost` inside the emulator is the emulator itself.
         */
        const val DEFAULT_HOST: String = "10.0.2.2"
        const val DEFAULT_PORT: Int = 8000
        const val DEFAULT_CHANNEL: Int = 1
        const val DEFAULT_NAME: String = "Anon"

        val CHANNEL_RANGE: IntRange = 1..99
        val PORT_RANGE: IntRange = 1..65_535
        const val MAX_NAME_LENGTH: Int = 32

        /** Long enough for `openssl rand -base64 48`, which is more than anyone needs. */
        const val MAX_TOKEN_LENGTH: Int = 128

        fun clampChannel(value: Int): Int = value.coerceIn(CHANNEL_RANGE)

        private fun encodeName(name: String): String =
            java.net.URLEncoder.encode(name.take(MAX_NAME_LENGTH), "UTF-8")
    }
}
