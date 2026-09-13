package com.tifusi.vpn.ui.servers

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.tifusi.vpn.R
import com.tifusi.vpn.data.SubscriptionClient
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Scans a QR code from the panel's subscription page and hands back the subscription it points
 * at, which the Servers tab then imports like a pasted link: every server, the certificate chain
 * and the account limits all come from the panel, whichever QR on the page was scanned.
 */
@Composable
fun ScanQrScreen(
    onSubscriptionScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var unrecognized by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.scan_qr_code), style = MaterialTheme.typography.headlineMedium)

        if (!hasPermission) {
            Text(stringResource(R.string.camera_permission_required), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = TifusiNeonBlue),
            ) {
                Text(stringResource(R.string.grant_permission))
            }
        } else {
            Text(stringResource(R.string.qr_point_camera), color = TifusiTextSecondary)
            QrCameraPreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp)),
                onCodeScanned = { raw ->
                    val subscription = subscriptionFromQr(raw)
                    if (subscription != null) {
                        onSubscriptionScanned(subscription)
                        true
                    } else {
                        unrecognized = true
                        false
                    }
                },
            )
            if (unrecognized) {
                Text(stringResource(R.string.qr_unrecognized), color = MaterialTheme.colorScheme.error)
            }
        }

        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

// A panel app code: the username followed by eight characters from app_code.py's alphabet,
// optionally with the panel's host.
private val APP_CODE = Regex(
    """^[A-Za-z][A-Za-z0-9_]{2,63}[2-9A-HJ-NP-Za-hj-np-z]{8}(@[A-Za-z0-9.-]+(:\d+)?)?$""",
)
private val IMPORT_URI = Regex("""^tifusi-vpn://import\?data=([A-Za-z0-9_-]+)""")

/**
 * The subscription a scanned code points at, or null for anything else. Accepts the
 * subscription link QR, an app code, and the per-server `tifusi-vpn://import` QR codes, which
 * carry the subscription link in their `sub` field.
 */
internal fun subscriptionFromQr(raw: String): String? {
    val value = raw.trim()
    if ("://" in value && !value.startsWith("tifusi-vpn://")) {
        return value.takeIf { SubscriptionClient.normalize(it) != null }
    }
    if (APP_CODE.matches(value)) {
        return value.takeIf { SubscriptionClient.normalize(it) != null }
    }
    val data = IMPORT_URI.find(value)?.groupValues?.get(1) ?: return null
    return runCatching {
        val json = JSONObject(String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        json.optString("sub").takeIf { it.isNotBlank() && SubscriptionClient.normalize(it) != null }
    }.getOrNull()
}

/**
 * CameraX preview with an ML Kit QR analyzer. [onCodeScanned] returns true once it has accepted a
 * code, which stops further analysis so a single scan never navigates twice.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun QrCameraPreview(
    modifier: Modifier,
    onCodeScanned: (String) -> Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCodeScanned by rememberUpdatedState(onCodeScanned)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    val handled = remember { AtomicBoolean(false) }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            handled.set(true)
            analysis.clearAnalyzer()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null || handled.get()) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                // ML Kit delivers these listeners on the main thread, where navigation is safe.
                scanner.process(input)
                    .addOnSuccessListener { barcodes ->
                        val raw = barcodes.firstNotNullOfOrNull { it.rawValue } ?: return@addOnSuccessListener
                        if (!handled.get() && currentOnCodeScanned(raw)) {
                            handled.set(true)
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                // Bound to the nav entry's lifecycle, so leaving the screen releases the camera.
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}
