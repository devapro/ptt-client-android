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

    private var socketClient: WebSocketClient? = null
    private var activeChannelNumber = -1
    private val scope = coroutineContextProvider.createScope(
        coroutineContextProvider.io
    )

    val actions = MutableSharedFlow<MainAction>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun start(channelNumber: Int) {
        if (socketClient?.isOpen == true) {
            scope.launch {
                actions.emit(MainAction.Connected)
            }
            return
        }
        if (
            socketClient == null
            || socketClient?.isClosing == true
            || socketClient?.isClosed == true
            ) {
            activeChannelNumber = channelNumber
            socketClient = createConnection(channelNumber)
        }
        socketClient?.connect()
    }

    fun reconnect(channelNumber: Int) {
        if (socketClient?.isOpen == true && activeChannelNumber == channelNumber) {
            scope.launch {
                actions.emit(MainAction.Connected)
            }
            return
        }
        activeChannelNumber = channelNumber
        socketClient?.close()
        socketClient = createConnection(channelNumber)
        socketClient?.connect()
    }

    fun stop() {
        if (socketClient?.isClosing == true || socketClient?.isClosed == true) {
            return
        }
        socketClient?.close()
    }

    fun send(message: ByteArray) {
        if (socketClient?.isOpen?.not() == true) {
            socketClient = createConnection(activeChannelNumber)
            socketClient?.connect()
            return
        }
        socketClient?.send(message)
    }

    private fun createConnection(channelNumber: Int): WebSocketClient {
        return object : WebSocketClient(URI("ws://192.168.100.4:8000/channel/$channelNumber")) {
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