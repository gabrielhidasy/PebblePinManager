package xyz.hidasy.pebblepinmanager.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeDecoderTest {

    @Test
    fun `decodes pairing URI from luma buffer with rowStride padding`() {
        val pairingUri =
            "pebblepin://pair?auth=bearer&name=Home%20Pin%20Server&token=0123456789abcdef0123456789abcdef&url=https%3A%2F%2Fpins.example.com%2Fpins.json&v=1"
        val bitMatrix = QRCodeWriter().encode(
            pairingUri,
            BarcodeFormat.QR_CODE,
            260,
            260,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
            ),
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val rowStride = width + 32
        val luma = ByteArray(rowStride * height) { 0xFF.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                luma[y * rowStride + x] = if (bitMatrix[x, y]) 0x00 else 0xFF.toByte()
            }
        }

        val decoded = QrCodeDecoder.decodeLuma(
            lumaBytes = luma,
            width = width,
            height = height,
            rowStride = rowStride,
        )
        assertEquals(pairingUri, decoded)
    }

    @Test
    fun `decodes inverted QR code from ARGB pixels and returns null on blank frame`() {
        val payload = "pebblepin://pair?v=1&url=https%3A%2F%2Fpins.example.com%2Fpins.json&auth=bearer&token=tok_inverted"
        val bitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 220, 220)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val invertedPixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Dark background, light modules (simulates inverted display)
                invertedPixels[y * width + x] = if (bitMatrix[x, y]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }

        assertEquals(payload, QrCodeDecoder.decodeArgb(invertedPixels, width, height))

        val blank = ByteArray(100 * 100) { 0xFF.toByte() }
        assertNull(QrCodeDecoder.decodeLuma(blank, 100, 100))
    }
}
