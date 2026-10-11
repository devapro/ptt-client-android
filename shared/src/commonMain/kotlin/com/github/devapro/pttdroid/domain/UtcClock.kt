package com.github.devapro.pttdroid.domain

/**
 * Epoch milliseconds. A seam rather than calls to the platform clock scattered through the code,
 * so the history capture and its 30-second answer window are testable on virtual time — the
 * same reason [ReconnectPolicy] takes delays instead of reading a clock itself.
 */
fun interface UtcClock {
    fun nowMs(): Long
}
