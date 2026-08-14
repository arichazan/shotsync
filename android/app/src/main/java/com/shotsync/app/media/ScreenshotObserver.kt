package com.shotsync.app.media

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.shotsync.app.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Observes one shared MediaStore collection for new media. Notifications
 * arrive in bursts; they are debounced and coalesced into a single scan.
 */
class ScreenshotObserver(
    private val uri: Uri,
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 600,
    private val onChanged: suspend () -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val registered = AtomicBoolean(false)
    private var debounceJob: Job? = null

    fun start() {
        if (!registered.compareAndSet(false, true)) return
        try {
            resolver.registerContentObserver(uri, true, this)
            AppLogger.i("Observer", "registered on $uri")
        } catch (e: Exception) {
            AppLogger.w("Observer", "register failed: ${e.message}")
            registered.set(false)
        }
    }

    fun stop() {
        if (!registered.compareAndSet(true, false)) return
        try {
            resolver.unregisterContentObserver(this)
            AppLogger.i("Observer", "unregistered")
        } catch (_: Exception) {
        }
        debounceJob?.cancel()
    }

    fun isRegistered(): Boolean = registered.get()

    override fun onChange(selfChange: Boolean) {
        if (selfChange) return
        onChange(selfChange, null)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (selfChange) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            AppLogger.d("Observer", "MediaStore change: $uri")
            try {
                onChanged()
            } catch (e: Exception) {
                AppLogger.w("Observer", "scan failed: ${e.message}")
            }
        }
    }
}
