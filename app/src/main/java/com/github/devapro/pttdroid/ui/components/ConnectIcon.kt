package com.github.devapro.pttdroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConnectIcon(
    isConnected: Boolean
) {
    Box(
        modifier = Modifier.size(24.dp)
            .background(
                color = if (isConnected) {
                    androidx.compose.ui.graphics.Color.Green
                } else {
                    androidx.compose.ui.graphics.Color.Red
                },
                shape = CircleShape
            )
    )
}