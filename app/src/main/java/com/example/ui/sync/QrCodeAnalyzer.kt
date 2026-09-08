package com.example.ui.sync

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        setHints(hints)
    }

    @Volatile
    private var isScanning = true

    fun pause() {
        isScanning = false
    }

    fun resume() {
        isScanning = true
    }

    override fun analyze(image: ImageProxy) {
        if (!isScanning) {
            image.close()
            return
        }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val width = image.width
            val height = image.height

            val source = PlanarYUVLuminanceSource(
                data,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )

            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)

            if (result != null && !result.text.isNullOrBlank()) {
                onQrCodeScanned(result.text)
            }
        } catch (e: NotFoundException) {
            // Expected when no QR code is in frame
        } catch (e: Exception) {
            // Frame decoding exception ignored
        } finally {
            reader.reset()
            image.close()
        }
    }
}
