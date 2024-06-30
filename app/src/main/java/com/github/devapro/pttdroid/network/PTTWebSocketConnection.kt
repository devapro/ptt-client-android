package com.github.devapro.pttdroid.network

import java.net.URI

class PTTWebSocketConnection {

    private var socketClient: PTTWebSocketListener = createConnection()

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

    private fun createConnection(): PTTWebSocketListener {
        return PTTWebSocketListener(URI("ws://192.168.100.4:8000/echo")) {
            println(it)
        }
    }
}