package com.github.devapro.pttdroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.devapro.pttdroid.model.MainAction

@Composable
fun ChanelNumber(
    currentChanel: Int,
    onAction: (MainAction) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable {
                    onAction(MainAction.SetChannel(currentChanel - 1))
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "-",
                fontSize = 45.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = currentChanel.toString(),
                fontSize = 35.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable {
                    onAction(MainAction.SetChannel(currentChanel + 1))
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                fontSize = 45.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Preview
@Composable
fun PreviewChanelNumber() {
    ChanelNumber(
        currentChanel = 1,
        onAction = {}
    )
}