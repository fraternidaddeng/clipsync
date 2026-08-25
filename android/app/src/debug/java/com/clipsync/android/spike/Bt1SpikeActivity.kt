package com.clipsync.android.spike

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PHASE 0 SPIKE ONLY — debug builds only (src/debug), never in the release APK.
 * Runbook: docs/bluetooth-phase0-spike.md. Lists OS-bonded devices, dials the Windows
 * spike listener over RFCOMM, runs the bt1 handshake plus RTT/throughput measurements,
 * and mirrors every result line to logcat tag [Bt1SpikeDefaults.LOG_TAG] so a local
 * agent can collect them with `adb logcat -s ClipSyncSpike`.
 */
class Bt1SpikeActivity : ComponentActivity() {
    private val logLines = mutableStateListOf<String>()
    private val permissionGranted = mutableStateOf(false)
    private val bondedDevices = mutableStateListOf<BluetoothDevice>()
    private val selectedAddress = mutableStateOf<String?>(null)
    private val useBt1 = mutableStateOf(true)
    private val transferKiB = mutableStateOf(256)
    private val secretHex = mutableStateOf(Bt1SpikeDefaults.DEFAULT_SECRET_HEX)
    private val busy = mutableStateOf(false)
    private val connected = mutableStateOf(false)

    private var runner: Bt1SpikeRunner? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted.value = granted
            appendLog("SPIKE_RESULT:bluetooth_connect_granted=$granted")
            if (granted) {
                refreshBondedDevices()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionGranted.value = hasConnectPermission()
        if (permissionGranted.value) {
            refreshBondedDevices()
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runner?.closeQuietly()
        runner = null
    }

    // PascalCase is the Compose convention; the main source set baselines this rule instead.
    @Suppress("ktlint:standard:function-naming")
    @SuppressLint("MissingPermission")
    @Composable
    private fun SpikeScreen() {
        val listState = rememberLazyListState()
        LaunchedEffect(logLines.size) {
            if (logLines.isNotEmpty()) {
                listState.animateScrollToItem(logLines.size - 1)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ClipSync 蓝牙 Phase 0 Spike", style = MaterialTheme.typography.titleLarge)
            Text(
                "仅证据收集（ADR 0005 阶段 0），非产品功能。前提：本机与 PC 已在系统设置中完成蓝牙配对，" +
                    "且 Windows 端 spike 监听已启动。",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!permissionGranted.value) {
                Button(onClick = { requestConnectPermission() }) {
                    Text("授予 BLUETOOTH_CONNECT 权限")
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("bt1 安全信道", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = useBt1.value,
                        onCheckedChange = { useBt1.value = it },
                        enabled = !busy.value && !connected.value,
                    )
                    Text("吞吐量:", style = MaterialTheme.typography.bodyMedium)
                    listOf(64, 256, 1024).forEach { kib ->
                        FilterChip(
                            selected = transferKiB.value == kib,
                            onClick = { transferKiB.value = kib },
                            enabled = !busy.value && !connected.value,
                            label = { Text(if (kib >= 1024) "${kib / 1024} MiB" else "$kib KiB") },
                        )
                    }
                }
                OutlinedTextField(
                    value = secretHex.value,
                    onValueChange = { secretHex.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy.value && !connected.value,
                    singleLine = true,
                    label = { Text("Spike 密钥（64 hex，与 Windows 端一致；默认公开测试值）") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("已配对设备（选择 PC）", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { refreshBondedDevices() }, enabled = !busy.value) {
                        Text("刷新")
                    }
                }
                if (bondedDevices.isEmpty()) {
                    Text(
                        "没有已配对设备。先在系统设置 > 蓝牙中与 PC 配对（这是 OS 级 bonding，" +
                            "不是 ClipSync 配对），然后点「刷新」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(modifier = Modifier.heightIn(max = 160.dp)) {
                    LazyColumn {
                        items(bondedDevices, key = { it.address }) { device ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedAddress.value == device.address,
                                    onClick = { selectedAddress.value = device.address },
                                    enabled = !busy.value && !connected.value,
                                )
                                Text(
                                    "${device.name ?: "(无名称)"}  ${device.address}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { runFullSpike() },
                        enabled = !busy.value && !connected.value && selectedAddress.value != null,
                    ) {
                        Text("连接并运行全部测试")
                    }
                    OutlinedButton(
                        onClick = { rerunRtt() },
                        enabled = !busy.value && connected.value,
                    ) {
                        Text("再测 RTT")
                    }
                    OutlinedButton(
                        onClick = { disconnect() },
                        enabled = !busy.value && connected.value,
                    ) {
                        Text("断开")
                    }
                }
            }

            HorizontalDivider()
            Text(
                "日志（同步输出到 logcat 标签 ${Bt1SpikeDefaults.LOG_TAG}）",
                style = MaterialTheme.typography.titleSmall,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(logLines.size.let { (0 until it).toList() }, key = { it }) { index ->
                    Text(
                        logLines[index],
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }

    private fun runFullSpike() {
        val device = bondedDevices.firstOrNull { it.address == selectedAddress.value } ?: return
        val secret = Bt1SpikeDefaults.decodeSecretHex(secretHex.value)
        if (secret == null) {
            appendLog("SPIKE_RESULT:error=secret_hex_invalid")
            return
        }
        val config = Bt1SpikeConfig(useBt1.value, secret, transferKiB.value * 1024)
        busy.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                logEnvironment(config)
                val spikeRunner = Bt1SpikeRunner(config, ::appendLog)
                runner = spikeRunner
                spikeRunner.connectAndHandshake(device)
                connected.value = true
                spikeRunner.runRtt()
                spikeRunner.runUplink()
                spikeRunner.runDownlink()
                appendLog("全部测试完成；连接保持打开。可锁屏静置数分钟后「再测 RTT」（空闲存活观察），最后点「断开」。")
            } catch (exception: Exception) {
                appendLog("SPIKE_RESULT:session=failed")
                appendLog("SPIKE_RESULT:session_error=${exception.javaClass.simpleName}: ${exception.message}")
                runner?.closeQuietly()
                connected.value = false
            } finally {
                busy.value = false
            }
        }
    }

    private fun rerunRtt() {
        val spikeRunner = runner ?: return
        busy.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                appendLog("重新运行 RTT（空闲存活观察）…")
                spikeRunner.runRtt()
            } catch (exception: Exception) {
                appendLog("SPIKE_RESULT:idle_rtt=failed")
                appendLog("SPIKE_RESULT:idle_rtt_error=${exception.javaClass.simpleName}: ${exception.message}")
                spikeRunner.closeQuietly()
                connected.value = false
            } finally {
                busy.value = false
            }
        }
    }

    private fun disconnect() {
        val spikeRunner = runner ?: return
        busy.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                spikeRunner.closeGracefully()
            } catch (exception: Exception) {
                appendLog("SPIKE_RESULT:bye_error=${exception.javaClass.simpleName}: ${exception.message}")
                spikeRunner.closeQuietly()
            } finally {
                connected.value = false
                busy.value = false
                runner = null
            }
        }
    }

    private fun logEnvironment(config: Bt1SpikeConfig) {
        appendLog("SPIKE_RESULT:spike=android-client")
        appendLog("SPIKE_RESULT:phone=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("SPIKE_RESULT:android_release=${Build.VERSION.RELEASE}")
        appendLog("SPIKE_RESULT:android_sdk=${Build.VERSION.SDK_INT}")
        appendLog("SPIKE_RESULT:oem_build=${Build.DISPLAY}")
        appendLog("SPIKE_RESULT:service_uuid=${Bt1SpikeDefaults.SERVICE_UUID}")
        appendLog("SPIKE_RESULT:client_device_id=${Bt1SpikeDefaults.CLIENT_DEVICE_ID}")
        appendLog("SPIKE_RESULT:listener_device_id=${Bt1SpikeDefaults.LISTENER_DEVICE_ID}")
        appendLog("SPIKE_RESULT:trust_epoch=${Bt1SpikeDefaults.TRUST_EPOCH}")
        appendLog("SPIKE_RESULT:transfer_bytes=${config.transferBytes}")
    }

    @SuppressLint("MissingPermission")
    private fun refreshBondedDevices() {
        if (!hasConnectPermission()) {
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        appendLog("SPIKE_RESULT:adapter_present=${adapter != null}")
        if (adapter == null) {
            return
        }
        appendLog("SPIKE_RESULT:adapter_enabled=${adapter.isEnabled}")
        bondedDevices.clear()
        val bonded =
            runCatching { adapter.bondedDevices.orEmpty() }
                .getOrElse {
                    appendLog("SPIKE_RESULT:bonded_enumeration=failed")
                    emptySet()
                }
        bondedDevices.addAll(bonded.sortedBy { it.name ?: it.address })
        appendLog("SPIKE_RESULT:bonded_device_count=${bondedDevices.size}")
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionGranted.value = true
            refreshBondedDevices()
        }
    }

    private fun appendLog(line: String) {
        Log.i(Bt1SpikeDefaults.LOG_TAG, line)
        runOnUiThread { logLines.add(line) }
    }
}
