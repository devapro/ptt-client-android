package com.github.devapro.pttdroid

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * `Dispatchers.Default`, not a dedicated IO pool — see the `expect val` KDoc in
 * `CoroutineContextProvider.kt` for why one is not publicly available on Kotlin/Native today.
 * Confidence: **medium**. This is a reasonable stand-in rather than a verified equivalent: every
 * "IO" operation this app actually performs — the Ktor WebSocket client, `DataStore` — is itself
 * suspending/non-blocking, not a thread that blocks on file or socket syscalls the way classic
 * JVM `Dispatchers.IO` exists to absorb; `Default`'s bounded parallelism (~number of cores) should
 * be adequate for that. It has not been profiled under load on a real device.
 */
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
