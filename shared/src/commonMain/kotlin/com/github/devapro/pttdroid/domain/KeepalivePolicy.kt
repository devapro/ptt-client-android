package com.github.devapro.pttdroid.domain

/**
 * How long [PttController] will sit on a silent socket before deciding it is dead.
 *
 * A WebSocket that loses its network — a NAT table expiring, a handset walking off Wi-Fi, a
 * relay killed mid-session — usually produces no close frame and no error: the read loop just
 * never returns another frame. Nothing in the transport notices, so the app used to keep
 * reporting "connected" until the user pressed talk and the first write failed. The counter
 * here is the thing that notices instead.
 *
 * The unit is an *interval with no inbound frame at all* rather than a timestamp, because
 * anything the relay sends — audio, `floor`, `peers`, a `pong` — proves the link as well as a
 * probe does, and a channel with someone talking on it should never be probed.
 *
 * [intervalMs] deliberately sits below the 15 s WebSocket ping the JVM engines run (see
 * `createPttHttpClient`): on Android and desktop the transport normally spots a dead peer first
 * and this is the backstop, while on iOS — whose engine has no ping of its own — it is the only
 * mechanism there is.
 */
data class KeepalivePolicy(
    /** Gap with no inbound frame that triggers one probe. */
    val intervalMs: Long = 10_000,
    /**
     * Consecutive silent intervals before the socket is given up on. The last one is not
     * probed — by then two probes have already gone unanswered, so `intervalMs * this` is the
     * worst-case time from a link dying to the reconnect starting.
     */
    val maxSilentIntervals: Int = 3,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be positive" }
        require(maxSilentIntervals >= 2) {
            // One would fire on the very first quiet interval, before any probe could have been
            // answered — every idle connection would reconnect in a loop.
            "maxSilentIntervals must leave room for at least one probe"
        }
    }

    val timeoutMs: Long get() = intervalMs * maxSilentIntervals
}
