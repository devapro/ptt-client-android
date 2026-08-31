package com.github.devapro.pttdroid.network

/**
 * One relay address plus the trust and credentials needed to reach it.
 *
 * These travel together because they change together: switching to `wss://` without the
 * matching pin, or to a different relay while keeping the old token, is exactly the kind of
 * half-applied change that a single `String` url invited.
 */
data class PttEndpoint(
    val url: String,
    /**
     * SHA-256 of the certificate to trust, hex, no separators. Empty means the platform's
     * usual certificate-authority verification applies.
     */
    val pinnedSha256: String = "",
    /** Sent as [TOKEN_HEADER]. Empty when the relay does not require one. */
    val accessToken: String = "",
) {
    val isSecure: Boolean get() = url.startsWith("wss://", ignoreCase = true)

    /**
     * The parts that decide how the TLS stack is built. Anything else can change without
     * tearing the HTTP client down.
     */
    val trustProfile: String get() = "${isSecure}:$pinnedSha256"

    companion object {
        /** Matches `TOKEN_HEADER` in the server repo's `routing/ChannelRoutes.kt`. */
        const val TOKEN_HEADER: String = "X-PTT-Token"
    }
}
