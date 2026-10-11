package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.PttLog
import com.github.devapro.pttdroid.audio.AudioConfig
import com.github.devapro.pttdroid.domain.ForegroundProvider
import com.github.devapro.pttdroid.domain.UtcClock
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Records what the speaker is playing while the app is in the background, so it can be replayed
 * from the history screen later.
 *
 * The floor state is the whole boundary: a remote holder starts a record, releasing (or the
 * session ending) closes one. Foreground-ness is sampled once, at the start — a record that
 * began in the background is finished and saved even if the user opens the app while it is
 * still coming in, because the beginning of it was already missed.
 *
 * Frames are buffered in memory rather than streamed to disk: the cap below bounds the buffer at
 * a few megabytes, and it keeps the whole capture free of file-IO failure modes on the 25 Hz
 * receive path. All the writing happens once, when the record closes.
 */
class BackgroundRecordCapture(
    private val store: HistoryStore,
    private val clock: UtcClock,
    private val isForeground: ForegroundProvider,
    private val scope: CoroutineScope,
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
) {
    private class InProgress(
        val holderId: String,
        var senderName: String?,
        val startedAtMs: Long,
        val frames: MutableList<ByteArray> = mutableListOf(),
    )

    private var inProgress: InProgress? = null

    /**
     * Forward every `floor` message. The capture decides by itself whether one starts, re-names
     * or ends a record, so the controller keeps only a single call in its floor handling.
     */
    fun onFloor(holderId: String?, holderName: String?, isSelf: Boolean) {
        val remoteSpeaker = if (isSelf || holderId == null) null else holderId
        if (remoteSpeaker == null) {
            finish()
            return
        }
        val active = inProgress
        if (active != null && active.holderId == remoteSpeaker) {
            // A re-announcement of the same holder (e.g. with a name that arrived late) — not a
            // new transmission.
            if (active.senderName == null) active.senderName = holderName
            return
        }
        // The previous speaker's hold ended without a release, or the floor switched hands
        // directly: close the old record before opening the new one.
        finish()
        if (isForeground.isForeground()) return
        inProgress = InProgress(remoteSpeaker, holderName, clock.nowMs())
    }

    /** Forward every inbound audio frame; cheap no-op whenever nothing is being captured. */
    fun onAudioFrame(pcm: ByteArray) {
        val active = inProgress ?: return
        active.frames += pcm
        if (active.frames.size >= maxFrames) {
            // The cap is a runaway guard, not an edit: the record is kept, it just ends here.
            // Playback keeps hearing the rest of the transmission live.
            finish()
        }
    }

    /** The socket went down mid-record: keep what arrived (spec: partials are saved). */
    fun onSessionClosed() = finish()

    /**
     * We were answered: the user took the floor within the answer window, which means the
     * message(s) were not merely stored but actually responded to, so they leave the history.
     */
    fun onAnsweredBySelf() {
        scope.launch {
            val removed = store.deleteEndedAfter(clock.nowMs() - ANSWER_WINDOW_MS)
            if (removed.isNotEmpty()) PttLog.i { "Answered; dropped ${removed.size} history record(s)" }
        }
    }

    private fun finish() {
        val active = inProgress ?: return
        inProgress = null
        if (active.frames.isEmpty()) return

        val pcm = ByteArray(active.frames.sumOf { it.size })
        var offset = 0
        for (frame in active.frames) {
            frame.copyInto(pcm, offset)
            offset += frame.size
        }
        val endedAtMs = clock.nowMs()
        val bytesPerSecond =
            (AudioConfig.SAMPLE_RATE_HZ * AudioConfig.CHANNEL_COUNT * AudioConfig.BYTES_PER_SAMPLE).toLong()
        val id = "rec-${active.startedAtMs}-${Random.nextInt(0x7FFFFFFF).toString(36)}"
        val record = VoiceRecord(
            id = id,
            fileName = "$id.wav",
            startedAtMs = active.startedAtMs,
            endedAtMs = endedAtMs,
            durationMs = pcm.size * 1000L / bytesPerSecond,
            sizeBytes = pcm.size.toLong(),
            senderName = active.senderName,
        )
        scope.launch {
            if (!store.addRecord(record, Wav.toWav(pcm))) {
                PttLog.w { "Could not write history record ${record.id}; dropped" }
            }
        }
    }

    companion object {
        /** A record longer than this is capped and closed — nobody's "voicemail" is 2 minutes. */
        const val MAX_RECORDING_MS = 120_000L

        /** [MAX_RECORDING_MS] expressed in whole wire frames, at [AudioConfig.FRAME_BYTES] each. */
        val DEFAULT_MAX_FRAMES: Int =
            (MAX_RECORDING_MS * AudioConfig.SAMPLE_RATE_HZ * AudioConfig.BYTES_PER_SAMPLE / 1000L /
                AudioConfig.FRAME_BYTES).toInt()

        /** How long after a message an answer still deletes it. */
        const val ANSWER_WINDOW_MS = 30_000L
    }
}
