package com.github.devapro.pttdroid.domain

/**
 * Android and desktop both compile `jvmCommonMain`, which is where
 * [com.github.devapro.pttdroid.internalserver.InternalPttServer] lives — see
 * `di/SharedDiJvm.kt`'s `jvmDi()`. Both can host the relay.
 */
actual val canHostRelay: Boolean = true
