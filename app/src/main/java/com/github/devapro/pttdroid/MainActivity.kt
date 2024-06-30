package com.github.devapro.pttdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.devapro.pttdroid.network.PTTWebSocketConnection
import com.github.devapro.pttdroid.network.PTTWebSocketListener
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import org.koin.android.ext.android.inject
import java.net.URI

class MainActivity : ComponentActivity() {

    private val socketConnection: PTTWebSocketConnection by inject()

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
        socketConnection.start()
    }

    override fun onStop() {
        super.onStop()
        socketConnection.stop()
    }
}