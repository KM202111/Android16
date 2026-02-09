package com.example.channelsounding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ChannelSoundingScreen(
    status: String,
    connectionState: String,
    distance: String,
    onStart: () -> Unit,
    onRateNormal: () -> Unit,
    onRateFrequent: () -> Unit,
    onRateInfrequent: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(text = "Connection: $connectionState", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Row(){
            Button(onClick = onStart) {
                Text(text = "Bluetooth Scan Start")
            }
            Spacer(modifier = Modifier.padding(8.dp))
            Button(onClick = onRateFrequent) {
                Text(text = "CS Ranging Start")
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = distance,
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )
    }
}
