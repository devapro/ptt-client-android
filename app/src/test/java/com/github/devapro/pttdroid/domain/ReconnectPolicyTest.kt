package com.github.devapro.pttdroid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The old reconnect used a flat `delay(1000L)` and retried forever at a constant rate; these
 * tests pin the properties that replaced it.
 */
class ReconnectPolicyTest {

    @Test
    fun `delay grows and is capped`() {
        // maxValue-biased RNG so we observe the ceiling rather than the jitter.
        val policy = ReconnectPolicy(baseDelayMs = 500, maxDelayMs = 30_000, random = MaxRandom)

        val delays = (1..12).map { policy.nextDelayMs() }

        assertEquals(500L, delays.first())
        assertTrue("delays should be non-decreasing: $delays", delays.zipWithNext().all { it.first <= it.second })
        assertTrue("delays must never exceed the cap: $delays", delays.all { it <= 30_000L })
        assertEquals("should reach the cap", 30_000L, delays.last())
    }

    @Test
    fun `delay never drops below the base and always respects the cap under jitter`() {
        val policy = ReconnectPolicy(baseDelayMs = 500, maxDelayMs = 30_000, random = Random(42))
        repeat(200) {
            val delay = policy.nextDelayMs()
            assertTrue("delay $delay below base", delay >= 500L)
            assertTrue("delay $delay above cap", delay <= 30_000L)
        }
    }

    @Test
    fun `jitter actually varies the delay`() {
        val policy = ReconnectPolicy(baseDelayMs = 500, maxDelayMs = 30_000, random = Random(7))
        // Advance past the first couple of attempts where the window is still narrow.
        repeat(6) { policy.nextDelayMs() }
        val samples = (1..20).map { policy.nextDelayMs() }
        assertTrue("expected jittered values, got $samples", samples.distinct().size > 1)
    }

    @Test
    fun `reset returns to the base delay`() {
        val policy = ReconnectPolicy(baseDelayMs = 500, maxDelayMs = 30_000, random = MaxRandom)
        repeat(8) { policy.nextDelayMs() }
        assertTrue(policy.attempts > 0)

        policy.reset()

        assertEquals(0, policy.attempts)
        assertEquals(500L, policy.nextDelayMs())
    }

    /** Always returns the top of the requested range, exposing the ceiling. */
    private object MaxRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = until - 1
    }
}
