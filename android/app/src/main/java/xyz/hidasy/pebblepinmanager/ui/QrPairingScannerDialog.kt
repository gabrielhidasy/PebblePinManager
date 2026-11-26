package xyz.hidasy.pebblepinmanager.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import xyz.hidasy.pebblepinmanager.remote.QrCodeDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen modal QR scanner backed by CameraX + on-device ZXing decoding, with an inline
 * "Paste Pairing URI" fallback for devices without cameras or when camera permission is denied.
 *
 * [onPayloadDetected] returns `null` when the payload was accepted (causing the dialog to close),
 * or a user-facing error message when an unrelated / invalid QR code was scanned (keeping the
 * viewfinder active).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPairingScannerDialog(
    onPayloadDetected: (String) -> String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var manualPasteMode by rememberSaveable { mutableStateOf(false) }
    var manualPayloadText by rememberSaveable { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(hasCameraPermission, manualPasteMode) {
        if (!hasCameraPermission && !permissionRequested && !manualPasteMode) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Pair Pin Server",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (manualPasteMode) "Paste Pairing URI" else "Scan /register QR Code",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close QR scanner",
                            )
                        }
                    },
                    actions = {
                        if (!manualPasteMode && hasCameraPermission && hasFlashUnit) {
                            IconButton(
                                onClick = {
                                    val next = !torchEnabled
                                    torchEnabled = next
                                    boundCamera?.cameraControl?.enableTorch(next)
                                },
                            ) {
                                Icon(
                                    imageVector = if (torchEnabled) Icons.Default.FlashOff else Icons.Default.FlashOn,
                                    contentDescription = if (torchEnabled) "Turn off flashlight" else "Turn on flashlight",
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (manualPasteMode) {
                    ManualPairingPastePanel(
                        payloadText = manualPayloadText,
                        errorMessage = scanError,
                        onPayloadChange = {
                            manualPayloadText = it
                            scanError = null
                        },
                        onSubmit = {
                            val err = onPayloadDetected(manualPayloadText)
                            if (err == null) {
                                onDismiss()
                            } else {
                                scanError = err
                            }
                        },
                        onSwitchToCamera = {
                            scanError = null
                            manualPasteMode = false
                        },
                    )
                } else if (!hasCameraPermission) {
                    CameraPermissionRequiredCard(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onSwitchToPaste = {
                            scanError = null
                            manualPasteMode = true
                        },
                    )
                } else {
                    Text(
                        text = "Open your companion server's /register page in a browser and point the camera at the QR code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        CameraQrPreview(
                            onCameraBound = { camera ->
                                boundCamera = camera
                                hasFlashUnit = camera.cameraInfo.hasFlashUnit()
                                if (!hasFlashUnit) {
                                    torchEnabled = false
                                }
                            },
                            onCameraError = { err ->
                                cameraError = err
                            },
                            onQrCodeDecoded = { decoded ->
                                val err = onPayloadDetected(decoded)
                                if (err == null) {
                                    onDismiss()
                                    true
                                } else {
                                    scanError = err
                                    false
                                }
                            },
                        )

                        ViewfinderReticleOverlay(
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    cameraError?.let { err ->
                        ScannerErrorBanner(message = err)
                    }

                    scanError?.let { err ->
                        ScannerErrorBanner(message = err)
                    }

                    Text(
                        text = "Decoded 100% on-device. Camera frames are never saved or uploaded.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    OutlinedButton(
                        onClick = {
                            scanError = null
                            manualPasteMode = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Paste Pairing URI Instead")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(
    onCameraBound: (Camera) -> Unit,
    onCameraError: (String) -> Unit,
    onQrCodeDecoded: (String) -> Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val acceptedOnce = remember { AtomicBoolean(false) }
    var lastRejectedValue by remember { mutableStateOf<String?>(null) }
    var lastRejectedAtMs by remember { mutableStateOf(0L) }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null

        val listener = Runnable {
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        if (acceptedOnce.get()) return@setAnalyzer
                        val decoded = decodeImageProxy(imageProxy) ?: return@setAnalyzer
                        val now = System.currentTimeMillis()
                        if (decoded == lastRejectedValue && now - lastRejectedAtMs < 2_500L) {
                            return@setAnalyzer
                        }
                        mainExecutor.execute {
                            if (acceptedOnce.get()) return@execute
                            val accepted = onQrCodeDecoded(decoded)
                            if (accepted) {
                                acceptedOnce.set(true)
                            } else {
                                lastRejectedValue = decoded
                                lastRejectedAtMs = System.currentTimeMillis()
                            }
                        }
                    } finally {
                        imageProxy.close()
                    }
                }

                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                onCameraBound(camera)
            } catch (e: Exception) {
                onCameraError(
                    e.localizedMessage
                        ?: "Unable to start camera preview. You can paste the pairing URI instead.",
                )
            }
        }

        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun decodeImageProxy(imageProxy: ImageProxy): String? {
    val plane = imageProxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = imageProxy.width
    val height = imageProxy.height
    if (width <= 0 || height <= 0 || rowStride < width) return null

    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return QrCodeDecoder.decodeLuma(
        lumaBytes = bytes,
        width = width,
        height = height,
        rowStride = rowStride,
    )
}

@Composable
private fun ViewfinderReticleOverlay(modifier: Modifier = Modifier) {
    val borderColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val frameSize = size.minDimension * 0.68f
        val left = (size.width - frameSize) / 2f
        val top = (size.height - frameSize) / 2f
        val strokeWidth = 3.dp.toPx()

        drawRoundRect(
            color = borderColor.copy(alpha = 0.9f),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(frameSize, frameSize),
            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            style = Stroke(width = strokeWidth),
        )
    }
}

@Composable
private fun CameraPermissionRequiredCard(
    onRequestPermission: () -> Unit,
    onSwitchToPaste: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = "Camera permission needed to scan QR codes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Pin Manager for Pebble uses the camera strictly on-device while this scanner is open to read your companion server's /register QR code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Allow Camera Access")
            }
            OutlinedButton(
                onClick = onSwitchToPaste,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Paste Pairing URI Instead")
            }
        }
    }
}

@Composable
private fun ManualPairingPastePanel(
    payloadText: String,
    errorMessage: String?,
    onPayloadChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToCamera: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Paste a pebblepin://pair?... URI (or pairing JSON) from your server's /register page.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = payloadText,
            onValueChange = onPayloadChange,
            label = { Text("Pairing URI") },
            placeholder = { Text("pebblepin://pair?v=1&url=https%3A%2F%2F...") },
            isError = errorMessage != null,
            supportingText = errorMessage?.let { err -> { Text(err) } },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onSubmit,
            enabled = payloadText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Pair Server")
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onSwitchToCamera) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Use Camera Scanner")
            }
        }
    }
}

@Composable
private fun ScannerErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
