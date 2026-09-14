package com.github.devapro.pttdroid.domain

import com.github.devapro.pttdroid.PttLog
import com.github.devapro.pttdroid.audio.StartBeep
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import com.github.devapro.pttdroid.network.ConnectionEvent
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.Peers
import com.github.devapro.pttdroid.network.protocol.Ping
import com.github.devapro.pttdroid.network.protocol.Pong
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.TalkRelease
import com.github.devapro.pttdroid.network.protocol.TalkRequest
import com.github.devapro.pttdroid.network.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the PTT session: the socket, the recorder and the player.
 *
 * This used to live in `MainActivity.onStart`/`onStop`, which meant backgrounding the app tore
 * down a transmission in flight. Ownership sits here — application-scoped, driven by the
 * foreground service — so the Activity, the floating overlay and the widget are all just
 * observers of [state] and callers of these methods.
 */
class PttController(
    private val connection: PttConnection,
    private val recorder: VoiceRecorderContract,
    private val player: VoicePlayerContract,
    /**
     * Reads the current settings. A supplier rather than the repository itself, so the domain
     * layer stays free of DataStore (and therefore of an Android Context) and is unit-testable.
     */
    private val settingsProvider: suspend () -> AppSettings,
    private val channelPersister: suspend (Int) -> Unit,
    private val scope: CoroutineScope,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val keepalivePolicy: KeepalivePolicy = KeepalivePolicy(),
) {
    private val _state = MutableStateFlow(PttState())
    val state: StateFlow<PttState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var eventJob: Job? = null
    private var audioPumpJob: Job? = null
    private var keepaliveJob: Job? = null

    /**
     * Frames received from the relay, counted rather than timed.
     *
     * The keepalive only needs to know whether *anything* arrived since it last looked, and a
     * counter answers that without a clock — which keeps the watchdog on virtual time in tests
     * and off the platform's wall clock everywhere else. A [MutableStateFlow] because the
     * writer is the event collector and the reader is the keepalive coroutine.
     */
    private val inboundFrames = MutableStateFlow(0L)

    /** Our own client id, so we can tell our own floor grants from someone else's. */
    private var clientId: String? = null

    fun start() {
        if (sessionJob?.isActive == true) return
        eventJob = eventJob ?: scope.launch { observeEvents() }
        sessionJob = scope.launch { runSessionLoop() }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        stopKeepalive()
        stopTransmit()
        scope.launch { connection.disconnect() }
        player.release()
        _state.update {
            it.copy(
                status = ConnectionStatus.Disconnected,
                isTransmitting = false,
                isRequestingFloor = false,
                isFloorHeldByOther = false,
                floorHolderName = null,
                peers = 0,
            )
        }
    }

    /** Reconnects onto [channel], persisting it first. */
    fun setChannel(channel: Int) {
        val clamped = AppSettings.clampChannel(channel)
        scope.launch {
            channelPersister(clamped)
            _state.update { it.copy(channel = clamped) }
            restart()
        }
    }

    fun restart() {
        stop()
        reconnectPolicy.reset()
        start()
    }

    /**
     * Points playback at the loudspeaker or the handset receiver.
     *
     * Persistence is the reducer's job, not this class's — the same split [setChannel] does not
     * make, because a channel change has to be written *before* the reconnect that reads it
     * back, where this one only has to reach the speaker.
     */
    fun setAudioOutput(output: AudioOutput) {
        player.setOutput(output)
    }

    /** Linear 0..1 gain, applied at once so a slider is audible while it is still moving. */
    fun setPlaybackVolume(volume: Float) {
        player.setVolume(AppSettings.clampVolume(volume))
    }

    /**
     * Asks for the floor. Audio does NOT start here — we wait for the server's `floor` message
     * confirming it is ours, so two people pressing at once cannot both transmit.
     */
    fun requestTalk() {
        val current = _state.value
        if (!current.canTalk || current.isTransmitting || current.isRequestingFloor) return
        _state.update { it.copy(isRequestingFloor = true) }
        scope.launch { connection.send(TalkRequest) }
    }

    fun releaseTalk() {
        val current = _state.value
        if (!current.isTransmitting && !current.isRequestingFloor) return
        stopTransmit()
        _state.update { it.copy(isTransmitting = false, isRequestingFloor = false) }
        scope.launch { connection.send(TalkRelease) }
    }

    fun toggleTalk() {
        if (_state.value.isTransmitting) releaseTalk() else requestTalk()
    }

    /**
     * Drops the last error. It is only ever a record of something that already happened, and
     * nothing else clears it until the next successful connect, so without this a refusal from
     * twenty minutes ago keeps presenting itself as a live fault.
     */
    fun clearError() {
        if (_state.value.lastError == null) return
        _state.update { it.copy(lastError = null) }
    }

    // --- internals ------------------------------------------------------------------------

    private suspend fun runSessionLoop() {
        while (scope.isActive) {
            val settings = settingsProvider()
            // The stored route and level, re-applied on every connect. The player remembers
            // them across its own prepare()/release(), but it is constructed with the defaults
            // and never sees DataStore itself, so this is where a restored preference reaches
            // it — before `welcome` can prepare the track, let alone before any audio arrives.
            player.setOutput(settings.audioOutput)
            player.setVolume(AppSettings.clampVolume(settings.playbackVolume))
            _state.update {
                it.copy(status = ConnectionStatus.Connecting, channel = settings.channel)
            }
            PttLog.i { "Connecting to ${settings.displayUrl()} channel ${settings.channel}" }

            // Suspends until the socket closes, then falls through to backoff.
            connection.connect(settings.endpoint())

            if (!scope.isActive) return
            val delayMs = reconnectPolicy.nextDelayMs()
            PttLog.i { "Disconnected; retrying in $delayMs ms (attempt ${reconnectPolicy.attempts})" }
            _state.update {
                it.copy(status = ConnectionStatus.Disconnected, isTransmitting = false)
            }
            delay(delayMs)
        }
    }

    private suspend fun observeEvents() {
        connection.events.collect { event ->
            when (event) {
                is ConnectionEvent.Connected -> {
                    reconnectPolicy.reset()
                    startKeepalive()
                    _state.update { it.copy(status = ConnectionStatus.Connected, lastError = null) }
                }

                is ConnectionEvent.Disconnected -> {
                    stopKeepalive()
                    stopTransmit()
                    player.release()
                    _state.update {
                        it.copy(
                            status = ConnectionStatus.Disconnected,
                            isTransmitting = false,
                            isRequestingFloor = false,
                            isFloorHeldByOther = false,
                            floorHolderName = null,
                            lastError = event.reason,
                        )
                    }
                }

                is ConnectionEvent.Audio -> {
                    markInbound()
                    player.play(event.pcm)
                }

                is ConnectionEvent.Control -> {
                    markInbound()
                    handleControl(event.message)
                }
            }
        }
    }

    /**
     * Records that the relay is still talking to us. Called on every inbound frame, audio
     * included — an increment on a `MutableStateFlow`, deliberately nothing heavier, because
     * this sits on the 25-frames-a-second receive path.
     */
    private fun markInbound() {
        inboundFrames.update { it + 1 }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch { runKeepalive() }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /**
     * Watches for a socket that has gone quiet, probing before it gives up on one.
     *
     * Silence alone is not a fault — an idle channel is silent — so the run is what counts: one
     * quiet interval earns a [Ping], and only a run of [KeepalivePolicy.maxSilentIntervals] with
     * nothing coming back at all is taken as a dead link. Anything inbound resets it, so a
     * channel with traffic on it is never probed.
     */
    private suspend fun runKeepalive() {
        var lastSeen = inboundFrames.value
        var silentIntervals = 0
        while (true) {
            delay(keepalivePolicy.intervalMs)

            val seen = inboundFrames.value
            if (seen != lastSeen) {
                lastSeen = seen
                silentIntervals = 0
                continue
            }

            silentIntervals++
            if (silentIntervals >= keepalivePolicy.maxSilentIntervals) {
                PttLog.w {
                    "Nothing from the relay in ${keepalivePolicy.timeoutMs} ms — reconnecting"
                }
                onKeepaliveTimeout()
                return
            }
            connection.send(Ping)
        }
    }

    /**
     * Gives up on the current socket and dials again.
     *
     * [restart] cancels this very coroutine on its way through [stop]; that is safe because
     * everything left to run here is non-suspending, and the jobs it starts belong to [scope]
     * rather than to the cancelled caller. The error survives the restart — [stop] does not
     * clear it — and the next successful connect does clear it, so the banner lasts exactly as
     * long as the problem does.
     */
    private fun onKeepaliveTimeout() {
        _state.update { it.copy(lastError = RELAY_SILENT) }
        restart()
    }

    private fun handleControl(message: com.github.devapro.pttdroid.network.protocol.ServerMessage) {
        when (message) {
            is Welcome -> {
                clientId = message.clientId
                player.prepare()
                _state.update {
                    it.copy(
                        status = ConnectionStatus.Connected,
                        channel = message.channel,
                        peers = message.peers,
                    )
                }
                PttLog.i { "Joined channel ${message.channel} as ${message.clientId} (${message.peers} peers)" }
            }

            is Floor -> handleFloor(message)

            is Peers -> _state.update { it.copy(peers = message.count) }

            // Nothing to do with the contents: the keepalive has already counted the frame,
            // and that it arrived at all was the entire point of asking.
            Pong -> Unit

            is ProtocolError -> {
                PttLog.w { "Protocol error ${message.code}: ${message.message}" }
                if (message.code == ErrorCodes.FLOOR_BUSY) {
                    // Someone beat us to it; make sure we are not left half-transmitting.
                    stopTransmit()
                    _state.update { it.copy(isRequestingFloor = false, isTransmitting = false) }
                }
                // A relay older than the keepalive answers `ping` with malformed_message. The
                // answer still proves the link is alive, which is what the probe wanted, and
                // "Unparseable control message" is not something a user can act on — so it
                // stays in the log above rather than going on a banner.
                if (message.code != ErrorCodes.MALFORMED_MESSAGE) {
                    _state.update { it.copy(lastError = message.message) }
                }
            }
        }
    }

    private fun handleFloor(floor: Floor) {
        val heldByUs = floor.isSelf
        val heldBySomeone = floor.holderId != null

        if (heldByUs) {
            // The grant we were waiting for — only now does the microphone open.
            startTransmit()
            _state.update {
                it.copy(
                    isTransmitting = true,
                    isRequestingFloor = false,
                    isFloorHeldByOther = false,
                    floorHolderName = floor.holderName,
                )
            }
        } else {
            stopTransmit()
            _state.update {
                it.copy(
                    isTransmitting = false,
                    isRequestingFloor = false,
                    isFloorHeldByOther = heldBySomeone,
                    floorHolderName = floor.holderName,
                )
            }
        }
    }

    private fun startTransmit() {
        if (audioPumpJob?.isActive == true) return
        audioPumpJob = scope.launch {
            if (!sendStartBeepIfEnabled()) return@launch
            recorder.start()
            for (chunk in recorder.frames) {
                if (!connection.sendAudio(chunk)) break
            }
        }
    }

    /**
     * Local play then the same PCM on the wire, then a wait so the speaker finishes before
     * the microphone opens — otherwise the tone leaks into the capture. Off, or a dead
     * socket, skips all of that and the caller must not start the recorder.
     */
    private suspend fun sendStartBeepIfEnabled(): Boolean {
        if (!settingsProvider().startBeepEnabled) return true
        for (frame in StartBeep.frames) {
            player.play(frame)
            if (!connection.sendAudio(frame.copyOf())) return false
        }
        delay(StartBeep.DURATION_MS.toLong())
        return true
    }

    private fun stopTransmit() {
        audioPumpJob?.cancel()
        audioPumpJob = null
        recorder.stop()
    }

    private companion object {
        /**
         * Shown when the keepalive gives up. Plain words on purpose: the banner carries
         * transport strings verbatim, and the ones the stack produces for this
         * ("sent ping but didn't receive pong within 15000ms") describe the mechanism rather
         * than the situation.
         */
        const val RELAY_SILENT = "The relay stopped responding"
    }

    /** Releases every native resource. Call from the owning service's `onDestroy`. */
    fun shutdown() {
        stop()
        stopKeepalive()
        eventJob?.cancel()
        eventJob = null
        recorder.release()
        player.release()
    }
}
