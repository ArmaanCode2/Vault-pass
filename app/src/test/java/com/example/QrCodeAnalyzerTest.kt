package com.example

import androidx.camera.core.ImageProxy
import com.example.network.sync.LanSocketTransport
import com.example.ui.sync.QrCodeAnalyzer
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** F38: camera Y planes have rowStride >= width; the analyzer must read them row by row. */
class QrCodeAnalyzerTest {

    private val text = "vaultpass-pair:desktop-1:4f2a9c1e7b3d5a60"
    private val width = 320
    private val height = 240

    /**
     * Renders [text] as a QR code into a Y plane whose rows are [rowStride] bytes apart. The
     * padding after each row is noise, and the last row is unpadded, as camera buffers can be.
     */
    private fun yPlane(rowStride: Int): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
        val data = ByteArray(rowStride * (height - 1) + width)
        for (y in 0 until height) {
            for (x in 0 until rowStride) {
                val index = y * rowStride + x
                if (index >= data.size) break
                data[index] = if (x < width) {
                    if (matrix[x, y]) 0 else 255.toByte()
                } else {
                    ((x * 31 + y * 17) and 0xFF).toByte()
                }
            }
        }
        return data
    }

    private fun decode(source: LuminanceSource): String {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun paddedRows_decode() {
        val rowStride = width + 64
        val data = yPlane(rowStride)
        assertEquals(text, decode(QrCodeAnalyzer.luminanceSource(data, width, height, rowStride)))
    }

    @Test
    fun paddedRows_readTheOldWay_doNotDecode() {
        // What the analyzer used to do: treat the buffer as width bytes per row.
        val data = yPlane(width + 64)
        val oldSource = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        try {
            decode(oldSource)
            fail("Rows read at the wrong stride must not decode")
        } catch (expected: ReaderException) {
        }
    }

    @Test
    fun unpaddedRows_decode() {
        val data = yPlane(width)
        assertEquals(width * height, data.size)
        assertEquals(text, decode(QrCodeAnalyzer.luminanceSource(data, width, height, width)))
    }

    @Test
    fun aStrideSmallerThanTheWidth_isRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeAnalyzer.luminanceSource(ByteArray(width * height), width, height, width - 1)
        }
    }

    // --- analyze(), the path the camera really drives ---

    /** A pairing QR code as the desktop builds it (SyncViewModel.startQrPairing): a large payload. */
    private val pairingUri = "vaultpass://pair?v=2&deviceId=3f6c2b1e-9a47-4d2e-8c15-7b0e4a9d2f61" +
        "&name=${java.net.URLEncoder.encode("Armaan's Work Laptop", "UTF-8")}" +
        "&secret=9c4e1a7f2b3d5e6081a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708" +
        "&ip=192.168.1.120" +
        "&ips=192.168.1.120,10.0.0.15,172.16.0.5" +
        "&port=53853"

    private val frameWidth = 640
    private val frameHeight = 480

    /** [pairingUri] as a QR code in a camera Y plane: [rowStride] bytes per row, noisy padding, last row unpadded. */
    private fun cameraPlane(rowStride: Int, size: Int = rowStride * (frameHeight - 1) + frameWidth): ByteArray {
        val matrix = QRCodeWriter().encode(pairingUri, BarcodeFormat.QR_CODE, frameWidth, frameHeight)
        val data = ByteArray(size)
        for (index in data.indices) {
            val x = index % rowStride
            val y = index / rowStride
            data[index] = if (x < frameWidth && y < frameHeight) {
                if (matrix[x, y]) 0 else 255.toByte()
            } else {
                ((x * 31 + y * 17) and 0xFF).toByte()
            }
        }
        return data
    }

    private class FakeFrame(val proxy: ImageProxy, val closed: () -> Boolean)

    /** A camera frame: ImageProxy and PlaneProxy are interfaces, so plain proxies stand in for CameraX. */
    private fun fakeFrame(data: ByteArray, rowStride: Int): FakeFrame {
        var closeCount = 0
        val plane = java.lang.reflect.Proxy.newProxyInstance(
            ImageProxy.PlaneProxy::class.java.classLoader,
            arrayOf(ImageProxy.PlaneProxy::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getRowStride" -> rowStride
                "getPixelStride" -> 1
                "getBuffer" -> ByteBuffer.wrap(data)
                else -> objectMethod(method.name)
            }
        } as ImageProxy.PlaneProxy
        val image = java.lang.reflect.Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java)
        ) { self, method, _ ->
            when (method.name) {
                "getPlanes" -> arrayOf(plane)
                "getWidth" -> frameWidth
                "getHeight" -> frameHeight
                "getFormat" -> 35 // ImageFormat.YUV_420_888
                "close" -> { closeCount++; Unit }
                "hashCode" -> System.identityHashCode(self)
                else -> objectMethod(method.name)
            }
        } as ImageProxy
        return FakeFrame(image) { closeCount == 1 }
    }

    private fun objectMethod(name: String): Any? = when (name) {
        "toString" -> "fake"
        "hashCode" -> 0
        "equals" -> false
        else -> throw UnsupportedOperationException("Not faked: $name")
    }

    @Test
    fun analyze_paddedCameraRows_scansThePairingCode() {
        val rowStride = frameWidth + 64
        val scanned = mutableListOf<String>()
        val frame = fakeFrame(cameraPlane(rowStride), rowStride)

        QrCodeAnalyzer { scanned += it }.analyze(frame.proxy)

        assertEquals(listOf(pairingUri), scanned)
        assertTrue("The frame is closed", frame.closed())
        assertNotNull("What the scanner hands on is a valid pairing code", LanSocketTransport.parseQrPairingUri(scanned.single()))
    }

    @Test
    fun analyze_unpaddedCameraRows_scansThePairingCode() {
        val scanned = mutableListOf<String>()
        val frame = fakeFrame(cameraPlane(frameWidth), frameWidth)

        QrCodeAnalyzer { scanned += it }.analyze(frame.proxy)

        assertEquals(listOf(pairingUri), scanned)
        assertTrue(frame.closed())
    }

    @Test
    fun analyze_aBufferShorterThanItsRows_isSkipped() {
        val rowStride = frameWidth + 64
        val short = cameraPlane(rowStride, size = rowStride * (frameHeight - 1) + frameWidth - 1)
        val scanned = mutableListOf<String>()
        val frame = fakeFrame(short, rowStride)

        QrCodeAnalyzer { scanned += it }.analyze(frame.proxy)

        assertTrue("No scan from a truncated frame", scanned.isEmpty())
        assertTrue("The frame is still closed", frame.closed())
    }

    @Test
    fun analyze_aStrideSmallerThanTheWidth_isSkipped() {
        val scanned = mutableListOf<String>()
        val frame = fakeFrame(cameraPlane(frameWidth), frameWidth - 1)

        QrCodeAnalyzer { scanned += it }.analyze(frame.proxy)

        assertTrue(scanned.isEmpty())
        assertTrue(frame.closed())
    }
}
