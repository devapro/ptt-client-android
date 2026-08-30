package com.github.devapro.pttdroid.data.settings

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
    val floatingButtonX: Int = 0,
    val floatingButtonY: Int = 300,
) {
    fun webSocketUrl(): String =
        "ws://$serverHost:$serverPort/channel/$channel" +
            "?name=${encodeName(displayName)}&v=1"

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

        fun clampChannel(value: Int): Int = value.coerceIn(CHANNEL_RANGE)

        private fun encodeName(name: String): String =
            java.net.URLEncoder.encode(name.take(MAX_NAME_LENGTH), "UTF-8")
    }
}
