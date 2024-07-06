package com.github.devapro.pttdroid.model

interface MainAction {

    data object InitConnection : MainAction
    data object Connected : MainAction
    data object Disconnect : MainAction
    data object Reconnect : MainAction
    data object Speak : MainAction
    data object StopSpeak : MainAction
}