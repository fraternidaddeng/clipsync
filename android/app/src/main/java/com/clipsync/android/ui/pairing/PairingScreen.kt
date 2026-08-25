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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.clipsync.android.R
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.ui.theme.CharterMotion
import com.clipsync.android.ui.theme.CharterShapes
import com.clipsync.android.ui.theme.ClipSyncFonts
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.LocalReducedMotion
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/** Controls carry the 12dp superellipse (tokens.md §7). */
private val ControlShape = CharterShapes.control

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

    Text(stringResource(R.string.pairing_title), style = RitualTitle, color = c.t1)

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
                stringResource(R.string.pairing_cert_prefix, groupFingerprint(peer.certSha256).take(19)),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = ClipSyncFonts.mono,
                color = c.t2,
            )
            Text(
                stringResource(R.string.pairing_trust_epoch, peer.trustEpoch),
                style = MaterialTheme.typography.bodySmall,
                color = c.t3,
            )
            // 解除配对是灰的事实级操作；红色留给真正的 error。
            TextButton(onClick = viewModel::forgetPeer) {
                Text(stringResource(R.string.pairing_forget), color = c.t3)
            }
        }
        Text(
            stringResource(R.string.pairing_rescan_hint),
            style = MaterialTheme.typography.bodySmall,
            color = c.t3,
        )
    } else {
        Text(
            stringResource(R.string.pairing_intro),
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
        GhostButton(text = stringResource(R.string.pairing_stop_scan), onClick = { scanning = false })
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
            Text(stringResource(R.string.pairing_scan_qr))
        }
        if (cameraDenied) {
            // 缺相机权限不是错误：粘贴通路仍然可用，赭色提示需要你选择。
            Text(
                stringResource(R.string.pairing_camera_denied),
                color = c.act,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    OutlinedTextField(
        value = manualPayload,
        onValueChange = { manualPayload = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.pairing_paste_label)) },
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
        text = stringResource(R.string.pairing_use_pasted),
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
    Text(stringResource(R.string.pairing_confirm_title), style = RitualTitle, color = c.t1)
    Text(
        stringResource(R.string.pairing_confirm_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = c.t2,
    )
    PeerFacts(review.qr)
    if (review.certificateChanged) {
        // 全应用最高风险的决策点：唯一允许赭黄整块背景的地方（ui_preview 注记）。
        val shape = CharterShapes.card
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
                stringResource(R.string.pairing_cert_changed_title),
                color = c.act,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.pairing_cert_changed_body),
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
        Text(
            if (review.certificateChanged) {
                stringResource(R.string.pairing_confirm_replace)
            } else {
                stringResource(R.string.pairing_confirm_match)
            },
        )
    }
    GhostButton(text = stringResource(R.string.common_cancel), onClick = viewModel::cancelReview)
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
        Text(stringResource(R.string.pairing_fingerprint_header), style = ClipSyncType.groupHeader, color = c.t4)
        // 指纹是全应用风险最高的比对：等宽、四位一组、两行、t1 最高对比。
        Text(
            twoLineFingerprint(qr.certSha256),
            style = ClipSyncType.fingerprint.copy(lineHeight = 22.sp),
            color = c.t1,
        )
        Text(stringResource(R.string.pairing_address_header), style = ClipSyncType.groupHeader, color = c.t4)
        Text(
            "${qr.hosts.joinToString()} : ${qr.port}",
            fontFamily = ClipSyncFonts.mono,
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
            Text(stringResource(R.string.pairing_waiting_title), fontWeight = FontWeight.SemiBold, color = c.t1)
            Text(
                stringResource(R.string.pairing_waiting_body, peerName),
                style = MaterialTheme.typography.bodyMedium,
                color = c.t2,
            )
        }
    }
}

@Composable
private fun PairedContent(peer: PairedPeer, viewModel: PairingViewModel) {
    val c = clipSyncColors
    Text(stringResource(R.string.pairing_done_title), style = RitualTitle, color = c.t1)
    Column(
        Modifier
            .fillMaxWidth()
            .charterCard()
            .pairingSuccessSheen()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(peer.displayName, fontWeight = FontWeight.SemiBold, color = c.t1)
        Text(
            stringResource(R.string.pairing_done_body),
            style = MaterialTheme.typography.bodySmall,
            color = c.t2,
        )
    }
    Button(onClick = viewModel::reset, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_done))
    }
}

@Composable
private fun FailedContent(reason: PairingFailure, viewModel: PairingViewModel) {
    val c = clipSyncColors
    Text(stringResource(R.string.pairing_failed_title), style = RitualTitle, color = c.t1)
    val message = stringResource(
        when (reason) {
            PairingFailure.INVALID_PAYLOAD -> R.string.pairing_fail_invalid
            PairingFailure.OWN_DEVICE -> R.string.pairing_fail_own_device
            PairingFailure.CERTIFICATE_MISMATCH -> R.string.pairing_fail_cert_mismatch
            PairingFailure.UNREACHABLE -> R.string.pairing_fail_unreachable
            PairingFailure.REJECTED -> R.string.pairing_fail_rejected
            PairingFailure.TIMEOUT -> R.string.pairing_fail_timeout
            PairingFailure.TOKEN_INVALID -> R.string.pairing_fail_token_invalid
            PairingFailure.TOKEN_EXPIRED -> R.string.pairing_fail_token_expired
            PairingFailure.PROTOCOL -> R.string.pairing_fail_protocol
        },
    )
    if (reason == PairingFailure.CERTIFICATE_MISMATCH) {
        // 证书不一致是真正的 error（可能的中间人）：红色着色盒唯一出场处。
        val shape = CharterShapes.card
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
        Text(stringResource(R.string.pairing_restart))
    }
}

/** The sheen band is 36% of the container's width (technique_lab §05). */
private const val SHEEN_BAND_WIDTH_FRACTION = 0.36f

/** The band's left edge travels from -40% to 130% of the width: 1.7 widths in total. */
private const val SHEEN_TRAVEL_START_FRACTION = -0.4f
private const val SHEEN_TRAVEL_WIDTH_FRACTION = 1.7f

/** White at 85% opacity at the band's centre, transparent at both edges. */
private const val SHEEN_PEAK_ALPHA = 0.85f
private const val SHEEN_PEAK_STOP = 0.5f

/** Vertical overdraw so the leaned band still covers the card; the clip crops the rest. */
private const val SHEEN_OVERDRAW_FACTOR = 3f

/** The band leans 18° like the charter demo; one sweep runs 1.3s on Android (skin). */
private const val SHEEN_ANGLE_DEGREES = 18f
private const val SHEEN_TRAVEL_MS = 1300

/**
 * 配对成功的一次性镜面流光（tokens.md §9 唯一豁免：「仅配对成功可考虑一次」；配方按
 * technique_lab §05）：一条容器宽 36% 的白色渐变带，倾斜 18°，以宪章缓动从 -40% 平移到
 * 130%，只走一遍——停顿比划过本身更重要，所以走完即静止，绝不循环。减弱动效时不出场
 * （系统的选择是事实），此时配对完成卡与其他卡面完全一致。
 */
@Composable
private fun Modifier.pairingSuccessSheen(): Modifier {
    if (LocalReducedMotion.current) {
        return this
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = SHEEN_TRAVEL_MS, easing = CharterMotion.Ease),
        )
    }
    return drawWithContent {
        drawContent()
        val value = progress.value
        if (value >= 1f) {
            return@drawWithContent
        }
        val band = size.width * SHEEN_BAND_WIDTH_FRACTION
        val left = size.width * (SHEEN_TRAVEL_START_FRACTION + SHEEN_TRAVEL_WIDTH_FRACTION * value)
        rotate(degrees = SHEEN_ANGLE_DEGREES, pivot = Offset(left + band / 2f, size.height / 2f)) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    SHEEN_PEAK_STOP to Color.White.copy(alpha = SHEEN_PEAK_ALPHA),
                    1f to Color.Transparent,
                    startX = left,
                    endX = left + band,
                ),
                // 竖向大幅超采样：18° 旋转后仍盖满整卡，多余部分被卡面 clip 裁掉。
                topLeft = Offset(left, -size.height),
                size = Size(band, size.height * SHEEN_OVERDRAW_FACTOR),
            )
        }
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
    val shape = CharterShapes.card
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
