package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.domain.security.Totp
import com.example.domain.security.TotpConfig
import com.example.ui.sync.QrCodeAnalyzer
import java.util.concurrent.Executors

/**
 * Full-screen camera that reads a two-factor setup QR code (`otpauth://totp/...`), with the camera permission
 * request. [onScanned] gets the setup; other QR codes only show a hint and scanning goes on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotpQrScanner(onScanned: (TotpConfig) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    // The hint under the camera: null, a QR code that isn't a setup code, or an authenticator's export code.
    var hint by remember { mutableStateOf<Int?>(null) }
    var done by remember { mutableStateOf(false) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val analyzer = remember {
        lateinit var qr: QrCodeAnalyzer
        qr = QrCodeAnalyzer { text ->
            // The analyzer runs off the main thread; hand the result over to it.
            mainExecutor.execute {
                if (done) return@execute
                val config = if (text.trim().startsWith("otpauth://", ignoreCase = true)) Totp.parse(text) else null
                when {
                    config != null -> {
                        done = true
                        qr.pause()
                        onScanned(config)
                    }
                    text.trim().startsWith("otpauth-migration://", ignoreCase = true) -> hint = R.string.totp_scan_export_code
                    else -> hint = R.string.totp_scan_wrong_code
                }
            }
        }
        qr
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.totp_scan_title)) },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (!hasPermission) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                    ) {
                        Text(stringResource(R.string.totp_scan_permission), textAlign = TextAlign.Center)
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.totp_scan_allow_camera))
                        }
                    }
                } else {
                    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
                    // Closed before the camera came up: the late listener must not turn it on.
                    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
                    DisposableEffect(Unit) {
                        onDispose {
                            disposed.set(true)
                            try {
                                provider?.unbindAll()
                            } catch (_: Exception) {
                            }
                            cameraExecutor.shutdown()
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                if (disposed.get()) return@addListener
                                val cameraProvider = future.get()
                                provider = cameraProvider
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { it.setAnalyzer(cameraExecutor, analyzer) }
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                                } catch (_: Exception) {
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        text = stringResource(hint ?: R.string.totp_scan_hint),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(24.dp)
                    )
                }
            }
        }
    }
}
