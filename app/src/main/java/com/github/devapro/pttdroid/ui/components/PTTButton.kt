package com.github.devapro.pttdroid.ui.components

import android.view.MotionEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PTTButton(
    onStart: () -> Unit = {},
    onStop: () -> Unit = {}
) {
    val staticPadding = 0.dp
    val isPressed = mutableStateOf(false)

    val infiniteTransition = rememberInfiniteTransition(label = "PTT")
    val padding: Dp by infiniteTransition.animateValue(
        initialValue = 32.dp,
        targetValue = 0.dp ,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        typeConverter = Dp.VectorConverter,
        label = "PTT"
    )


    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter {
                when (it.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed.value = true
                        onStart()
                    }

                    MotionEvent.ACTION_UP -> {
                        isPressed.value = false
                        onStop()
                    }

                    else -> false
                }
                true
            }
    ) {
        Box(
            modifier = Modifier.background(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            )
                .aspectRatio(1f)
                .padding(padding)
                .height(IntrinsicSize.Min)
                .width(IntrinsicSize.Min),
            contentAlignment = Alignment.Center
        ){
            Box(
                modifier = Modifier.background(
                    color = MaterialTheme.colorScheme.inversePrimary,
                    shape = CircleShape
                )
                    .aspectRatio(1f)
                    .padding(padding)
                    .height(IntrinsicSize.Min)
                    .width(IntrinsicSize.Min),
                contentAlignment = Alignment.Center
            ){
                Box(
                    modifier = Modifier.background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                        .aspectRatio(1f)
                        .padding(16.dp)
                        .height(IntrinsicSize.Min)
                        .width(IntrinsicSize.Min),
                    contentAlignment = Alignment.Center
                ){
                    Text(
                        text = "PTT",
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun PTTButtonPreview() {
    PTTButton()
}