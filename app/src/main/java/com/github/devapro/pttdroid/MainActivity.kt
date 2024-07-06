package com.github.devapro.pttdroid

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import com.github.devapro.pttdroid.audio.VoicePlayer
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import com.github.devapro.pttdroid.permission.Permission
import com.github.devapro.pttdroid.permission.UtilPermission
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.koin.android.ext.android.inject
import com.github.devapro.pttdroid.audio.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    private val socketConnection: PTTWebSocketConnection by inject()
    private val utilPermission: UtilPermission by inject()
    private val voicePlayer: VoicePlayer by inject()
    private val voiceRecorder: VoiceRecorder by inject()
    private val coroutineContextProvider: CoroutineContextProvider by inject()

    private var scope: CoroutineScope? = null

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PTTdroidTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInteropFilter {
                                when (it.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        voiceRecorder.startRecord()
                                    }

                                    MotionEvent.ACTION_UP -> {
                                        voiceRecorder.stopRecord()
                                    }

                                    else -> false
                                }
                                true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "Send")
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

        voicePlayer.create()
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
        voicePlayer.stopPlay()
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