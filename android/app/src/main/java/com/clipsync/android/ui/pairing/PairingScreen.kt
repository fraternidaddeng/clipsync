@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:function-signature",
    "ktlint:standard:chain-method-continuation",
)

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.clipsync.android.R
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingQrPayload
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PairingScreen(viewModel: PairingViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier =
            modifier
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
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            cameraDenied = !granted
            scanning = granted
        }

    Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineSmall)

    if (peer != null) {
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.pairing_certificate,
                        groupFingerprint(peer.certSha256).take(19),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.pairing_trust_epoch, peer.trustEpoch),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = viewModel::forgetPeer) {
                    Text(stringResource(R.string.pairing_forget))
                }
            }
        }
        Text(
            stringResource(R.string.pairing_replace_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            stringResource(R.string.pairing_scan_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (scanning) {
        QrScannerView(
            onResult = { raw ->
                scanning = false
                viewModel.onPayload(raw)
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(320.dp),
        )
        OutlinedButton(onClick = { scanning = false }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pairing_stop_scanning))
        }
    } else {
        Button(
            onClick = {
                val granted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                if (granted) {
                    scanning = true
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pairing_scan_qr))
        }
        if (cameraDenied) {
            Text(
                stringResource(R.string.pairing_camera_denied),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    OutlinedTextField(
        value = manualPayload,
        onValueChange = { manualPayload = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.pairing_paste_label)) },
        minLines = 2,
    )
    OutlinedButton(
        onClick = {
            viewModel.onPayload(manualPayload)
            manualPayload = ""
        },
        enabled = manualPayload.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.pairing_use_pasted))
    }
}

@Composable
private fun ReviewContent(review: PairingUiState.Review, viewModel: PairingViewModel) {
    Text(stringResource(R.string.pairing_confirm_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.pairing_confirm_hint),
        style = MaterialTheme.typography.bodyMedium,
    )
    PeerFacts(review.qr)
    if (review.certificateChanged) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                stringResource(R.string.pairing_cert_changed),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Button(onClick = viewModel::confirm, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                if (review.certificateChanged) {
                    R.string.pairing_replace
                } else {
                    R.string.pairing_fingerprint_match
                },
            ),
        )
    }
    OutlinedButton(onClick = viewModel::cancelReview, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pairing_cancel))
    }
}

@Composable
private fun PeerFacts(qr: PairingQrPayload) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(qr.displayName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.pairing_fingerprint_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                groupFingerprint(qr.certSha256),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.pairing_address, qr.hosts.joinToString(), qr.port),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SubmittingContent(peerName: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.padding(end = 16.dp))
        Column {
            Text(stringResource(R.string.pairing_waiting), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.pairing_waiting_hint, peerName),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PairedContent(peer: PairedPeer, viewModel: PairingViewModel) {
    Text(stringResource(R.string.pairing_success_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.pairing_success_body, peer.displayName))
    Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pairing_done))
    }
}

@Composable
private fun FailedContent(reason: PairingFailure, viewModel: PairingViewModel) {
    Text(stringResource(R.string.pairing_failed_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(pairingFailureRes(reason)),
        color =
            if (reason == PairingFailure.CERTIFICATE_MISMATCH) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
    )
    Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pairing_start_over))
    }
}

private fun pairingFailureRes(reason: PairingFailure): Int =
    when (reason) {
        PairingFailure.INVALID_PAYLOAD -> R.string.pairing_fail_invalid_payload
        PairingFailure.OWN_DEVICE -> R.string.pairing_fail_own_device
        PairingFailure.CERTIFICATE_MISMATCH -> R.string.pairing_fail_cert_mismatch
        PairingFailure.UNREACHABLE -> R.string.pairing_fail_unreachable
        PairingFailure.REJECTED -> R.string.pairing_fail_rejected
        PairingFailure.TIMEOUT -> R.string.pairing_fail_timeout
        PairingFailure.TOKEN_INVALID -> R.string.pairing_fail_token_invalid
        PairingFailure.TOKEN_EXPIRED -> R.string.pairing_fail_token_expired
        PairingFailure.RATE_LIMITED -> R.string.pairing_fail_rate_limited
        PairingFailure.PROTOCOL -> R.string.pairing_fail_protocol
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
                val preview =
                    Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                val analysis =
                    ImageAnalysis.Builder()
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

private class QrAnalyzer(
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner =
        BarcodeScanning.getClient(
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

private fun groupFingerprint(fingerprint: String): String = fingerprint.chunked(4).joinToString(separator = " ")
