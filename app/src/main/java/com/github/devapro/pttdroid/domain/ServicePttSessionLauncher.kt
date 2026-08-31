package com.github.devapro.pttdroid.domain

import android.content.Context
import com.github.devapro.pttdroid.service.PttServiceCommands

/** The Android [PttSessionLauncher]: starts/stops [com.github.devapro.pttdroid.service.PttForegroundService]. */
class ServicePttSessionLauncher(private val context: Context) : PttSessionLauncher {

    /**
     * Must be called while an Activity is visible: Android 14+ refuses to start a microphone
     * foreground service from the background.
     */
    override fun start() {
        PttServiceCommands.start(context)
    }

    override fun stop() {
        context.startService(
            PttServiceCommands.intent(context, PttServiceCommands.ACTION_STOP),
        )
    }
}
