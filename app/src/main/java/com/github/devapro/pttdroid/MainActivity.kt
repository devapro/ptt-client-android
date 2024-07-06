package com.github.devapro.pttdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import com.github.devapro.pttdroid.permission.Permission
import com.github.devapro.pttdroid.permission.UtilPermission
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.koin.android.ext.android.inject
import com.github.devapro.pttdroid.audio.VoiceRecorder
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.ui.components.ChanelNumber
import com.github.devapro.pttdroid.ui.components.ConnectIcon
import com.github.devapro.pttdroid.ui.components.PTTButton
import com.github.devapro.pttdroid.viewmodel.MainActivityViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val socketConnection: PTTWebSocketConnection by inject()
    private val utilPermission: UtilPermission by inject()
    private val voiceRecorder: VoiceRecorder by inject()
    private val coroutineContextProvider: CoroutineContextProvider by inject()

    private var scope: CoroutineScope? = null

    private val viewModel: MainActivityViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val screenState = viewModel.state.collectAsState()
            PTTdroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ChanelNumber()
                        ConnectIcon(isConnected = screenState.value is ScreenState.Connected)
                        (screenState.value as? ScreenState.Connected)?.let {
                            PTTButton(
                                isPressed = it.isSpeaking,
                                onStart = {
                                    viewModel.onAction(MainAction.Speak)
                                },
                                onStop = {
                                    viewModel.onAction(MainAction.StopSpeak)
                                }
                            )
                        }
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

        viewModel.onAction(MainAction.InitConnection)

        scope = coroutineContextProvider.createScope(
            coroutineContextProvider.io
        )
        scope?.launch {
            voiceRecorder.audioDataChannel.consumeEach { data ->
                socketConnection.send(data)
            }
        }
        scope?.launch {
            socketConnection.actions.consumeEach { action ->
                viewModel.onAction(action)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        scope?.cancel()
        viewModel.onAction(MainAction.Disconnect)
        viewModel.onAction(MainAction.StopSpeak)
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