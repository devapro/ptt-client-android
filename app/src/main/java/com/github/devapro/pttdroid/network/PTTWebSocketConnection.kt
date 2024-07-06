package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.audio.VoicePlayer
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import timber.log.Timber
import java.lang.Exception
import java.net.URI
import java.nio.ByteBuffer

class PTTWebSocketConnection(
    private val voicePlayer: VoicePlayer
) {

    private var socketClient: WebSocketClient = createConnection()

    fun start() {
        if (socketClient.isOpen) {
            return
        }
        if (socketClient.isClosing || socketClient.isClosed) {
            socketClient = createConnection()
        }
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
        return object : WebSocketClient(URI("ws://192.168.100.74:8000/channel/111")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Timber.i("onOpen")
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
                voicePlayer.stopPlay()
            }

            override fun onError(ex: Exception?) {
                Timber.i("onError")
                voicePlayer.stopPlay()
            }

        }
    }
}