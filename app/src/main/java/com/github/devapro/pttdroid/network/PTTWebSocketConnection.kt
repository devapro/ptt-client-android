package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.CoroutineContextProvider
import com.github.devapro.pttdroid.audio.VoicePlayer
import com.github.devapro.pttdroid.model.MainAction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import timber.log.Timber
import java.lang.Exception
import java.net.URI
import java.nio.ByteBuffer

class PTTWebSocketConnection(
    private val voicePlayer: VoicePlayer,
    private val coroutineContextProvider: CoroutineContextProvider
) {

    private var socketClient: WebSocketClient = createConnection()
    private val scope = coroutineContextProvider.createScope(
        coroutineContextProvider.io
    )

    val actions = MutableSharedFlow<MainAction>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun start() {
        if (socketClient.isOpen) {
            scope.launch {
                actions.emit(MainAction.Connected)
            }
            return
        }
        if (socketClient.isClosing || socketClient.isClosed) {
            socketClient = createConnection()
        }
        socketClient.connect()
    }

    fun reconnect() {
        if (socketClient.isOpen) {
            scope.launch {
                actions.emit(MainAction.Connected)
            }
            return
        }
        socketClient.close()
        socketClient = createConnection()
        socketClient.connect()
    }

    fun stop() {
        if (socketClient.isClosing || socketClient.isClosed) {
            return
        }
        socketClient.close()
    }

    fun send(message: String) {
        if (socketClient.isOpen.not()) {
            socketClient = createConnection()
            socketClient.connect()
            return
        }
        socketClient.send(message)
    }

    fun send(message: ByteArray) {
        if (socketClient.isOpen.not()) {
            socketClient = createConnection()
            socketClient.connect()
            return
        }
        socketClient.send(message)
    }

    private fun createConnection(): WebSocketClient {
        return object : WebSocketClient(URI("ws://192.168.100.4:8000/channel/111")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Timber.i("onOpen")
                scope.launch {
                    actions.emit(MainAction.Connected)
                }
                voicePlayer.create()
            }

            override fun onMessage(message: String?) {

            }

            override fun onMessage(bytes: ByteBuffer?) {
                Timber.i("onMessage")
                voicePlayer.play(bytes?.array() ?: byteArrayOf())
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Timber.i("onClose")
                scope.launch {
                    actions.emit(MainAction.Reconnect)
                }
                voicePlayer.stopPlay()
            }

            override fun onError(ex: Exception?) {
                Timber.i("onError")
//                actions.trySend(MainAction.Reconnect)
//                voicePlayer.stopPlay()
            }
        }
    }
}