package com.github.devapro.pttdroid.internalserver

import kotlin.random.Random

/**
 * Initializes `kotlin.random.Random` on the calling thread, before the relay and a Ktor client
 * can race to do it from two threads at once, which deadlocks the JVM's class initialization.
 *
 * `Random`'s static initializer builds `Random.Default`'s platform source by constructing a
 * subclass of `AbstractPlatformRandom`, which itself extends `Random`. Ktor's server pinger calls
 * `Random(seed)` when a WebSocket session starts, which initializes `Random`. Ktor's client
 * nonce generator calls `SecureRandom.asKotlinRandom()`, which initializes
 * `AbstractPlatformRandom` and therefore its superclass `Random`. If the two start together,
 * each thread holds the class-initialization lock the other needs, and neither ever finishes.
 *
 * A Ktor client's own first handshake cannot set this off: the handshake waits for that first
 * nonce, so no session of its own is pinging yet. A raw-socket peer's session can, because its
 * pinger starts while the first Ktor client in the JVM may still be initializing the nonce
 * generator. That is every test class here that opens a raw WebSocket to the relay. Seen as a
 * test JVM hung for minutes inside `webSocketSession()`, with `DefaultDispatcher-worker
 * @ws-pinger` and `@nonce-generator` each "waiting on the Class initialization monitor" for the
 * other's class (known-issues #48).
 *
 * After this runs, neither path initializes anything, so once per JVM is enough. Calling it
 * from each such class's setup is cheap and keeps the guard independent of test order.
 */
internal fun initKotlinRandomBeforeKtor() {
    Random.nextInt()
}
