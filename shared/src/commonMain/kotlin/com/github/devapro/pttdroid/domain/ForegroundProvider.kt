package com.github.devapro.pttdroid.domain

/**
 * Whether the app's own UI is currently in front of the user.
 *
 * The session outlives any Activity by design, so "foreground" cannot be inferred from the
 * controller's own lifecycle — it has to be reported. Android counts its Activity's
 * start/stop through a `ForegroundTracker`; desktop and iOS have no background receive surface
 * worth recording, so they bind a constant `true` and the history capture never fires there.
 */
fun interface ForegroundProvider {
    fun isForeground(): Boolean
}
