package com.github.devapro.pttdroid.foreground

import com.github.devapro.pttdroid.domain.ForegroundProvider

/**
 * Counts started Activities to answer "is the app's own UI in front?" — the STARTED state, so
 * a permission dialog (pause, not stop) keeps the app foreground and nothing gets recorded the
 * user could already hear. The overlay bubble and the widget are deliberately not counted:
 * neither can show a received transmission, which is the whole reason a record exists.
 *
 * A counter, not a boolean: with Compose and `recreate()` a new instance can start before the
 * old one is fully stopped, and the leaving instance's `onStop` must not extinguish the entering
 * one's claim.
 *
 * MainActivity reports into this; the DI binding is here in `:app` because it is the Activity's
 * own lifecycle that defines the answer.
 */
object ActivityForegroundTracker : ForegroundProvider {
    private var startedActivities = 0

    override fun isForeground(): Boolean = startedActivities > 0

    fun onActivityStarted() {
        startedActivities++
    }

    fun onActivityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }
}
