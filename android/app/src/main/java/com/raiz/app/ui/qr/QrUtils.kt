package com.raiz.app.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Genera un Bitmap con el código QR del contenido dado.
 *
 * Para RAÍZ, el contenido típico es una Stellar address (`G...` cuenta o
 * `C...` contrato). El escáner reconoce cualquier string; en la pantalla de
 * PayScreen filtramos: si comienza con G/C y mide 56 chars, lo aceptamos
 * como merchant destination.
 *
 * Pintamos blanco/negro estricto — el escáner tiene mejor detección con
 * alto contraste.
 */
object QrUtils {

    fun generate(content: String, sizePx: Int = 640): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    /**
     * QR "nítido" para contenidos densos (el sobre del pago diferido: ≈ 2 000 chars → versión 38).
     * Cada módulo ocupa un número ENTERO de píxeles (`k = targetPx / (módulos + 2·margen)`), sin
     * reescalado: con un QR tan denso, medio píxel de más o de menos por módulo es lo que decide si una
     * cámara lo lee desde una pantalla. Pintarlo en `Modifier.size(bitmap.width.toDp())` con
     * `FilterQuality.None`, nunca con `fillMaxWidth`.
     *
     * Sin hint de charset (el base64url es ASCII puro; el hint UTF-8 añadiría un segmento ECI).
     */
    fun generateCrisp(
        content: String,
        targetPx: Int = 1024,
        ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        quietZoneModules: Int = 4,
    ): Bitmap {
        val code = Encoder.encode(content, ec)
        val m = code.matrix
        val modules = m.width
        val total = modules + 2 * quietZoneModules
        val k = (targetPx / total).coerceAtLeast(1)
        val side = total * k
        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (m.get(x, y).toInt() != 1) continue
                val px0 = (x + quietZoneModules) * k
                val py0 = (y + quietZoneModules) * k
                for (dy in 0 until k) {
                    val row = (py0 + dy) * side
                    for (dx in 0 until k) pixels[row + px0 + dx] = Color.BLACK
                }
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    /** Píxeles por módulo que usaría [generateCrisp] para este contenido y tamaño (para la UI). */
    fun modulesOf(content: String, ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M): Int =
        Encoder.encode(content, ec).matrix.width
}
