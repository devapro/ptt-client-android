package com.github.devapro.pttdroid.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.internalserver.InternalPttServer
import com.github.devapro.pttdroid.overlay.OverlayController
import com.github.devapro.pttdroid.widget.PttWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Keeps the PTT session alive independently of any Activity.
 *
 * This is what makes talking without opening the app possible: the socket, microphone and
 * speaker all belong to [PttController], which lives as long as this service does. Declared
 * with `foregroundServiceType="microphone"`, which Android 14+ requires for mic capture.
 */
class PttForegroundService : Service() {

    private val controller: PttController by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val overlayController: OverlayController by inject()
    private val internalServer: InternalPttServer by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PttNotifications.ensureChannel(this)
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            PttServiceCommands.ACTION_STOP -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }

            PttServiceCommands.ACTION_TOGGLE_TALK -> {
                promoteToForeground()
                controller.toggleTalk()
            }

            PttServiceCommands.ACTION_START_TALK -> {
                promoteToForeground()
                controller.requestTalk()
            }

            PttServiceCommands.ACTION_STOP_TALK -> controller.releaseTalk()

            PttServiceCommands.ACTION_SET_CHANNEL -> {
                promoteToForeground()
                val channel = intent.getIntExtra(
                    PttServiceCommands.EXTRA_CHANNEL,
                    AppSettings.DEFAULT_CHANNEL,
                )
                controller.setChannel(channel)
            }

            else -> {
                if (action != PttServiceCommands.ACTION_START &&
                    action != PttServiceCommands.ACTION_REFRESH_OVERLAY
                ) {
                    Timber.d("Unrecognised action %s; starting session anyway", action)
                }
                promoteToForeground()
            }
        }

        // Restarted by the system after being killed: resume the session rather than idling.
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification = PttNotifications.build(this, controller.state.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, PttNotifications.NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // Most likely ForegroundServiceStartNotAllowedException: we were started from the
            // background, which Android 14+ forbids for a microphone FGS.
            Timber.e(e, "Could not enter the foreground; stopping")
            stopSelfCleanly()
            return
        }

        if (!started) {
            started = true
            controller.start()
        }
    }

    private fun observeState() {
        scope.launch {
            combine(
                controller.state,
                settingsRepository.settings,
            ) { state, settings -> Triple(state, settings.floatingButtonEnabled, settings) }
                .distinctUntilChanged()
                .collect { (state, floatingEnabled, settings) ->
                    syncInternalServer(settings)
                    if (started) refreshNotification(state)
                    overlayController.sync(enabled = floatingEnabled, state = state)
                    PttWidgetUpdater.update(this@PttForegroundService, state)
                }
        }
    }

    /**
     * Starts or stops the on-device relay to match the setting. When hosting, this device's own
     * client still connects over the socket like any other peer.
     */
    private fun syncInternalServer(settings: AppSettings) {
        if (!settings.hostServerEnabled) {
            if (internalServer.isRunning) internalServer.stop()
            return
        }

        val wanted = InternalPttServer.Config(
            port = settings.serverPort,
            accessToken = settings.accessToken.trim(),
        )
        // A port or token change has to reach a relay that is already up: leaving it on the old
        // pair means the setting appears saved while the relay still answers to the old one.
        if (internalServer.isRunning && internalServer.runningConfig != wanted) {
            internalServer.stop()
        }
        if (!internalServer.isRunning) {
            runCatching { internalServer.start(wanted.port, wanted.accessToken) }
                .onFailure { Timber.e(it, "Could not start the embedded relay") }
        }
    }

    /**
     * Updates the ongoing notification.
     *
     * POST_NOTIFICATIONS is revocable from API 33, and the user can also disable the channel,
     * so both are checked — `notify` would otherwise be silently dropped or throw.
     */
    private fun refreshNotification(state: com.github.devapro.pttdroid.domain.PttState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        PttNotifications.ensureChannel(this)
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        runCatching {
            manager.notify(PttNotifications.NOTIFICATION_ID, PttNotifications.build(this, state))
        }.onFailure { Timber.d("Notification update rejected: %s", it.toString()) }
    }

    private fun stopSelfCleanly() {
        controller.stop()
        overlayController.hide()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        internalServer.stop()
        overlayController.hide()
        controller.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
