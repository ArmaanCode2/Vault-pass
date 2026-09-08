package com.example.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.VaultPassApplication
import com.example.domain.sync.models.SyncState
import com.example.network.sync.LanDiscoveryManager
import com.example.network.sync.LanSocketTransport
import com.example.network.sync.QrPairingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraQrScanScreen(
    lanSocketTransport: LanSocketTransport,
    lanDiscoveryManager: LanDiscoveryManager? = null,
    onNavigateBack: () -> Unit,
    onPairingSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val discoveryManager = lanDiscoveryManager ?: remember {
        (context.applicationContext as? VaultPassApplication)?.container?.lanDiscoveryManager
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var targetDeviceName by remember { mutableStateOf("") }
    var isConnectingOrWaiting by remember { mutableStateOf(false) }
    var errorDialogDetails by remember { mutableStateOf<Pair<String, String?>?>(null) }

    val syncState by lanSocketTransport.syncState.collectAsState()
    val pendingPairingRequest by lanSocketTransport.pendingPairingRequest.collectAsState()

    LaunchedEffect(syncState) {
        if (syncState == SyncState.PAIRED) {
            isConnectingOrWaiting = false
            onPairingSuccess()
        } else if (syncState == SyncState.ERROR) {
            isConnectingOrWaiting = false
            if (errorDialogDetails == null) {
                errorDialogDetails = Pair(
                    "Failed to connect to peer device.",
                    "Please ensure both devices are on the same Wi-Fi network and firewalls allow LAN traffic."
                )
            }
        }
    }

    val qrAnalyzer = remember(discoveryManager, lanSocketTransport) {
        lateinit var analyzer: QrCodeAnalyzer
        analyzer = QrCodeAnalyzer { qrContent ->
            val pairingData = LanSocketTransport.parseQrPairingUri(qrContent)
            if (pairingData != null && !isConnectingOrWaiting) {
                analyzer.pause()
                targetDeviceName = pairingData.deviceName
                isConnectingOrWaiting = true
                scope.launch {
                    val localIp = discoveryManager?.getLocalIpAddress() ?: "127.0.0.1"
                    val localPrefix = if (localIp.contains(".")) localIp.substringBeforeLast(".") + "." else ""

                    // 1. Start Mobile TCP Server for reverse connection
                    lanSocketTransport.startServer()

                    // 2. Send UDP Reverse Connect Signal to Desktop
                    discoveryManager?.sendReverseConnectSignal(
                        desktopIp = pairingData.primaryIpAddress,
                        pairingToken = pairingData.pairingToken,
                        mobileIp = localIp
                    )

                    // 3. Sort candidate IPs: Known UDP first, then subnet matching
                    val knownUdpIp = discoveryManager?.discoveredDevices?.value?.get(pairingData.deviceId)?.ipAddress
                    val candidates = pairingData.candidateIpAddresses.toMutableList()
                    if (knownUdpIp != null && !candidates.contains(knownUdpIp)) {
                        candidates.add(0, knownUdpIp)
                    }

                    val sortedCandidates = candidates.sortedWith(Comparator { a, b ->
                        when {
                            knownUdpIp != null && a == knownUdpIp -> -1
                            knownUdpIp != null && b == knownUdpIp -> 1
                            localPrefix.isNotEmpty() && a.startsWith(localPrefix) && !b.startsWith(localPrefix) -> -1
                            localPrefix.isNotEmpty() && b.startsWith(localPrefix) && !a.startsWith(localPrefix) -> 1
                            else -> 0
                        }
                    })

                    // 4. Attempt direct connect with fallback
                    val result = lanSocketTransport.connectToPeerCandidates(sortedCandidates, pairingData.port)
                    if (result.isSuccess) {
                        lanSocketTransport.initiatePairing(pairingData)
                    } else {
                        // Check if Desktop reverse connected to us in the meantime
                        if (lanSocketTransport.syncState.value != SyncState.AWAITING_LOCAL_APPROVAL &&
                            lanSocketTransport.syncState.value != SyncState.WAITING_FOR_REMOTE_APPROVAL &&
                            lanSocketTransport.syncState.value != SyncState.PAIRED
                        ) {
                            isConnectingOrWaiting = false
                            val errorDetail = result.exceptionOrNull()?.message ?: "Socket timeout"
                            errorDialogDetails = Pair(
                                "Could not establish connection to ${pairingData.deviceName}.",
                                "Candidate IPs tried:\n${sortedCandidates.joinToString("\n")}\n\n$errorDetail"
                            )
                        }
                    }
                }
            }
        }
        analyzer
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Pairing QR Code") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (hasCameraPermission) {
                        IconButton(onClick = {
                            val nextState = !isTorchOn
                            cameraControl?.enableTorch(nextState)
                            isTorchOn = nextState
                        }) {
                            Icon(
                                imageVector = if (isTorchOn) Icons.Default.FlashOff else Icons.Default.FlashOn,
                                contentDescription = if (isTorchOn) "Turn off flashlight" else "Turn on flashlight"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "VaultPass needs camera access to scan the desktop pairing QR code and establish a secure local network link.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                }
            } else {
                val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
                DisposableEffect(Unit) {
                    onDispose {
                        try {
                            cameraProviderRef?.unbindAll()
                        } catch (_: Exception) {
                        }
                        cameraExecutor.shutdown()
                        if (isConnectingOrWaiting) {
                            scope.launch {
                                lanSocketTransport.disconnect()
                            }
                        }
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProviderRef = cameraProvider
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor, qrAnalyzer)
                                }

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                val camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageAnalysis
                                )
                                cameraControl = camera.cameraControl
                            } catch (e: Exception) {
                                // Camera binding error
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Viewfinder and Laser Animation Overlay
                val infiniteTransition = rememberInfiniteTransition(label = "laser_transition")
                val laserProgress by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "laser_y"
                )

                val primaryColor = MaterialTheme.colorScheme.primary

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                ) {
                    val boxSize = size.minDimension * 0.72f
                    val left = (size.width - boxSize) / 2f
                    val top = (size.height - boxSize) / 2f

                    // Dark semi-transparent mask
                    drawRect(Color(0x99000000))

                    // Transparent cutout viewfinder
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = Size(boxSize, boxSize),
                        cornerRadius = CornerRadius(28f, 28f),
                        blendMode = BlendMode.Clear
                    )

                    // Reticle border
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(left, top),
                        size = Size(boxSize, boxSize),
                        cornerRadius = CornerRadius(28f, 28f),
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // Scanning laser line
                    val lineY = top + (boxSize * laserProgress)
                    drawLine(
                        color = Color(0xFF00E676),
                        start = Offset(left + 24f, lineY),
                        end = Offset(left + boxSize - 24f, lineY),
                        strokeWidth = 2.5.dp.toPx()
                    )
                }

                // Instruction label below cutout
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp)
                        .background(Color(0x88000000), shape = MaterialTheme.shapes.medium)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Point camera at Desktop pairing QR code",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Connecting & Waiting for Remote Approval Dialog
            if (isConnectingOrWaiting && pendingPairingRequest == null) {
                AlertDialog(
                    onDismissRequest = { /* Require explicit cancel */ },
                    title = { Text("LAN Sync Pairing") },
                    text = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Connecting to ${targetDeviceName}...\nWaiting for other device to accept pairing.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(
                            onClick = {
                                isConnectingOrWaiting = false
                                scope.launch {
                                    lanSocketTransport.disconnect()
                                    qrAnalyzer.resume()
                                }
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Incoming Pairing Request Dialog (e.g. via Reverse Connect)
            pendingPairingRequest?.let { request ->
                AlertDialog(
                    onDismissRequest = {
                        scope.launch {
                            lanSocketTransport.declinePairing(request)
                            qrAnalyzer.resume()
                        }
                    },
                    title = { Text("Accept Pairing Request") },
                    text = {
                        Text("Device \"${request.deviceName}\" wants to pair with this device.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                scope.launch {
                                    lanSocketTransport.acceptPairing(request)
                                }
                            }
                        ) {
                            Text("Accept")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    lanSocketTransport.declinePairing(request)
                                    qrAnalyzer.resume()
                                }
                            }
                        ) {
                            Text("Decline")
                        }
                    }
                )
            }

            // Error Details Dialog
            errorDialogDetails?.let { details ->
                ErrorDetailsDialog(
                    errorMessage = details.first,
                    technicalDetails = details.second,
                    onDismiss = {
                        errorDialogDetails = null
                        qrAnalyzer.resume()
                    }
                )
            }
        }
    }
}
