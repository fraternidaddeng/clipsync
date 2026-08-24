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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    Text("Pair with Windows", style = RitualTitle, color = clipSyncColors.t1)

    if (peer != null) {
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(peer.displayName, fontWeight = FontWeight.SemiBold, color = clipSyncColors.t1)
            Text(
                "Certificate ${groupFingerprint(peer.certSha256).take(19)}…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = clipSyncColors.t2,
            )
            Text(
                "Trust epoch ${peer.trustEpoch}",
                style = MaterialTheme.typography.bodySmall,
                color = clipSyncColors.t3,
            )
            TextButton(onClick = viewModel::forgetPeer) { Text("Forget this pairing") }
        }
        Text(
            "Scanning a new code replaces the current pairing after your confirmation.",
            style = MaterialTheme.typography.bodySmall,
            color = clipSyncColors.t3,
        )
    } else {
        Text(
            "Open ClipSync on your computer, choose \"Pair new device\", then scan the QR code it shows.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (scanning) {
        QrScannerView(
            onResult = { raw ->
                scanning = false
                viewModel.onPayload(raw)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
        OutlinedButton(
            onClick = { scanning = false },
            shape = ControlShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Stop scanning")
        }
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
            Text("Scan QR code")
        }
        if (cameraDenied) {
            Text(
                "Camera permission was denied. You can still pair by pasting the payload below.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    OutlinedTextField(
        value = manualPayload,
        onValueChange = { manualPayload = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Or paste the pairing payload") },
        shape = ControlShape,
        minLines = 2,
    )
    OutlinedButton(
        onClick = {
            viewModel.onPayload(manualPayload)
            manualPayload = ""
        },
        enabled = manualPayload.isNotBlank(),
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Use pasted payload")
    }
}

@Composable
private fun ReviewContent(review: PairingUiState.Review, viewModel: PairingViewModel) {
    val c = clipSyncColors
    Text("Confirm this computer", style = RitualTitle, color = c.t1)
    Text(
        "Only continue if the name and certificate fingerprint below match what the Windows app shows.",
        style = MaterialTheme.typography.bodyMedium,
        color = c.t2,
    )
    PeerFacts(review.qr)
    if (review.certificateChanged) {
        // Certificate change is a genuine error — the one place red is allowed.
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.errBg)
                .border(1.dp, c.errLn, shape),
        ) {
            Text(
                "Warning: this computer's certificate CHANGED since the last pairing. " +
                    "If you did not reinstall ClipSync on Windows, stop and check the computer.",
                modifier = Modifier.padding(16.dp),
                color = c.err,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Button(onClick = viewModel::confirm, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
        Text(if (review.certificateChanged) "I verified it — replace pairing" else "Fingerprint matches — pair")
    }
    OutlinedButton(onClick = viewModel::cancelReview, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
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
        Text(
            "Certificate fingerprint",
            style = MaterialTheme.typography.labelMedium,
            color = c.t3,
        )
        // The fingerprint is the app's highest-risk comparison: t1 + mono.
        Text(
            groupFingerprint(qr.certSha256),
            fontFamily = FontFamily.Monospace,
            style = ClipSyncType.fingerprint,
            color = c.t1,
        )
        Text(
            "Address ${qr.hosts.joinToString()} : ${qr.port}",
            style = MaterialTheme.typography.bodySmall,
            color = c.t3,
        )
    }
}

@Composable
private fun SubmittingContent(peerName: String) {
    val c = clipSyncColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.padding(end = 16.dp))
        Column {
            Text("Waiting for approval…", fontWeight = FontWeight.SemiBold, color = c.t1)
            Text(
                "Approve this phone in the ClipSync window on \"$peerName\".",
                style = MaterialTheme.typography.bodyMedium,
                color = c.t2,
            )
        }
    }
}

@Composable
private fun PairedContent(peer: PairedPeer, viewModel: PairingViewModel) {
    Text("Paired", style = RitualTitle, color = clipSyncColors.t1)
    Text(
        "This phone is now paired with \"${peer.displayName}\". Clipboard sync starts in a later stage; the trust and secret are stored securely now.",
        color = clipSyncColors.t2,
    )
    Button(onClick = viewModel::reset, shape = ControlShape, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun FailedContent(reason: PairingFailure, viewModel: PairingViewModel) {
    Text("Pairing failed", style = RitualTitle, color = clipSyncColors.t1)
    Text(
        when (reason) {
            PairingFailure.INVALID_PAYLOAD -> "That is not a valid ClipSync pairing code."
            PairingFailure.OWN_DEVICE -> "This code identifies this device itself."
            PairingFailure.CERTIFICATE_MISMATCH ->
                "The computer presented a DIFFERENT certificate than the QR code promised. " +
                    "Pairing was blocked. Check the network and re-open the QR window on Windows."
            PairingFailure.UNREACHABLE ->
                "The computer could not be reached. Make sure both devices are on the same network."
            PairingFailure.REJECTED -> "The request was rejected on the computer."
            PairingFailure.TIMEOUT -> "The computer did not approve in time. Show a fresh QR code and try again."
            PairingFailure.TOKEN_INVALID -> "This code was already used or cancelled. Show a fresh QR code."
            PairingFailure.TOKEN_EXPIRED -> "This code expired. Show a fresh QR code and scan it promptly."
            PairingFailure.PROTOCOL -> "The computer answered outside the pairing protocol. Update both apps to matching versions."
        },
        color = if (reason == PairingFailure.CERTIFICATE_MISMATCH) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
    Button(onClick = viewModel::reset, shape = ControlShape, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
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
