package com.shotsync.app.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.security.MessageDigest

/** Generates a synthetic PNG used by the "Send test image" button. */
object TestImage {

    fun create(context: Context): File {
        val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(32, 84, 160))
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            isAntiAlias = true
        }
        canvas.drawText("ShotSync test", 100f, 150f, paint)
        paint.textSize = 24f
        canvas.drawText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date()), 100f, 200f, paint)

        val file = File(context.cacheDir, "shotsync-test.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
