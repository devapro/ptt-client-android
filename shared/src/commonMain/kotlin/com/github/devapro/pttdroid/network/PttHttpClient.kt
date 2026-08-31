package com.github.devapro.pttdroid.network

import io.ktor.client.HttpClient

/**
 * Builds the `HttpClient` used for the PTT WebSocket connection, with TLS trust configured for
 * [endpoint] — see [PttEndpoint.trustProfile].
 *
 * Ktor's `engine { }` configuration block is typed per engine (OkHttp on the JVM targets today; a
 * Darwin actual would arrive with Phase 7's iOS targets), so the client construction itself — not
 * just the engine factory — has to sit behind this `expect`/`actual` seam. [KtorPttConnection]
 * stays in commonMain and calls this rather than constructing an engine-specific client directly.
 */
internal expect fun createPttHttpClient(endpoint: PttEndpoint): HttpClient

/**
 * Platform-specific description of [cause], if it recognizes the exception type, or null to keep
 * walking the cause chain.
 *
 * On the JVM this recognizes `java.security.cert.CertificateException`, which is what
 * `PinnedTrustManager` throws for a mismatched fingerprint or an out-of-window certificate — see
 * [KtorPttConnection]'s `describe`. A future non-JVM target (Phase 7) would recognize whatever its
 * own TLS stack throws instead.
 */
internal expect fun describePlatformCause(cause: Throwable): String?
