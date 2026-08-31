package com.github.devapro.pttdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.github.devapro.pttdroid.MainActivity
import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.domain.PttState
import com.github.devapro.pttdroid.shared.resources.*
import com.github.devapro.pttdroid.ui.PttUiStatus
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Builds the foreground-service notification and its actions.
 *
 * [ensureChannel] and [build] are `suspend`: their strings are now Compose Multiplatform
 * [org.jetbrains.compose.resources.StringResource]s, and the only non-composable accessor,
 * `getString`/`getPluralString`, suspends. Callers run them from a coroutine already — see
 * `PttForegroundService` — rather than blocking the service's main thread.
 */
object PttNotifications {

    const val CHANNEL_ID = "ptt_session"
    const val NOTIFICATION_ID = 1001

    suspend fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(Res.string.notification_channel_name),
            // LOW: this notification is a persistent status surface, not an alert.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(Res.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    suspend fun build(context: Context, state: PttState): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = getString(Res.string.notification_title, state.channel)
        // Same vocabulary as the app screen and the widget — this notification is often the
        // only visible surface, and it should not invent its own wording for the same state.
        val status = PttUiStatus.of(state)
        val text = when (status) {
            PttUiStatus.RECEIVING -> getString(
                Res.string.status_receiving_from,
                state.floorHolderName ?: getString(Res.string.someone),
            )

            PttUiStatus.READY -> getPluralString(
                Res.plurals.peers_online,
                state.peers,
                state.peers,
            )

            else -> getString(status.labelRes)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // A notification action is a discrete tap, so transmit is a toggle here — the same
        // constraint as the widget. Press-and-hold only works in the app and the overlay.
        if (state.isConnected) {
            val toggleLabel = if (state.isTransmitting) {
                Res.string.action_stop_talking
            } else {
                Res.string.action_start_talking
            }
            builder.addAction(
                0,
                getString(toggleLabel),
                PttServiceCommands.pendingIntent(context, PttServiceCommands.ACTION_TOGGLE_TALK),
            )
        }
        builder.addAction(
            0,
            getString(Res.string.action_stop_service),
            PttServiceCommands.pendingIntent(context, PttServiceCommands.ACTION_STOP),
        )

        return builder.build()
    }
}
