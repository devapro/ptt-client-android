package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.domain.UtcClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The MainScreen history badge's single cross-feature fact: has any record arrived since the
 * list was last opened?
 *
 * Lives in `:shared` (not in the history feature) precisely because its only consumer is
 * `MainScreen` — so the `:features:main -> :features:history` edge never has to exist. Unread is
 * derived, not stored per record: "new" means "arrived after the last time the list was opened"
 * (lastViewedAt 0 — never opened — makes every record new).
 *
 * [clock] is the same [UtcClock] seam `OpenHistoryReducer` takes — the read-receipt side of the
 * badge lives here too ([markSeen]) so a test can drive both ends of the badge with one fake
 * clock, and a consumer that holds the badge can record a viewing without reaching past it into
 * the store.
 */
class HistoryBadge(private val store: HistoryStore, private val clock: UtcClock) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val unread: StateFlow<Boolean> =
        combine(store.records, store.lastViewedAt) { records, seen ->
            records.any { it.endedAtMs > seen }
        }.stateIn(
            // The badge is a process-lifetime singleton, so its own eagerly-collecting scope is
            // the honest lifecycle — no host has to remember to keep it subscribed. Unconfined so
            // a store write is reflected in `unread` before the writer's next read.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /** Records "the list was opened now" — monotonic in the store, so re-entrant safe. */
    suspend fun markSeen() = store.markAllSeen(clock.nowMs())
}
