package com.raiz.app.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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
}
