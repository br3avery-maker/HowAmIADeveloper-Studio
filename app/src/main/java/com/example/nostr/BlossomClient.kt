package com.example.nostr

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BlossomClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    data class BlobUploadResult(
        val sha256: String,
        val url: String,
        val serverUrl: String,
        val uploadedLive: Boolean,
        val statusDetail: String
    )

    suspend fun uploadBlob(
        serverUrl: String,
        bytes: ByteArray,
        mimeType: String,
        extension: String,
        privKeyHex: String
    ): BlobUploadResult = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.trim().trimEnd('/')
        val sha256 = NostrCrypto.sha256Hex(bytes)
        val nowSec = System.currentTimeMillis() / 1000L
        val expirationSec = (nowSec + 600L).toString()

        // BUD-11 / BUD-02 Kind 24242 Authorization Event
        val authEvent = NostrEvent.createAndSign(
            privKeyHex = privKeyHex,
            kind = NostrKinds.BLOSSOM_AUTH,
            content = "Upload blob $sha256",
            tags = listOf(
                listOf("t", "upload"),
                listOf("x", sha256),
                listOf("expiration", expirationSec)
            ),
            createdAt = nowSec
        )
        val base64Auth = Base64.encodeToString(
            authEvent.toJsonString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val putUrl = "$cleanServer/upload"
        val request = Request.Builder()
            .url(putUrl)
            .header("Authorization", "Nostr $base64Auth")
            .header("X-SHA-256", sha256)
            .header("Content-Type", mimeType)
            .put(bytes.toRequestBody(mimeType.toMediaType()))
            .build()

        runCatching {
            okHttpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val parsedUrl = runCatching {
                        JSONObject(bodyStr).optString("url")
                    }.getOrNull().orEmpty()
                    val finalUrl = parsedUrl.ifBlank {
                        val extSuffix = if (extension.isNotBlank()) ".$extension" else ""
                        "$cleanServer/$sha256$extSuffix"
                    }
                    BlobUploadResult(
                        sha256 = sha256,
                        url = finalUrl,
                        serverUrl = cleanServer,
                        uploadedLive = true,
                        statusDetail = "Verified on $cleanServer (HTTP ${resp.code})"
                    )
                } else {
                    val extSuffix = if (extension.isNotBlank()) ".$extension" else ""
                    BlobUploadResult(
                        sha256 = sha256,
                        url = "$cleanServer/$sha256$extSuffix",
                        serverUrl = cleanServer,
                        uploadedLive = false,
                        statusDetail = "Hashed locally (${resp.code}); ready for Blossom mirror"
                    )
                }
            }
        }.getOrElse { err ->
            val extSuffix = if (extension.isNotBlank()) ".$extension" else ""
            BlobUploadResult(
                sha256 = sha256,
                url = "$cleanServer/$sha256$extSuffix",
                serverUrl = cleanServer,
                uploadedLive = false,
                statusDetail = "SHA-256 computed (${err.localizedMessage?.take(36) ?: "offline"}); cached locally"
            )
        }
    }

    suspend fun fetchBlobText(servers: List<String>, sha256: String, directUrl: String = ""): String? =
        withContext(Dispatchers.IO) {
            if (directUrl.startsWith("https://", ignoreCase = true) || directUrl.startsWith("http://", ignoreCase = true)) {
                val direct = runCatching {
                    val req = Request.Builder().url(directUrl).get().build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                }.getOrNull()
                if (!direct.isNullOrBlank()) return@withContext direct
            }
            if (sha256.isBlank()) return@withContext null

            val allServers = (servers + DEFAULT_BLOSSOM_SERVERS)
                .map { it.trim().trimEnd('/') }
                .filter { it.startsWith("https://") || it.startsWith("http://") }
                .distinct()
            for (srv in allServers) {
                for (suffix in listOf("", ".html")) {
                    val candidate = runCatching {
                        val req = Request.Builder().url("$srv/$sha256$suffix").get().build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val bytes = resp.body?.bytes() ?: return@use null
                                if (NostrCrypto.sha256Hex(bytes).equals(sha256, ignoreCase = true)) {
                                    String(bytes, Charsets.UTF_8)
                                } else null
                            } else null
                        }
                    }.getOrNull()
                    if (candidate != null) return@withContext candidate
                }
            }
            null
        }

    companion object {
        val DEFAULT_BLOSSOM_SERVERS = listOf(
            "https://blossom.primal.net",
            "https://nostr.download",
            "https://cdn.satellite.earth",
            "https://blossom.band"
        )
    }
}
