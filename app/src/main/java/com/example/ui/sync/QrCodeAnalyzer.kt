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
            // Rows are rowStride bytes apart (padding after each row); the last row may be unpadded.
            val rowStride = plane.rowStride
            if (width <= 0 || height <= 0 || rowStride < width ||
                data.size.toLong() < rowStride.toLong() * (height - 1) + width
            ) {
                return
            }

            val source = luminanceSource(data, width, height, rowStride)

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

    companion object {
        /**
         * The Y plane as zxing reads it: [rowStride] bytes per row in [data], of which the first
         * [width] are pixels. zxing only reads y * rowStride + x for x < width, so [data] needs
         * rowStride * (height - 1) + width bytes (the last row may be unpadded).
         */
        internal fun luminanceSource(data: ByteArray, width: Int, height: Int, rowStride: Int): PlanarYUVLuminanceSource {
            require(rowStride >= width) { "rowStride $rowStride is smaller than the width $width" }
            return PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        }
    }
}
