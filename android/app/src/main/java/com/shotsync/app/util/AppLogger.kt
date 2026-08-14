package com.shotsync.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.min

/**
 * Structured in-memory + on-disk logger with a ring buffer for the
 * diagnostics view and a shareable log file for export.
 *
 * Never log image contents, tokens, pairing secrets or private keys.
 */
object AppLogger {

    private const val MAX_BUFFER = 300
    private val buffer = ConcurrentLinkedDeque<String>()
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "logs/shotsync.log").also { f ->
            f.parentFile?.mkdirs()
        }
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        while (buffer.size >= MAX_BUFFER) buffer.pollFirst()
        buffer.addLast(line)
        file?.let { f ->
            runCatching {
                if (f.length() > 2 * 1024 * 1024) f.writeText("") // rotate by truncation
                f.appendText(line + "\n")
            }
        }
    }

    fun i(tag: String, msg: String) = write("INFO", tag, msg)
    fun w(tag: String, msg: String) = write("WARN", tag, msg)
    fun e(tag: String, msg: String) = write("ERROR", tag, msg)
    fun d(tag: String, msg: String) = write("DEBUG", tag, msg)

    fun snapshot(): List<String> = buffer.toList()

    /** Recent entries formatted for the diagnostics view. */
    fun diagnostics(): String = snapshot().takeLast(40).joinToString("\n")

    /** Exports the full log via a document picker. */
    fun exportIntent(): Intent {
        val f = file ?: File("shotsync.log")
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "shotsync-log-${System.currentTimeMillis()}.log")
        }
    }

    fun logFile(): File? = file

    fun tail(n: Int): List<String> {
        val all = snapshot()
        return all.subList(maxOf(0, all.size - min(n, all.size)), all.size)
    }

    fun writeExportedTo(uri: Uri, context: Context) {
        runCatching {
            val f = file ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
        }
    }
}
