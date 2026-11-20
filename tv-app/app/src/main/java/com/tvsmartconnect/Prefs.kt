package com.tvsmartconnect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom

object Prefs {
    // Bỏ các ký tự dễ nhầm (0/O, 1/I) cho dễ đọc trên TV.
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val FILE = "tvsc"

    /** Mã ghép nối cố định của TV này: tạo một lần, giữ nguyên qua các lần mở app. */
    fun code(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.getString("code", null)?.let { return it }
        val rnd = SecureRandom()
        val code = (1..6).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
        prefs.edit().putString("code", code).apply()
        return code
    }

    fun serverUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString("server", null) ?: BuildConfig.SERVER_URL

    fun setServerUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("server", url).apply()
    }
}

object Qr {
    fun bitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
