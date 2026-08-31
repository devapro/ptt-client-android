package com.github.devapro.pttdroid.di

import com.github.devapro.pttdroid.internalserver.InternalPttServer
import org.koin.core.module.Module

/**
 * The on-device relay only exists on the JVM (it embeds a Ktor CIO server, unavailable on the
 * targets this app does not yet build for). Both JVM platforms — Android and desktop — call this
 * from their own DI module (`SharedDiAndroid.kt`, `SharedDiDesktop.kt`) rather than duplicating
 * the binding, since `jvmCommonMain` is exactly the source set they already share
 * (`shared/build.gradle.kts`).
 */
internal fun Module.jvmDi() {
    single { InternalPttServer() }
}
