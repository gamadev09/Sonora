package com.music.bitchord.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.music.bitchord.auth.TvPairingCrypto
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side QR scanner and encrypted pairing confirmation for Sonora TV.
 *
 * Opens a live embedded Camera2 + ZXing QR scanner directly inside Settings →
 * Account → Pair with TV (or skips straight to confirmation when opened via a
 * `sonora://tv-pair?...` deep link).
 */
@Composable
fun TvPairDialog(
    initialRequest: TvPairingCrypto.TvPairRequest?,
    accountLabel: String?,
    signedIn: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var parsedRequest by remember(initialRequest) { mutableStateOf(initialRequest) }
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pairedSuccess by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            errorMessage = "Camera permission is required to scan the Sonora TV QR code."
        } else {
            errorMessage = null
        }
    }

    LaunchedEffect(signedIn, parsedRequest, hasCameraPermission) {
        if (signedIn && parsedRequest == null && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val sendToTv: (TvPairingCrypto.TvPairRequest) -> Unit = { req ->
        sending = true
        errorMessage = null
        scope.launch {
            val result = TvPairingCrypto.sendActiveSessionToTv(context, req)
            sending = false
            result.onSuccess {
                pairedSuccess = true
            }.onFailure { err ->
                errorMessage = err.message ?: "Could not reach Sonora TV on your Wi-Fi."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        icon = { Icon(Icons.Rounded.Tv, contentDescription = null) },
        title = {
            Text(
                when {
                    pairedSuccess -> "Paired with Sonora TV"
                    parsedRequest != null -> "Sign In to Sonora TV?"
                    else -> "Pair with Sonora TV"
                },
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    pairedSuccess -> {
                        Text(
                            "Your account was sent securely to Sonora TV over your local Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    !signedIn -> {
                        Text(
                            "Sign in to your YouTube Music account on this phone first, then scan the QR code on Sonora TV.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    parsedRequest != null -> {
                        val req = parsedRequest!!
                        val who = accountLabel?.takeIf { it.isNotBlank() } ?: "your signed-in account"
                        Text(
                            "Sign in to Sonora TV at ${req.host} as $who? Your session is encrypted end-to-end with a single-use key from the QR code.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    hasCameraPermission -> {
                        Text(
                            "Point your camera at the QR code shown on Sonora TV.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        EmbeddedQrScannerView(
                            onQrDecoded = { raw ->
                                val req = TvPairingCrypto.parseQrUri(raw)
                                if (req != null) {
                                    parsedRequest = req
                                    errorMessage = null
                                } else {
                                    errorMessage = "That QR code is not a valid local Sonora TV pairing code."
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(18.dp)),
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Allow camera access to scan the QR code on Sonora TV.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (sending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Encrypting and sending to TV…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                errorMessage?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            when {
                pairedSuccess -> TextButton(onClick = onDismiss) { Text("Done") }
                !signedIn -> TextButton(onClick = onDismiss) { Text("OK") }
                parsedRequest != null -> TextButton(
                    enabled = !sending,
                    onClick = { sendToTv(parsedRequest!!) },
                ) { Text("Sign In on TV") }
                !hasCameraPermission -> TextButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                ) { Text("Allow Camera") }
            }
        },
        dismissButton = {
            if (!pairedSuccess) {
                Row {
                    if (parsedRequest != null && initialRequest == null && !sending) {
                        TextButton(onClick = { parsedRequest = null; errorMessage = null }) {
                            Text("Scan Again")
                        }
                    }
                    TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
}

/**
 * Lightweight, GPLv3-compatible live Camera2 + ZXing QR code viewfinder.
 * Decodes directly from the YUV_420_888 luma plane at ~5 fps with zero Play Services deps.
 */
@SuppressLint("MissingPermission")
@Composable
private fun EmbeddedQrScannerView(
    onQrDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val delivered = remember { AtomicBoolean(false) }

    var cameraDevice by remember { mutableStateOf<CameraDevice?>(null) }
    var captureSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var imageReader by remember { mutableStateOf<ImageReader?>(null) }
    val bgThread = remember { HandlerThread("SonoraQrCamera").apply { start() } }
    val bgHandler = remember(bgThread) { Handler(bgThread.looper) }

    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { captureSession?.close() }
            runCatching { cameraDevice?.close() }
            runCatching { imageReader?.close() }
            runCatching { bgThread.quitSafely() }
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
                            val camId = mgr.cameraIdList.firstOrNull { id ->
                                mgr.getCameraCharacteristics(id)
                                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                            } ?: mgr.cameraIdList.firstOrNull() ?: return

                            val scanW = 640
                            val scanH = 480
                            st.setDefaultBufferSize(scanW, scanH)
                            val previewSurface = Surface(st)

                            var lastDecodeMs = 0L
                            val ir = ImageReader.newInstance(scanW, scanH, ImageFormat.YUV_420_888, 2).apply {
                                setOnImageAvailableListener({ r ->
                                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                                    try {
                                        val now = System.currentTimeMillis()
                                        if (delivered.get() || now - lastDecodeMs < 180L) return@setOnImageAvailableListener
                                        lastDecodeMs = now
                                        val plane = img.planes[0]
                                        val buf = plane.buffer
                                        val bytes = ByteArray(buf.remaining())
                                        buf.get(bytes)
                                        val source = PlanarYUVLuminanceSource(
                                            bytes,
                                            plane.rowStride,
                                            img.height,
                                            0,
                                            0,
                                            img.width,
                                            img.height,
                                            false,
                                        )
                                        val bitmap = BinaryBitmap(HybridBinarizer(source))
                                        val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
                                        reader.reset()
                                        val text = result?.text
                                        if (!text.isNullOrBlank() && delivered.compareAndSet(false, true)) {
                                            post { onQrDecoded(text) }
                                        }
                                    } finally {
                                        img.close()
                                    }
                                }, bgHandler)
                            }
                            imageReader = ir

                            runCatching {
                                mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                                    override fun onOpened(cam: CameraDevice) {
                                        cameraDevice = cam
                                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(previewSurface)
                                            addTarget(ir.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }
                                        @Suppress("DEPRECATION")
                                        cam.createCaptureSession(
                                            listOf(previewSurface, ir.surface),
                                            object : CameraCaptureSession.StateCallback() {
                                                override fun onConfigured(session: CameraCaptureSession) {
                                                    captureSession = session
                                                    runCatching { session.setRepeatingRequest(req.build(), null, bgHandler) }
                                                }
                                                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                                            },
                                            bgHandler,
                                        )
                                    }
                                    override fun onDisconnected(cam: CameraDevice) { cam.close() }
                                    override fun onError(cam: CameraDevice, error: Int) { cam.close() }
                                }, bgHandler)
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )

        // Crisp scanning corner reticle overlay
        Canvas(modifier = Modifier.fillMaxSize().padding(36.dp)) {
            val len = size.minDimension * 0.16f
            val stroke = 3.5.dp.toPx()
            val c = Color.White.copy(alpha = 0.9f)
            // Top-left
            drawLine(c, Offset(0f, 0f), Offset(len, 0f), stroke, StrokeCap.Round)
            drawLine(c, Offset(0f, 0f), Offset(0f, len), stroke, StrokeCap.Round)
            // Top-right
            drawLine(c, Offset(size.width, 0f), Offset(size.width - len, 0f), stroke, StrokeCap.Round)
            drawLine(c, Offset(size.width, 0f), Offset(size.width, len), stroke, StrokeCap.Round)
            // Bottom-left
            drawLine(c, Offset(0f, size.height), Offset(len, size.height), stroke, StrokeCap.Round)
            drawLine(c, Offset(0f, size.height), Offset(0f, size.height - len), stroke, StrokeCap.Round)
            // Bottom-right
            drawLine(c, Offset(size.width, size.height), Offset(size.width - len, size.height), stroke, StrokeCap.Round)
            drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - len), stroke, StrokeCap.Round)
        }
    }
}
