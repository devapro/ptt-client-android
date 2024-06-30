package com.github.devapro.pttdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.github.devapro.pttdroid.audio.VoicePlayer
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import com.github.devapro.pttdroid.permission.Permission
import com.github.devapro.pttdroid.permission.UtilPermission
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.koin.android.ext.android.inject
import com.github.devapro.pttdroid.audio.VoiceRecorder
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    private val socketConnection: PTTWebSocketConnection by inject()
    private val utilPermission: UtilPermission by inject()
    private val voicePlayer: VoicePlayer by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PTTdroidTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Button(onClick = {
                        socketConnection.send("Hello")
                    }){
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
    }

    override fun onStop() {
        super.onStop()
        socketConnection.stop()
        voicePlayer.stopPlay()
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
        val voiceRecorder = VoiceRecorder() {
//            (application as WalkieTalkieApp).chanelController.sendMessage(ByteBuffer.wrap(it))
        }
        voiceRecorder.create()

//        viewBinding.ppt.pushStateSubject.subscribe {
//            if (it) {
//                voiceRecorder.startRecord()
//            } else {
//                voiceRecorder.stopRecord()
//            }
//        }.also {
//            compositeDisposable.add(it)
//        }
    }
}