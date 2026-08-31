package com.github.devapro.pttdroid.domain

/**
 * Starts and stops the foreground service that hosts the session.
 *
 * Exists so reducers can express "make sure the session is running" without depending on
 * Android service plumbing, and so they stay unit-testable with a fake.
 *
 * The Android implementation, [com.github.devapro.pttdroid.domain.ServicePttSessionLauncher],
 * uses `android.content.Context` and starts `PttForegroundService`, so it stays in `:app`.
 */
interface PttSessionLauncher {
    fun start()
    fun stop()
}
