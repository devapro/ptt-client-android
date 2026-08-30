package com.github.devapro.pttdroid.domain

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with full jitter, replacing the old fixed `delay(1000L)` that retried
 * forever at a constant rate for as long as the app was in the foreground.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 500,
    private val maxDelayMs: Long = 30_000,
    private val random: Random = Random.Default,
) {
    private var attempt: Int = 0

    /** Delay before retry number [attempt] (1-based), jittered into `[base, ceiling]`. */
    fun nextDelayMs(): Long {
        val exponentialCeiling = min(maxDelayMs, baseDelayMs shl min(attempt, MAX_SHIFT))
        attempt++
        if (exponentialCeiling <= baseDelayMs) return baseDelayMs
        return baseDelayMs + random.nextLong(exponentialCeiling - baseDelayMs + 1)
    }

    /** Ceiling for the next attempt without consuming it — used by tests and logging. */
    fun peekCeilingMs(): Long = min(maxDelayMs, baseDelayMs shl min(attempt, MAX_SHIFT))

    fun reset() {
        attempt = 0
    }

    val attempts: Int get() = attempt

    private companion object {
        /** Caps the shift so `baseDelayMs shl attempt` cannot overflow. */
        const val MAX_SHIFT = 16
    }
}
