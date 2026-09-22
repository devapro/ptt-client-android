package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.audio.AudioConfig
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads a stored record back through the live [VoicePlayerContract].
 *
 * Reusing the player is what makes history obey the speaker/earpiece and volume preferences for
 * free — but it also means a playback pump shares the one AudioTrack with incoming live audio,
 * so the owner (`PttController`) must call [stop] before anything real can be heard; it does.
 * Frames are paced at wire rate ([FRAME_INTERVAL_MS] per [AudioConfig.FRAME_BYTES]) because the
 * track's writes are non-blocking — a burst would simply be dropped.
 *
 * [playingId] reports the record id on start and null on stop or natural end, so the owner
 * (`PttController`) can mirror it into `PttState` next to everything else the UI watches.
 */
class HistoryPlayback(
    private val player: VoicePlayerContract,
    private val store: HistoryStore,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    fun play(record: VoiceRecord) {
        job?.cancel()
        job = scope.launch {
            if (!isActive) return@launch
            _playingId.value = record.id
            val wav = store.wavOf(record)
            val pcm = wav?.let { Wav.pcmFromWav(it) }
            if (pcm == null || pcm.isEmpty() || !player.prepare()) {
                if (isActive) _playingId.value = null
                return@launch
            }
            var offset = 0
            while (isActive && offset < pcm.size) {
                player.play(pcm.copyOfRange(offset, minOf(offset + AudioConfig.FRAME_BYTES, pcm.size)))
                offset += AudioConfig.FRAME_BYTES
                if (offset < pcm.size) delay(FRAME_INTERVAL_MS)
            }
            if (isActive) _playingId.value = null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _playingId.value = null
    }

    /** True while a pump is running — the interrupt checks in `PttController` read this. */
    val isPlaying: Boolean get() = job?.isActive == true

    private companion object {
        /** 40 ms, the interval [AudioConfig.FRAME_BYTES] of audio at the wire rate represents. */
        const val FRAME_INTERVAL_MS = 40L
    }
}
