package com.clipsync.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clipsync.android.R
import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.HealthTone
import com.clipsync.android.ui.HealthValue

@Composable
fun CapabilityStatusCards(
    state: HealthScreenState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapabilityCard(title = stringResource(R.string.capability_network), value = state.network)
        CapabilityCard(title = stringResource(R.string.capability_service), value = state.service)
        CapabilityCard(title = stringResource(R.string.capability_read), value = state.read)
        CapabilityCard(title = stringResource(R.string.capability_write), value = state.write)
    }
}

@Composable
private fun CapabilityCard(
    title: String,
    value: HealthValue,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = value.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(value.tone.color(), CircleShape),
            )
        }
    }
}

@Composable
private fun HealthTone.color(): Color = when (this) {
    HealthTone.GOOD -> MaterialTheme.colorScheme.primary
    HealthTone.NEUTRAL -> MaterialTheme.colorScheme.outline
    HealthTone.WARNING -> MaterialTheme.colorScheme.error
}
