package com.nikolay.assistvoice

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders arbitrary text (a URL, in every current use) as a black-on-white
 * QR bitmap — used for the repo link on the info page and the donation/
 * social links on the support page, so a person can scan straight from the
 * watch instead of the app carrying clickable links it has no browser to
 * open.
 */
object QrCode {

    private const val TAG = "QrCode"

    /** Renders a square QR bitmap [sizePx] wide encoding [content], or null if encoding fails. */
    fun render(content: String, sizePx: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render QR code for $content", e)
            null
        }
    }
}
