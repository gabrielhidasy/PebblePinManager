package xyz.hidasy.pebblepinmanager.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Pure on-device QR code decoder backed by ZXing's [QRCodeReader].
 *
 * Accepts raw grayscale/YUV luma buffers (from CameraX `ImageAnalysis`) as well as ARGB pixel
 * arrays (for unit tests and offline verification). Never allocates persistent files or makes
 * network calls.
 */
object QrCodeDecoder {
    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * Decodes a QR code from a Y-plane / grayscale byte buffer (stride == [rowStride]).
     * Returns the decoded UTF-8 payload, or `null` if no QR code is detected in the frame.
     */
    fun decodeLuma(
        lumaBytes: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        val requiredBytes = (height - 1) * rowStride + width
        if (lumaBytes.size < requiredBytes) return null

        val source = PlanarYUVLuminanceSource(
            lumaBytes,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        return decodeLuminanceSource(source)
    }

    /**
     * Decodes a QR code from an ARGB integer pixel buffer (`0xFF000000` dark, `0xFFFFFFFF` light).
     */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val source = RGBLuminanceSource(width, height, pixels)
        return decodeLuminanceSource(source)
    }

    private fun decodeLuminanceSource(source: LuminanceSource): String? {
        val reader = QRCodeReader()
        val attempts = listOf(
            { BinaryBitmap(HybridBinarizer(source)) },
            { BinaryBitmap(GlobalHistogramBinarizer(source)) },
            { BinaryBitmap(HybridBinarizer(source.invert())) },
        )
        for (bitmapProvider in attempts) {
            try {
                val result = reader.decode(bitmapProvider(), decodeHints)
                if (!result.text.isNullOrEmpty()) {
                    return result.text
                }
            } catch (_: NotFoundException) {
                // Try next binarizer / inverted luminance.
            } catch (_: Exception) {
                // Ignore corrupt / partial frames during live camera scanning.
            } finally {
                reader.reset()
            }
        }
        return null
    }
}
