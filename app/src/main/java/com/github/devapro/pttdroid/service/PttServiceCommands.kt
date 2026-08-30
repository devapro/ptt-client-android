package com.github.devapro.pttdroid.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * The command surface every non-Activity entry point (notification action, widget tap,
 * overlay) uses to drive the service.
 */
object PttServiceCommands {

    const val ACTION_START = "com.github.devapro.pttdroid.action.START"
    const val ACTION_STOP = "com.github.devapro.pttdroid.action.STOP"
    const val ACTION_TOGGLE_TALK = "com.github.devapro.pttdroid.action.TOGGLE_TALK"
    const val ACTION_START_TALK = "com.github.devapro.pttdroid.action.START_TALK"
    const val ACTION_STOP_TALK = "com.github.devapro.pttdroid.action.STOP_TALK"
    const val ACTION_SET_CHANNEL = "com.github.devapro.pttdroid.action.SET_CHANNEL"
    const val ACTION_REFRESH_OVERLAY = "com.github.devapro.pttdroid.action.REFRESH_OVERLAY"

    const val EXTRA_CHANNEL = "channel"

    fun intent(context: Context, action: String): Intent =
        Intent(context, PttForegroundService::class.java).setAction(action)

    fun pendingIntent(context: Context, action: String, requestCode: Int = action.hashCode()): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            intent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Starts the service in the foreground.
     *
     * Android 14+ rejects starting a `microphone` foreground service from the background, so
     * the caller must be a visible Activity or an exempt user gesture (a notification action
     * or a widget tap both qualify).
     */
    fun start(context: Context, action: String = ACTION_START) {
        ContextCompat.startForegroundService(context, intent(context, action))
    }
}
