package com.clipsync.android.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/** Controls carry a 12dp radius (tokens.md §7). */
private val ControlShape = RoundedCornerShape(12.dp)

/** The pairing ritual is one of the app's few serif moments. */
private val RitualTitle = ClipSyncType.pageTitle.copy(fontSize = 20.sp)

@Composable
fun PairingScreen(viewModel: PairingViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val current = state) {
            is PairingUiState.Idle -> IdleContent(current.pairedPeer, viewModel)
            is PairingUiState.Review -> ReviewContent(current, viewModel)
            is PairingUiState.Submitting -> SubmittingContent(current.peerName)
            is PairingUiState.Paired -> PairedContent(current.peer, viewModel)
            is PairingUiState.Failed -> FailedContent(current.reason, viewModel)
        }
    }
}

@Composable
private fun IdleContent(peer: PairedPeer?, viewModel: PairingViewModel) {
    val c = clipSyncColors
    var scanning by remember { mutableStateOf(false) }
    var manualPayload by remember { mutableStateOf("") }
    val context = LocalContext.current
    var cameraDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraDenied = !granted
        scanning = granted
    }

    Text("与 Windows 配对", style = RitualTitle, color = c.t1)

    if (peer != null) {
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(peer.displayName, fontWeight = FontWeight.SemiBold, color = c.t1)
            Text(
                "证书 ${groupFingerprint(peer.certSha256).take(19)}…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = c.t2,
            )
            Text(
                "信任纪元 ${peer.trustEpoch}",
                style = MaterialTheme.typography.bodySmall,
                color = c.t3,
            )
            // 解除配对是灰的事实级操作；红色留给真正的 error。
            TextButton(onClick = viewModel::forgetPeer) {
                Text("忘记此配对", color = c.t3)
            }
        }
        Text(
            "扫描新码会在你确认后替换当前配对。",
            style = MaterialTheme.typography.bodySmall,
            color = c.t3,
        )
    } else {
        Text(
            "在电脑上打开「剪剪相传」，选择「配对新设备」，然后扫描它显示的二维码。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.t2,
        )
    }

    if (scanning) {
        ScannerFrame(
            onResult = { raw ->
                scanning = false
                viewModel.onPayload(raw)
            },
        )
        GhostButton(text = "停止扫描", onClick = { scanning = false })
    } else {
        Button(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    scanning = true
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            shape = ControlShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("扫描二维码")
        }
        if (cameraDenied) {
            // 缺相机权限不是错误：粘贴通路仍然可用，赭色提示需要你选择。
            Text(
                "相机权限被拒绝。仍可在下方粘贴配对内容完成配对。",
                color = c.act,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    OutlinedTextField(
        value = manualPayload,
        onValueChange = { manualPayload = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("或粘贴配对内容") },
        shape = ControlShape,
        minLines = 2,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.flowLn,
            unfocusedBorderColor = c.ln,
            focusedLabelColor = c.flow,
            unfocusedLabelColor = c.t4,
            focusedContainerColor = c.sfIn,
            unfocusedContainerColor = c.sfIn,
        ),
    )
    GhostButton(
        text = "使用粘贴的内容",
        enabled = manualPayload.isNotBlank(),
        onClick = {
            viewModel.onPayload(manualPayload)
            manualPayload = ""
        },
    )
}

@Composable
private fun ReviewContent(review: PairingUiState.Review, viewModel: PairingViewModel) {
    val c = clipSyncColors
    Text("确认此电脑", style = RitualTitle, color = c.t1)
    Text(
        "仅当下方名称和证书指纹与 Windows 应用显示的一致时再继续。",
        style = MaterialTheme.typography.bodyMedium,
        color = c.t2,
    )
    PeerFacts(review.qr)
    if (review.certificateChanged) {
        // 全应用最高风险的决策点：唯一允许赭黄整块背景的地方（ui_preview 注记）。
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.actBg)
                .border(1.dp, c.actLn, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "警告：证书已变更",
                color = c.act,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "此电脑的证书自上次配对后已变更。若你没有在 Windows 上重装「剪剪相传」，请停止并检查该电脑。",
                color = c.act,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Button(
        onClick = viewModel::confirm,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth(),
        colors = if (review.certificateChanged) {
            // 责任确认按钮：文案本身是一次动作复述（「我已核实」，不是「确认」）。
            ButtonDefaults.buttonColors(containerColor = c.act, contentColor = c.onFlow)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(if (review.certificateChanged) "我已核实 — 替换配对" else "指纹一致 — 配对")
    }
    GhostButton(text = "取消", onClick = viewModel::cancelReview)
}

@Composable
private fun PeerFacts(qr: PairingQrPayload) {
    val c = clipSyncColors
    Column(
        Modifier
            .fillMaxWidth()
            .charterCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            qr.displayName,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
            color = c.t1,
        )
        Text("证书指纹", style = ClipSyncType.groupHeader, color = c.t4)
        // 指纹是全应用风险最高的比对：等宽、四位一组、两行、t1 最高对比。
        Text(
            twoLineFingerprint(qr.certSha256),
            fontFamily = FontFamily.Monospace,
            style = ClipSyncType.fingerprint.copy(lineHeight = 22.sp),
            color = c.t1,
        )
        Text("地址", style = ClipSyncType.groupHeader, color = c.t4)
        Text(
            "${qr.hosts.joinToString()} : ${qr.port}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = c.t2,
        )
    }
}

@Composable
private fun SubmittingContent(peerName: String) {
    val c = clipSyncColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .charterCard()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = c.flow,
            trackColor = c.sfIn,
            strokeWidth = 3.dp,
            modifier = Modifier.padding(end = 16.dp),
        )
        Column {
            Text("等待批准…", fontWeight = FontWeight.SemiBold, color = c.t1)
            Text(
                "请在「$peerName」的剪剪相传窗口中批准此手机。",
                style = MaterialTheme.typography.bodyMedium,
                color = c.t2,
            )
        }
    }
}

@Composable
private fun PairedContent(peer: PairedPeer, viewModel: PairingViewModel) {
    val c = clipSyncColors
    Text("已配对", style = RitualTitle, color = c.t1)
    Column(
        Modifier
            .fillMaxWidth()
            .charterCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(peer.displayName, fontWeight = FontWeight.SemiBold, color = c.t1)
        Text(
            "信任与密钥已安全存储，网络段就此接通。",
            style = MaterialTheme.typography.bodySmall,
            color = c.t2,
        )
    }
    Button(onClick = viewModel::reset, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
        Text("完成")
    }
}

@Composable
private fun FailedContent(reason: PairingFailure, viewModel: PairingViewModel) {
    val c = clipSyncColors
    Text("配对失败", style = RitualTitle, color = c.t1)
    val message = when (reason) {
        PairingFailure.INVALID_PAYLOAD -> "这不是有效的剪剪相传配对码。"
        PairingFailure.OWN_DEVICE -> "这个码标识的是本机自己。"
        PairingFailure.CERTIFICATE_MISMATCH ->
            "电脑出示的证书与二维码承诺的不一致，配对已被阻止。请检查网络，并在 Windows 上重新打开二维码窗口。"
        PairingFailure.UNREACHABLE -> "无法连接到电脑。请确认两台设备在同一网络。"
        PairingFailure.REJECTED -> "请求在电脑上被拒绝。"
        PairingFailure.TIMEOUT -> "电脑未在限时内批准。请出示新的二维码后重试。"
        PairingFailure.TOKEN_INVALID -> "这个码已被使用或已取消。请出示新的二维码。"
        PairingFailure.TOKEN_EXPIRED -> "这个码已过期。出示新的二维码后请尽快扫描。"
        PairingFailure.PROTOCOL -> "电脑的应答超出配对协议。请将两端更新到匹配的版本。"
    }
    if (reason == PairingFailure.CERTIFICATE_MISMATCH) {
        // 证书不一致是真正的 error（可能的中间人）：红色着色盒唯一出场处。
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.errBg)
                .border(1.dp, c.errLn, shape)
                .padding(16.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = c.err)
        }
    } else {
        // 其余失败是可陈述的事实：普通卡面，不动用红色。
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard()
                .padding(16.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = c.t2)
        }
    }
    Button(onClick = viewModel::reset, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
        Text("重新开始")
    }
}

/** Quiet outlined action: charter ghost button (t3 text, ln2 border). */
@Composable
private fun GhostButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = clipSyncColors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.ln2),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.t3),
    ) {
        Text(text)
    }
}

/**
 * The camera preview inside a charter card, with flow-blue viewfinder corners:
 * the act-pair icon language ("content flows into the frame") at screen scale.
 */
@Composable
private fun ScannerFrame(onResult: (String) -> Unit) {
    val c = clipSyncColors
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(shape)
            .border(1.dp, c.ln, shape),
    ) {
        QrScannerView(onResult = onResult, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val leg = 24.dp.toPx()
            val inset = 14.dp.toPx()
            val radius = 8.dp.toPx()
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width
            val h = size.height
            fun corner(ox: Float, oy: Float, sx: Float, sy: Float) {
                // One L-shaped corner with a small arc, mirrored via sx/sy.
                val path = Path().apply {
                    moveTo(ox, oy + sy * leg)
                    lineTo(ox, oy + sy * radius)
                    quadraticTo(ox, oy, ox + sx * radius, oy)
                    lineTo(ox + sx * leg, oy)
                }
                drawPath(path, color = c.flow, style = stroke)
            }
            corner(inset, inset, 1f, 1f)
            corner(w - inset, inset, -1f, 1f)
            corner(inset, h - inset, 1f, -1f)
            corner(w - inset, h - inset, -1f, -1f)
        }
    }
}

@Composable
private fun QrScannerView(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(viewContext), QrAnalyzer(onResult))
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(viewContext))
            previewView
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}

private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private val delivered = AtomicBoolean(false)

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null || delivered.get()) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                if (raw != null && delivered.compareAndSet(false, true)) {
                    onResult(raw)
                }
            }
            .addOnCompleteListener { image.close() }
    }
}

private fun groupFingerprint(fingerprint: String): String =
    fingerprint.chunked(4).joinToString(separator = " ")

/** Four-character groups, eight per line: humans compare groups, not character streams. */
private fun twoLineFingerprint(fingerprint: String): String =
    fingerprint.chunked(4).chunked(8)
        .joinToString(separator = "\n") { line -> line.joinToString(" ") }
