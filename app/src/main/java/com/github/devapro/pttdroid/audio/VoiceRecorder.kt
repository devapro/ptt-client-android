package com.github.devapro.pttdroid.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Microphone capture, emitting fixed-size PCM frames on [frames].
 *
 * Lifecycle is owned by `PttController`, not by an Activity: the old class allocated a fresh
 * `AudioRecord` on every `create()` without releasing the previous one, never had `destroy()`
 * called at all, and spawned a brand-new unmanaged `CoroutineScope` on every PTT press.
 */
class VoiceRecorder(private val scope: CoroutineScope) : VoiceRecorderContract {

    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null

    /**
     * Capture is realtime: if the network cannot keep up, dropping the oldest frame is better
     * than growing an unbounded backlog of stale audio.
     */
    override val frames: Channel<ByteArray> = Channel(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @SuppressLint("MissingPermission")
    private fun ensureRecord(): AudioRecord? {
        audioRecord?.let { return it }

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AndroidAudioFormat.IN_CHANNEL_MASK,
            AndroidAudioFormat.PCM_ENCODING,
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Timber.e("Microphone does not support %d Hz mono PCM16", AudioConfig.SAMPLE_RATE_HZ)
            return null
        }

        // Several frames of slack so a scheduling hiccup does not overrun the hardware buffer.
        val bufferSize = maxOf(minBuffer, AudioConfig.FRAME_BYTES * 4)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE_HZ,
                AndroidAudioFormat.IN_CHANNEL_MASK,
                AndroidAudioFormat.PCM_ENCODING,
                bufferSize,
            )
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Could not create AudioRecord")
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord failed to initialise (state=%d)", record.state)
            record.release()
            return null
        }

        Timber.i("AudioRecord ready: %d Hz, buffer %d bytes", AudioConfig.SAMPLE_RATE_HZ, bufferSize)
        audioRecord = record
        return record
    }

    override fun start() {
        if (readJob?.isActive == true) return
        val record = ensureRecord() ?: return

        runCatching { record.startRecording() }
            .onFailure { Timber.e(it, "startRecording failed"); return }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Timber.e("AudioRecord did not enter RECORDING state")
            return
        }

        readJob = scope.launch {
            // Reads go through the frame loop so every emitted frame is exactly
            // AudioConfig.FRAME_BYTES: a short or interrupted read carries its remainder to the
            // next step instead of putting a partial — possibly odd-length, which the protocol
            // rejects outright — frame on the wire, as desktop and iOS capture already do.
            val loop = CaptureFrameLoop(
                source = PcmReadSource { buffer -> record.read(buffer, 0, buffer.size) },
                emit = { frame -> frames.trySend(frame) },
            )
            while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                // The read inside step() blocks until the buffer fills, so this loop neither
                // busy-spins nor needs a suspend call to yield; cancellation lands as soon as
                // a read returns.
                val read = loop.step()
                if (read < 0) {
                    Timber.e("AudioRecord.read error %d", read)
                    break
                }
            }
        }
    }

    override fun stop() {
        readJob?.cancel()
        readJob = null
        val record = audioRecord ?: return
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.stop() }
                .onFailure { Timber.d("AudioRecord.stop failed: %s", it.toString()) }
        }
    }

    /** Idempotent; safe to call more than once. */
    override fun release() {
        stop()
        audioRecord?.let { record ->
            runCatching { record.release() }
                .onFailure { Timber.d("AudioRecord.release failed: %s", it.toString()) }
        }
        audioRecord = null
    }
}
