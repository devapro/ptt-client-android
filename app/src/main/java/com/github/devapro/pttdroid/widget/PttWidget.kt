package com.github.devapro.pttdroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.domain.PttState
import com.github.devapro.pttdroid.service.PttServiceCommands
import com.github.devapro.pttdroid.ui.PttUiStatus
import org.koin.core.context.GlobalContext

/**
 * Home-screen widget: status plus a transmit toggle, so the user can talk without opening
 * the app.
 *
 * Note this is a TOGGLE, not press-and-hold. RemoteViews (and therefore Glance) only deliver
 * discrete click events — there is no touch-down/touch-up — so true hold-to-talk is not
 * expressible in a widget. Hold-to-talk lives in the app and in the floating bubble.
 *
 * The container follows the launcher's own theme, because a widget that ignores the home screen
 * looks broken; only the transmit key is painted from [PttUiStatus], because that colour is the
 * one thing that has to mean the same here as it does everywhere else.
 */
class PttWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Seed from the live session state. Without this, a newly added widget renders the
        // default (Offline) values until the next state CHANGE happens to be pushed.
        runCatching {
            val controller = GlobalContext.get().get<PttController>()
            writeState(context, id, controller.state.value)
        }
        provideContent { WidgetContent() }
    }

    @Composable
    private fun WidgetContent() {
        val context = LocalContext.current
        val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
        val status = prefs[KEY_STATUS]
            ?.let { name -> runCatching { PttUiStatus.valueOf(name) }.getOrNull() }
            ?: PttUiStatus.OFFLINE
        val channel = prefs[KEY_CHANNEL] ?: 1
        val holder = prefs[KEY_HOLDER]?.takeIf { it.isNotEmpty() }
        val peers = prefs[KEY_PEERS] ?: 0

        val statusLine = when (status) {
            PttUiStatus.RECEIVING -> holder
                ?.let { context.getString(R.string.status_receiving_from, it) }
                ?: context.getString(R.string.status_receiving)

            PttUiStatus.OFFLINE, PttUiStatus.CONNECTING -> context.getString(status.labelRes)
            else -> context.resources.getQuantityString(R.plurals.peers_online, peers, peers)
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(10.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = context.getString(R.string.main_channel_number, channel),
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
            )
            Text(
                text = statusLine,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )

            Spacer(modifier = GlanceModifier.height(8.dp))

            Text(
                text = context.getString(
                    if (status == PttUiStatus.TRANSMITTING) {
                        R.string.action_stop_talking
                    } else {
                        R.string.action_start_talking
                    },
                ).uppercase(),
                style = TextStyle(
                    color = ColorProvider(ON_SIGNAL),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(ColorProvider(Color(status.argb)))
                    .cornerRadius(18.dp)
                    .padding(vertical = 10.dp)
                    .clickable(
                        actionRunCallback<PttWidgetAction>(
                            actionParametersOf(
                                ACTION_KEY to PttServiceCommands.ACTION_TOGGLE_TALK,
                            ),
                        ),
                    ),
            )

            Spacer(modifier = GlanceModifier.height(6.dp))

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                ChannelButton("−", channel - 1, GlanceModifier.defaultWeight())
                Spacer(modifier = GlanceModifier.width(6.dp))
                ChannelButton("+", channel + 1, GlanceModifier.defaultWeight())
            }
        }
    }

    @Composable
    private fun ChannelButton(label: String, target: Int, modifier: GlanceModifier) {
        Text(
            text = label,
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                color = GlanceTheme.colors.onSurface,
                textAlign = TextAlign.Center,
            ),
            modifier = modifier
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(12.dp)
                .padding(vertical = 6.dp)
                .clickable(
                    actionRunCallback<PttWidgetAction>(
                        actionParametersOf(
                            ACTION_KEY to PttServiceCommands.ACTION_SET_CHANNEL,
                            CHANNEL_KEY to AppSettings.clampChannel(target),
                        ),
                    ),
                ),
        )
    }

    companion object {
        /** Matches `OnSignal` in the app's PTT button — the accents are all mid tones. */
        private val ON_SIGNAL = Color(0xFF08111A)

        val KEY_STATUS = stringPreferencesKey("status")
        val KEY_CHANNEL = intPreferencesKey("channel")
        val KEY_PEERS = intPreferencesKey("peers")
        val KEY_HOLDER = stringPreferencesKey("holder")

        val ACTION_KEY = ActionParameters.Key<String>("ptt_action")
        val CHANNEL_KEY = ActionParameters.Key<Int>("ptt_channel")
    }
}

/**
 * Runs on a widget tap. A widget tap is one of the user gestures Android accepts as a reason
 * to start a microphone foreground service from outside an Activity.
 */
class PttWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val action = parameters[PttWidget.ACTION_KEY] ?: PttServiceCommands.ACTION_START
        val intent = PttServiceCommands.intent(context, action)
        parameters[PttWidget.CHANNEL_KEY]?.let { channel ->
            intent.putExtra(PttServiceCommands.EXTRA_CHANNEL, channel)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
}

class PttWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PttWidget()
}

/** Writes one widget's Glance state from [state]. */
internal suspend fun writeState(context: Context, id: GlanceId, state: PttState) {
    updateAppWidgetState(context, id) { prefs ->
        prefs[PttWidget.KEY_STATUS] = PttUiStatus.of(state).name
        prefs[PttWidget.KEY_CHANNEL] = state.channel
        prefs[PttWidget.KEY_PEERS] = state.peers
        prefs[PttWidget.KEY_HOLDER] = state.floorHolderName ?: ""
    }
}

/** Pushes [PttState] into every placed widget. Called by the foreground service. */
object PttWidgetUpdater {
    suspend fun update(context: Context, state: PttState) {
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        val ids = runCatching { manager.getGlanceIds(PttWidget::class.java) }.getOrNull().orEmpty()
        if (ids.isEmpty()) return

        for (id in ids) writeState(context, id, state)
        PttWidget().updateAll(context)
    }
}
