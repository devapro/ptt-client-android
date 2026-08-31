package com.github.devapro.pttdroid.network.tls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustRef
import platform.posix.memcpy

/**
 * The iOS half of `docs/architecture.md#transport-security`'s pinning story.
 *
 * `network/PttHttpClient.ios.kt`'s `handleChallenge` calls [leafCertificateDer] to get the
 * server's presented certificate as DER bytes, then hashes it with [sha256] and compares against
 * `PttEndpoint.pinnedSha256` via the existing commonMain `CertificatePin.matches` — the exact
 * same "SHA-256 of the whole DER certificate" the JVM side's `PinnedTrustManager`
 * (`network/tls/PinnedTrust.kt`) checks, so one fingerprint in Settings works identically on
 * every platform. As of Phase 7b this file compiles for real — Kotlin/Native can *frontend-compile*
 * (klib, not link) Apple targets from this Linux machine
 * (`-PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64`/`compileKotlinIosArm64`) — and
 * every cinterop call below has been cross-checked against `klib dump-metadata` on the exact
 * platform klibs this build resolves, not just written from memory. It still cannot be *linked* or
 * *run* here — that needs a real Mac, see `.github/workflows/ios.yml`. See the confidence notes on
 * each function.
 */

/**
 * SHA-256 over raw bytes, via `CommonCrypto`'s `CC_SHA256` — `platform.CoreCrypto` is one of
 * Kotlin/Native's built-in Apple "platform library" bindings (generated from the Apple SDK the
 * toolchain runs against, the same way `platform.Foundation`/`platform.Security` are), not a new
 * third-party dependency. Confidence: **verified against klib metadata** (Phase 7b, `klib
 * dump-metadata` on `org.jetbrains.kotlin.native.platform.CommonCrypto`'s iosSimulatorArm64 klib):
 * `CC_SHA256(data: CValuesRef<*>?, len: UInt /* = CC_LONG */, md: CValuesRef<UByteVarOf<UByte>>?):
 * CPointer<UByteVarOf<UByte>>?` — `input.addressOf(0)`/`output.addressOf(0)` (both `CPointer<...>`)
 * satisfy the two `CValuesRef<*>?` parameters, and `bytes.size.convert()` produces the `UInt` the
 * length parameter expects. This file now compiles for real (`:shared:compileKotlinIosSimulatorArm64`,
 * `-PenableIosTargets=true`) with this call unchanged from the Phase 7a draft.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun sha256(bytes: ByteArray): ByteArray {
    // 32 == CC_SHA256_DIGEST_LENGTH. Hardcoded rather than referencing the C constant: safer to
    // get wrong as an obviously-fixable literal than as a plausible-looking but unverified
    // symbol name.
    val digest = UByteArray(32)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(input.addressOf(0), bytes.size.convert(), output.addressOf(0))
        }
    }
    @OptIn(ExperimentalUnsignedTypes::class)
    return digest.asByteArray()
}

/**
 * The leaf (server-presented) certificate out of an `SecTrustRef`, as raw DER bytes.
 *
 * Confidence: **verified against klib metadata** (Phase 7b, `klib dump-metadata` on the
 * `org.jetbrains.kotlin.native.platform.Security`/`.Foundation` klibs shipped with this project's
 * Kotlin/Native 2.4.10 distribution, cross-checked by actually compiling this file for
 * `iosSimulatorArm64` — see the Phase 7b report). Two things the Phase 7a KDoc flagged as
 * unverified are now settled:
 * - `SecTrustGetCertificateAtIndex` is deprecated since iOS 15 in favour of
 *   `SecTrustCopyCertificateChain`, which returns a `CFArray` that then needs
 *   `CFArrayGetValueAtIndex` plus a pointer cast to unwrap — meaningfully more cinterop surface
 *   to get wrong. `SecTrustGetCertificateAtIndex` returns a single typed `SecCertificateRef?`
 *   directly, so it was kept deliberately *because* it is simpler. It is still present and
 *   functional through recent iOS releases (deprecated does not mean removed).
 * - `SecCertificateCopyData` does **not** return `NSData` directly — its klib signature is
 *   `(SecCertificateRef?) -> CPointer<__CFData>? /* = CFDataRef^? */`, so `as? NSData` on that
 *   result can never succeed (confirmed: compiling this file with the direct cast produces a
 *   compiler warning to that effect, "This cast can never succeed" — which would have made this
 *   function silently return null for every certificate, defeating the pin). `CFDataRef` and
 *   `NSData` are toll-free bridged at the Objective-C level, but Kotlin/Native still requires an
 *   explicit `CFBridgingRelease` to cross that bridge — it also transfers ownership of the +1
 *   reference `SecCertificateCopyData` returns (Core Foundation's "copy" naming rule) to Kotlin's
 *   normal ObjC object refcounting, so this does not leak the way a bare pointer cast would if it
 *   somehow had compiled.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun leafCertificateDer(trust: SecTrustRef): ByteArray? {
    @Suppress("DEPRECATION")
    val certificate = SecTrustGetCertificateAtIndex(trust, 0L) ?: return null
    val data = CFBridgingRelease(SecCertificateCopyData(certificate)) as? NSData ?: return null
    return data.toByteArrayCopy()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArrayCopy(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, size.convert()) }
    return out
}
