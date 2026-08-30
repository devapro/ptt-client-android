package com.github.devapro.pttdroid.data.settings

/**
 * The relay address as the user typed it, parsed into the pieces [AppSettings] stores.
 *
 * People do not type addresses, they paste them: `ptt-server` prints `ws://192.168.1.20:8000` when
 * it starts and a tunnel hands out `https://something.ngrok-free.app`. Both are accepted whole, and
 * so is a bare `relay.local`, rather than making the user take a URL apart into a host box and a
 * port box and work out which switch the `https` corresponded to.
 */
sealed interface ServerAddress {

    data class Valid(
        val host: String,
        val port: Int,
        /**
         * Non-null only when the text carried a scheme, in which case it decides encryption.
         * Null means the text said nothing about it and the Security switch still owns it.
         */
        val secure: Boolean?,
    ) : ServerAddress {
        /** The same address with nothing left implied — what the field is normalized to. */
        fun hostAndPort(): String = "$host:$port"
    }

    /** Why the text cannot be used. Each maps to one message under the field. */
    enum class Problem : ServerAddress {
        EMPTY,

        /** `wss://user:pass@relay` — credentials in a URL reach every proxy log on the way. */
        CREDENTIALS,

        PORT,
        MALFORMED,
    }

    companion object {
        /** The port to assume when the text names none. */
        private const val DEFAULT_TLS_PORT = 443
        private const val DEFAULT_HTTP_PORT = 80

        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$")
        private val IPV6 = Regex("^\\[[0-9A-Fa-f:.]+]$")

        fun parse(raw: String): ServerAddress {
            val text = raw.trim()
            if (text.isEmpty()) return Problem.EMPTY

            val separator = text.indexOf("://")
            val secure: Boolean?
            var rest = text
            if (separator >= 0) {
                secure = when (text.take(separator).lowercase()) {
                    "wss", "https" -> true
                    "ws", "http" -> false
                    else -> return Problem.MALFORMED
                }
                rest = text.substring(separator + 3)
            } else {
                secure = null
            }

            // A pasted URL brings a path with it — `/channel/1` here would end up inside the host.
            rest = rest.takeWhile { it != '/' && it != '?' && it != '#' }
            if (rest.isEmpty()) return Problem.EMPTY
            if (rest.contains('@')) return Problem.CREDENTIALS

            val host: String
            val portText: String?
            if (rest.startsWith('[')) {
                val close = rest.indexOf(']')
                if (close < 0) return Problem.MALFORMED
                // The brackets stay: they are what makes an IPv6 literal legal in a URL.
                host = rest.take(close + 1)
                val tail = rest.substring(close + 1)
                portText = when {
                    tail.isEmpty() -> null
                    tail.startsWith(':') -> tail.drop(1)
                    else -> return Problem.MALFORMED
                }
                if (!IPV6.matches(host)) return Problem.MALFORMED
            } else {
                val colon = rest.indexOf(':')
                host = if (colon < 0) rest else rest.take(colon)
                portText = if (colon < 0) null else rest.substring(colon + 1)
                // More than one colon and no brackets is a bare IPv6 literal, which is ambiguous
                // with a port and illegal in a URL either way.
                if (portText?.contains(':') == true) return Problem.MALFORMED
                if (!HOST.matches(host)) return Problem.MALFORMED
            }

            val port = when {
                portText == null -> defaultPort(secure)
                portText.isEmpty() || portText.any { !it.isDigit() } -> return Problem.PORT
                else -> portText.toIntOrNull()?.takeIf { it in AppSettings.PORT_RANGE }
                    ?: return Problem.PORT
            }

            return Valid(host = host, port = port, secure = secure)
        }

        /**
         * A scheme that was spelled out keeps its own default port — `https://x.ngrok-free.app` is
         * 443 and nothing else. Only a bare host, which says nothing about the protocol, gets this
         * app's relay port. Either way the "will connect to" line spells the result out, so a wrong
         * guess is visible before it is saved rather than after a failed connection.
         */
        private fun defaultPort(secure: Boolean?): Int = when (secure) {
            null -> AppSettings.DEFAULT_PORT
            true -> DEFAULT_TLS_PORT
            false -> DEFAULT_HTTP_PORT
        }
    }
}
