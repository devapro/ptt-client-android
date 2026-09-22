package com.github.devapro.pttdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.history.VoiceRecord
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.shared.resources.Res
import com.github.devapro.pttdroid.shared.resources.cd_back
import com.github.devapro.pttdroid.shared.resources.cd_delete_record
import com.github.devapro.pttdroid.shared.resources.cd_play_record
import com.github.devapro.pttdroid.shared.resources.cd_stop_record
import com.github.devapro.pttdroid.shared.resources.ic_arrow_back
import com.github.devapro.pttdroid.shared.resources.ic_delete
import com.github.devapro.pttdroid.shared.resources.ic_play
import com.github.devapro.pttdroid.shared.resources.ic_stop
import com.github.devapro.pttdroid.shared.resources.history
import com.github.devapro.pttdroid.shared.resources.history_empty
import com.github.devapro.pttdroid.shared.resources.history_unknown_sender
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Records received while the app was in the background, newest first.
 *
 * The whole screen is a list of `VoiceRecord`s mirrored into [ScreenState] — it reads nothing
 * from repositories and writes nothing to them; every tap goes out as a [MainAction] like
 * everywhere else in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: ScreenState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.history)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = { onAction(MainAction.CloseHistory) }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.history.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(Res.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val today = rememberToday()
            val timeZone = TimeZone.currentSystemDefault()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(state.history, key = { it.id }) { record ->
                    HistoryRow(
                        record = record,
                        isPlaying = state.ptt.historyPlayingId == record.id,
                        stamp = formatStamp(record, timeZone, today),
                        onAction = onAction,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    record: VoiceRecord,
    isPlaying: Boolean,
    stamp: String,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                onAction(
                    if (isPlaying) MainAction.StopRecordPlayback
                    else MainAction.PlayRecord(record.id),
                )
            },
        ) {
            Icon(
                painter = painterResource(if (isPlaying) Res.drawable.ic_stop else Res.drawable.ic_play),
                contentDescription = stringResource(
                    if (isPlaying) Res.string.cd_stop_record else Res.string.cd_play_record,
                ),
                tint = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.senderName ?: stringResource(Res.string.history_unknown_sender),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$stamp · ${formatDuration(record.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onAction(MainAction.DeleteRecord(record.id)) }) {
            Icon(
                painter = painterResource(Res.drawable.ic_delete),
                contentDescription = stringResource(Res.string.cd_delete_record),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}

private fun rememberToday(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

/** Today → "14:05"; this year → "22.09 14:05"; older → "03.05.2024 14:05". */
private fun formatStamp(record: VoiceRecord, timeZone: TimeZone, today: LocalDate): String {
    val moment = Instant.fromEpochMilliseconds(record.startedAtMs)
        .toLocalDateTime(timeZone)
    val time = "%02d:%02d".format(moment.hour, moment.minute)
    val date = moment.date
    return when {
        date == today -> time
        date.year == today.year -> "%02d.%02d %s".format(date.monthNumber, date.dayOfMonth, time)
        else -> "%02d.%02d.%04d %s".format(date.monthNumber, date.dayOfMonth, date.year, time)
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
