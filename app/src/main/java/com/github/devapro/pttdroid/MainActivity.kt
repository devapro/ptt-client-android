package com.github.devapro.pttdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import com.github.devapro.pttdroid.permission.Permission
import com.github.devapro.pttdroid.permission.UtilPermission
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.koin.android.ext.android.inject
import com.github.devapro.pttdroid.audio.VoiceRecorder
import com.github.devapro.pttdroid.ui.components.PTTButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val socketConnection: PTTWebSocketConnection by inject()
    private val utilPermission: UtilPermission by inject()
    private val voiceRecorder: VoiceRecorder by inject()
    private val coroutineContextProvider: CoroutineContextProvider by inject()

    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PTTdroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PTTButton(
                            onStart = {
                                voiceRecorder.startRecord()
                            },
                            onStop = {
                                voiceRecorder.stopRecord()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        utilPermission.checkOrRequestPermissions(this, object : UtilPermission.PermissionCallback(
            arrayOf(Permission.AUDIO_RECORD)
        ) {
            override fun onSuccessGrantedAll() {
                startVoiceRecorder()
            }
        })

        socketConnection.start()

        scope = coroutineContextProvider.createScope(
            coroutineContextProvider.io
        )
        scope?.launch {
            voiceRecorder.audioDataChannel.consumeEach { data ->
                socketConnection.send(data)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        socketConnection.stop()
        voiceRecorder.stopRecord()
        scope?.cancel()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        utilPermission.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startVoiceRecorder() {
        voiceRecorder.create()
    }
}