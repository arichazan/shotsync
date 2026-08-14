package com.shotsync.app.sync

import com.shotsync.app.data.AppSettings
import com.shotsync.app.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** A reachable receiver endpoint. */
data class Endpoint(val host: String, val port: Int, val via: ConnectionMethod) {
    val baseUrl: String get() = "https://$host:$port"
}

enum class ConnectionMethod { LOCAL, TAILSCALE }

sealed class UploadResult {
    data class Stored(val filename: String) : UploadResult()
    data class AlreadyExists(val filename: String) : UploadResult()
    data class Rejected(val httpStatus: Int, val errorCode: String?, val message: String?) : UploadResult()
    data class NetworkError(val message: String) : UploadResult()
}

sealed class PairResult {
    data class Success(val token: String) : PairResult()
    data class Failure(val message: String) : PairResult()
}

data class HealthResult(val ok: Boolean, val paused: Boolean, val deviceName: String?)

/**
 * OkHttp-based protocol client with certificate pinning. All requests carry
 * the device token and device ID. Bodies are streamed, never buffered whole.
 */
class ApiClient(
    private val settings: AppSettings,
    private val token: String?,
) {

    private val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val pin = settings.certificateFingerprint
        val tm = PinningTrustManager(pin)
        val verifier = AllowlistedHostnameVerifier {
            buildSet {
                add(settings.localHost)
                if (settings.tailscaleHost.isNotBlank()) add(settings.tailscaleHost)
            }
        }
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(tm), java.security.SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, tm)
            .hostnameVerifier(verifier)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS) // large recordings on slow links
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun health(endpoint: Endpoint): HealthResult {
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/api/v1/health")
            .get()
            .build()
        return execute(request).use { response ->
            if (response.isSuccessful) {
                HealthResult(
                    ok = true,
                    paused = response.body?.string()?.contains("\"paused\"") == true,
                    deviceName = null,
                )
            } else {
                HealthResult(ok = false, paused = false, deviceName = null)
            }
        }
    }

    /** Probes an endpoint with a bare TCP/TLS handshake-free HEAD via health. */
    suspend fun isReachable(endpoint: Endpoint): Boolean =
        try {
            health(endpoint).ok
        } catch (e: Exception) {
            false
        }

    suspend fun pair(
        endpoint: Endpoint,
        pairingSecret: String,
        deviceId: String,
        deviceName: String,
    ): PairResult {
        val json = org.json.JSONObject()
            .put("pairing_secret", pairingSecret)
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .toString()
        val body = RequestBody.create("application/json".toMediaType(), json)
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/api/v1/pair")
            .post(body)
            .build()
        return try {
            execute(request).use { response ->
                if (response.isSuccessful) {
                    val token = org.json.JSONObject(response.body?.string().orEmpty())
                        .optString("device_token")
                    if (token.isNotBlank()) PairResult.Success(token)
                    else PairResult.Failure("no token in response")
                } else {
                    PairResult.Failure(
                        if (response.code == 401) "pairing secret invalid or expired"
                        else "pairing failed (HTTP ${response.code})"
                    )
                }
            }
        } catch (e: IOException) {
            AppLogger.w("Api", "pair failed: ${e.message}")
            PairResult.Failure("cannot reach receiver: ${e.message}")
        }
    }

    /**
     * Uploads [item] by streaming [openStream] to the receiver.
     * The stream is read in chunks by OkHttp; nothing is buffered in memory.
     */
    suspend fun upload(
        endpoint: Endpoint,
        item: UploadDescriptor,
        openStream: () -> InputStream,
    ): UploadResult {
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = item.mimeType.toMediaTypeOrNull()

            override fun contentLength(): Long = item.size

            override fun writeTo(sink: BufferedSink) {
                openStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                    }
                }
            }
        }
        val builder = Request.Builder()
            .url("${endpoint.baseUrl}/api/v1/screenshots")
            .post(body)
        token?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("X-ShotSync-Device-ID", settings.deviceId)
        builder.header("X-ShotSync-File-ID", item.fileId)
        builder.header("X-ShotSync-SHA256", item.sha256)
        builder.header("X-ShotSync-Filename", item.filename)
        settings.destSubfolder.trim().takeIf { it.isNotEmpty() }?.let {
            builder.header("X-ShotSync-Destination-Subfolder", it)
        }
        item.capturedAtIso?.let { builder.header("X-ShotSync-Captured-At", it) }

        return try {
            execute(builder.build()).use { response ->
                val status = response.code
                when {
                    response.isSuccessful -> {
                        val payload = response.body?.string().orEmpty()
                        val parsed = parseUploadResponse(payload)
                        when (parsed.status) {
                            "stored" -> UploadResult.Stored(parsed.filename ?: item.filename)
                            "already_exists" -> UploadResult.AlreadyExists(parsed.filename ?: item.filename)
                            else -> UploadResult.Rejected(status, "bad_response", payload.take(200))
                        }
                    }
                    else -> {
                        val payload = response.body?.string().orEmpty()
                        val errorCode = runCatching {
                            org.json.JSONObject(payload).optString("error").ifBlank { null }
                        }.getOrNull()
                        UploadResult.Rejected(status, errorCode, payload.take(200))
                    }
                }
            }
        } catch (e: IOException) {
            UploadResult.NetworkError(e.message ?: "network error")
        }
    }

    suspend fun revokeSelf(endpoint: Endpoint) {
        val builder = Request.Builder()
            .url("${endpoint.baseUrl}/api/v1/device/${settings.deviceId}")
            .delete()
        token?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("X-ShotSync-Device-ID", settings.deviceId)
        try {
            execute(builder.build()).use { }
        } catch (_: Exception) {
        }
    }

    private data class UploadResponse(val status: String?, val filename: String?)

    private fun parseUploadResponse(payload: String): UploadResponse =
        runCatching {
            val obj = org.json.JSONObject(payload)
            UploadResponse(obj.optString("status"), obj.optString("filename").ifBlank { null })
        }.getOrDefault(UploadResponse(null, null))

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response)
                }
            })
        }
}

/** Everything the upload call needs, decoupled from the Room entity. */
data class UploadDescriptor(
    val fileId: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val filename: String,
    val capturedAtIso: String?,
)

/** Attach name resolution to the endpoint pick. */
data class ResolvedEndpoint(val endpoint: Endpoint, val via: ConnectionMethod)
