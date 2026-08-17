package com.clipsync.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipsync.android.pairing.PairingConfirmClient
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.ui.HealthScreen
import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.pairing.PairingScreen
import com.clipsync.android.ui.pairing.PairingViewModel
import com.clipsync.android.ui.theme.ClipSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pairingStore = PairingStore(SharedPrefsKeyValueStore(this), KeystoreSecretProtector())
        setContent {
            ClipSyncTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val pairingViewModel: PairingViewModel = viewModel(
                    factory = PairingViewModel.factory(
                        pairingStore,
                        PairingConfirmClient(),
                        localNameFallback = deviceLabel(),
                    ),
                )
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = {},
                                label = { Text("Status") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = {},
                                label = { Text("Pairing") },
                            )
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> HealthScreen(
                            state = HealthScreenState.initial(),
                            modifier = Modifier.padding(padding),
                        )
                        else -> PairingScreen(
                            viewModel = pairingViewModel,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }

    private fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        val label = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
        return label.ifBlank { "Android phone" }
    }
}

