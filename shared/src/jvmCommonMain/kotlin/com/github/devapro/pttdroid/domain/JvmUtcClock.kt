package com.github.devapro.pttdroid.domain

/** JVM's [UtcClock] — Android and desktop share it (`jvmCommonMain`). */
class JvmUtcClock : UtcClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
