package com.example.nostr

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BlossomClient(
    private val cacheDir: File? = null,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val memoryBlobCache = ConcurrentHashMap<String, ByteArray>()

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
        val sha256 = NostrCrypto.sha256Hex(bytes).lowercase()
        cacheVerifiedBytes(sha256, bytes)

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

    suspend fun fetchBlobBytes(
        servers: List<String>,
        sha256: String,
        extensionHint: String = "",
        directUrl: String = ""
    ): ByteArray? = withContext(Dispatchers.IO) {
        val normalizedSha = sha256.trim().lowercase()
        if (normalizedSha.length == 64) {
            readCachedBytes(normalizedSha)?.let { return@withContext it }
        }

        if (directUrl.startsWith("https://", ignoreCase = true) || directUrl.startsWith("http://", ignoreCase = true)) {
            val directBytes = fetchRawUrlBytes(directUrl)
            if (directBytes != null && directBytes.isNotEmpty()) {
                if (normalizedSha.length != 64 || NostrCrypto.sha256Hex(directBytes).equals(normalizedSha, ignoreCase = true)) {
                    if (normalizedSha.length == 64) cacheVerifiedBytes(normalizedSha, directBytes)
                    return@withContext directBytes
                }
            }
        }

        if (normalizedSha.length != 64) return@withContext null

        val cleanExt = extensionHint.trim().trimStart('.').lowercase()
        val suffixes = buildList {
            add("")
            if (cleanExt.isNotBlank()) add(".$cleanExt")
            if (cleanExt != "html" && cleanExt.isBlank()) add(".html")
        }.distinct()

        val allServers = (servers + DEFAULT_BLOSSOM_SERVERS)
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
            .distinct()

        for (srv in allServers) {
            for (suffix in suffixes) {
                val candidate = runCatching {
                    val req = Request.Builder()
                        .url("$srv/$normalizedSha$suffix")
                        .header("Accept", "*/*")
                        .get()
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        if (NostrCrypto.sha256Hex(bytes).equals(normalizedSha, ignoreCase = true)) {
                            bytes
                        } else {
                            null
                        }
                    }
                }.getOrNull()

                if (candidate != null) {
                    cacheVerifiedBytes(normalizedSha, candidate)
                    return@withContext candidate
                }
            }
        }
        null
    }

    suspend fun fetchRawUrlBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    suspend fun fetchBlobText(
        servers: List<String>,
        sha256: String,
        extensionHint: String = "html",
        directUrl: String = ""
    ): String? {
        val bytes = fetchBlobBytes(
            servers = servers,
            sha256 = sha256,
            extensionHint = extensionHint,
            directUrl = directUrl
        ) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun readCachedBytes(sha256: String): ByteArray? {
        memoryBlobCache[sha256]?.let { return it }
        val dir = cacheDir ?: return null
        val file = File(dir, sha256)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            if (NostrCrypto.sha256Hex(bytes).equals(sha256, ignoreCase = true)) {
                if (memoryBlobCache.size < 96 && bytes.size <= 2_097_152) {
                    memoryBlobCache[sha256] = bytes
                }
                bytes
            } else {
                file.delete()
                null
            }
        }.getOrNull()
    }

    private fun cacheVerifiedBytes(sha256: String, bytes: ByteArray) {
        if (bytes.size <= 2_097_152) {
            if (memoryBlobCache.size >= 96) {
                memoryBlobCache.keys.firstOrNull()?.let { memoryBlobCache.remove(it) }
            }
            memoryBlobCache[sha256] = bytes
        }
        val dir = cacheDir ?: return
        runCatching {
            dir.mkdirs()
            val target = File(dir, sha256)
            if (!target.exists()) {
                target.writeBytes(bytes)
            }
        }
    }

    companion object {
        val DEFAULT_BLOSSOM_SERVERS = listOf(
            "https://blossom.primal.net",
            "https://cdn.satellite.earth",
            "https://nostr.download",
            "https://blossom.band",
            "https://cdn.hzrd149.com",
            "https://blossom.oxtr.dev",
            "https://nostrcheck.me"
        )
    }
}
