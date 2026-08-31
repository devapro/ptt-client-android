package com.github.devapro.pttdroid

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

class CoroutineContextProvider {

    val main: CoroutineDispatcher by lazy { Dispatchers.Main }

    val io: CoroutineDispatcher by lazy { ioDispatcher }

    val default: CoroutineDispatcher by lazy { Dispatchers.Default }

    val globalScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob(null) + main) }

    fun createScope(
        context: CoroutineContext
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob(null) + context)
    }
}

/**
 * `Dispatchers.IO` (used for [CoroutineContextProvider.io], the dispatcher `SESSION_SCOPE`
 * — `di/SharedDi.kt` — runs the whole session on) is JVM-only: `kotlinx-coroutines-core` 1.11.0
 * (the version pinned here) declares a `Dispatchers.IO` for Kotlin/Native too, but it is
 * `internal`, not part of the public API a consumer can reference — confirmed by actually
 * compiling for `iosSimulatorArm64` on this Linux machine (Phase 7a), which is what caught this;
 * it was already wrong for a target this repo could not previously build for at all. See
 * `CoroutineContextProvider.jvm.kt`/`.ios.kt` for the two actuals.
 */
internal expect val ioDispatcher: CoroutineDispatcher