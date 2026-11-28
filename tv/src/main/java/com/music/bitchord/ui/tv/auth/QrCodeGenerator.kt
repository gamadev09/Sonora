package com.music.bitchord.ui.tv.auth

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Generates ISO/IEC 18004 QR matrices using ZXing (`com.google.zxing:core`),
 * supporting arbitrary payload lengths (Versions 1–40) with Error Correction Level M.
 */
object QrCodeGenerator {

    /**
     * Generates a 2D boolean array representing the QR code matrix (true = dark, false = light).
     */
    fun encode(text: String): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
        val width = matrix.width
        val height = matrix.height
        return Array(height) { y ->
            BooleanArray(width) { x -> matrix.get(x, y).toInt() != 0 }
        }
    }
}
