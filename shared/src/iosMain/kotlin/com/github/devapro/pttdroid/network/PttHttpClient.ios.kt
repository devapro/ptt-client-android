package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.data.settings.CertificatePin
import com.github.devapro.pttdroid.network.tls.leafCertificateDer
import com.github.devapro.pttdroid.network.tls.sha256
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust

/**
 * Darwin-engine actual for the iOS targets — see the `expect fun` KDoc in `PttHttpClient.kt` and
 * `docs/architecture.md#transport-security` for the three states this has to reproduce (no TLS,
 * TLS with normal CA verification, TLS pinned to one certificate's SHA-256).
 *
 * Deliberately **not** `io.ktor.client.engine.darwin.certificates.CertificatePinner` — that pins
 * the certificate's *SPKI* hash. This app's pin is SHA-256 of the *whole DER certificate*
 * (`PinnedTrustManager.checkServerTrusted` on the JVM side does
 * `MessageDigest.getInstance("SHA-256").digest(presented.encoded)`), a different value from the
 * SPKI hash for the same certificate. Using `CertificatePinner` would mean the one
 * `certificateSha256` setting needs a *different* value on iOS than on Android/desktop for the
 * same relay — exactly the kind of silent platform-specific breakage `PttEndpoint` exists to
 * prevent.
 *
 * Confidence: **verified against klib metadata**, including the two members Phase 7a flagged as
 * unresolved. `HttpClient(Darwin) { engine { handleChallenge { ... } } }`'s shape was verified
 * against `ktor-client-darwin:3.5.2`'s own klib metadata (`klib dump-metadata` on the downloaded
 * `.klib`): `handleChallenge(block: (NSURLSession, NSURLSessionTask, NSURLAuthenticationChallenge,
 * (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit) -> Unit)` — exactly what is
 * written here.
 *
 * **Phase 7a's "Unresolved reference" on `serverTrust`/`credentialForTrust`, root-caused (Phase
 * 7b): not a Linux cross-compilation limitation — a missing import.** `klib dump-metadata` on
 * `org.jetbrains.kotlin.native.platform.Foundation` (this project's Kotlin/Native 2.4.10
 * distribution, iosSimulatorArm64) shows both `NSURLProtectionSpace.serverTrust` and
 * `NSURLCredential`'s `credentialForTrust` factory declared *outside* their class bodies — real
 * members of `NSURLProtectionSpace`/`NSURLCredential` (e.g. `protectionSpace`, `user`, `password`)
 * appear inside the `class { ... }` block in the dump; `serverTrust`, `distinguishedNames` and
 * `credentialForTrust` do not, because Apple declares them in a separate category (the "server
 * trust authentication" additions), which Kotlin/Native's interop generator mirrors as top-level
 * extension declarations in the `platform.Foundation` package. An extension has to be brought into
 * scope with its own import — `import platform.Foundation.serverTrust` /
 * `import platform.Foundation.credentialForTrust` below — the same as any other Kotlin extension
 * function; merely importing the *class* (`NSURLCredential`, already imported) does not do it,
 * and unlike a genuine member, the compiler's error for a missing one is "Unresolved reference"
 * rather than anything import-shaped, which is what made this look environmental in Phase 7a. Once
 * these two imports were added, `./gradlew -PenableIosTargets=true
 * :shared:compileKotlinIosSimulatorArm64`/`compileKotlinIosArm64` both succeed — see the Phase 7b
 * report. **This retires the Phase 7a report's #1 predicted CI failure.**
 *
 * Known accepted gap, recorded in `docs/known-issues.md`: unlike the JVM path
 * (`PinnedTrustManager`), this does not check the certificate's *validity window* — only the pin.
 * A pin match on an expired certificate is accepted here where the JVM/desktop client would
 * reject it with a distinguishable message. Closing that gap needs a DER `Validity` parser, which
 * is out of scope for Phase 7a.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun createPttHttpClient(endpoint: PttEndpoint): HttpClient {
    val pin = endpoint.pinnedSha256
    return HttpClient(Darwin) {
        install(WebSockets)
        engine {
            handleChallenge { _, _, challenge, completionHandler ->
                val space = challenge.protectionSpace
                val trust = space.serverTrust
                val hasPin = CertificatePin.normalize(pin).isNotEmpty()

                if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust ||
                    trust == null ||
                    !hasPin
                ) {
                    // Not a server-trust challenge, or "encryption on, no fingerprint" in the
                    // architecture doc's table: the platform's own CA verification applies,
                    // unchanged. This is also what a `ws://` (non-TLS) connection gets, since it
                    // never raises a server-trust challenge in the first place.
                    completionHandler(
                        NSURLSessionAuthChallengePerformDefaultHandling,
                        null,
                    )
                    return@handleChallenge
                }

                val leafDer = leafCertificateDer(trust)
                val matches = leafDer != null && CertificatePin.matches(pin, sha256(leafDer))
                val disposition: NSURLSessionAuthChallengeDisposition
                val credential: NSURLCredential?
                if (matches) {
                    // Mirrors PinnedHostnameVerifier (network/tls/PinnedTrust.kt): once the pin
                    // matches, the hostname/CA chain is not re-checked — the pin already
                    // identifies the peer more precisely than a name would, per that class's
                    // KDoc. `+[NSURLCredential credentialForTrust:]` is Apple's public API for
                    // exactly this: it is what tells URLSession to accept this handshake despite
                    // failing normal chain validation. (Kotlin/Native's binding also exposes this
                    // same functionality as a generic `NSURLCredential.create(trust)` factory;
                    // both were tried while chasing the "Unresolved reference" below and behave
                    // identically in this environment, so `credentialForTrust` — the API every
                    // Swift/ObjC reference for this task actually names — was kept.)
                    disposition = NSURLSessionAuthChallengeUseCredential
                    credential = NSURLCredential.credentialForTrust(trust)
                } else {
                    disposition = NSURLSessionAuthChallengeCancelAuthenticationChallenge
                    credential = null
                }
                completionHandler(disposition, credential)
            }
        }
    }
}

/**
 * Confidence: **low** for recognizing any specific Darwin error type — the exact exception shape
 * Ktor's Darwin engine wraps an `NSURLErrorDomain`/`NSError` failure in (e.g. whether it is some
 * `DarwinHttpRequestException` carrying the `NSError`, or the `NSError` itself bridged as a
 * Kotlin `Throwable`) is not verified here, and inventing a type name to catch would risk an
 * `is` check against a class that does not exist, or exists under a different name/package,
 * which would simply never match rather than fail loudly. Returning null is deliberate: it lets
 * `KtorPttConnection`'s commonMain cause-chain walk fall through to `cause.message`, which for an
 * `NSError`-backed failure is normally already a readable OS-provided description (e.g. "The
 * certificate for this server is invalid" / "A server with the specified hostname could not be
 * found") — a materially worse fallback than the JVM's exact `CertificateException.message`, but
 * not a broken one.
 */
internal actual fun describePlatformCause(cause: Throwable): String? = null
