package com.github.devapro.pttdroid.network

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.lang.Exception
import java.net.URI
import java.nio.ByteBuffer

class PTTWebSocketListener(serverUri: URI, private val onData: (String?) -> Unit) : WebSocketClient(serverUri){
    override fun onOpen(handshakedata: ServerHandshake?) {
        onData("Connected")
    }

    override fun onMessage(bytes: ByteBuffer?) {
        super.onMessage(bytes)
        onData("Message")
    }

    override fun onMessage(message: String?) {
        onData(message)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        onData("Closed")
    }

    override fun onError(ex: Exception?) {
        onData("Error")
    }
}