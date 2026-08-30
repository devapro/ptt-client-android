package com.github.devapro.pttdroid.domain

import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.network.ConnectionEvent
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.Peers
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
import timber.log.Timber

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
) {
    private val _state = MutableStateFlow(PttState())
    val state: StateFlow<PttState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var eventJob: Job? = null
    private var audioPumpJob: Job? = null

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
            _state.update {
                it.copy(status = ConnectionStatus.Connecting, channel = settings.channel)
            }
            Timber.i("Connecting to %s channel %d", settings.displayUrl(), settings.channel)

            // Suspends until the socket closes, then falls through to backoff.
            connection.connect(settings.endpoint())

            if (!scope.isActive) return
            val delayMs = reconnectPolicy.nextDelayMs()
            Timber.i("Disconnected; retrying in %d ms (attempt %d)", delayMs, reconnectPolicy.attempts)
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
                    _state.update { it.copy(status = ConnectionStatus.Connected, lastError = null) }
                }

                is ConnectionEvent.Disconnected -> {
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

                is ConnectionEvent.Audio -> player.play(event.pcm)

                is ConnectionEvent.Control -> handleControl(event.message)
            }
        }
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
                Timber.i("Joined channel %d as %s (%d peers)", message.channel, message.clientId, message.peers)
            }

            is Floor -> handleFloor(message)

            is Peers -> _state.update { it.copy(peers = message.count) }

            is ProtocolError -> {
                Timber.w("Protocol error %s: %s", message.code, message.message)
                if (message.code == ErrorCodes.FLOOR_BUSY) {
                    // Someone beat us to it; make sure we are not left half-transmitting.
                    stopTransmit()
                    _state.update { it.copy(isRequestingFloor = false, isTransmitting = false) }
                }
                _state.update { it.copy(lastError = message.message) }
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
        recorder.start()
        audioPumpJob = scope.launch {
            for (chunk in recorder.frames) {
                if (!connection.sendAudio(chunk)) break
            }
        }
    }

    private fun stopTransmit() {
        audioPumpJob?.cancel()
        audioPumpJob = null
        recorder.stop()
    }

    /** Releases every native resource. Call from the owning service's `onDestroy`. */
    fun shutdown() {
        stop()
        eventJob?.cancel()
        eventJob = null
        recorder.release()
        player.release()
    }
}
