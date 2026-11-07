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

    var healthObserver: ((
        serverUrl: String,
        isUpload: Boolean,
        success: Boolean,
        verifiedRetrieval: Boolean,
        hashMismatch: Boolean,
        latencyMs: Long?,
        statusMessage: String
    ) -> Unit)? = null

    data class BlossomServerCapabilities(
        val serverUrl: String,
        val reachable: Boolean,
        val requiresAuth: Boolean,
        val maxUploadBytes: Long,
        val supportsMirror: Boolean,
        val latencyMs: Long,
        val statusDetail: String
    )

    data class BlobUploadResult(
        val sha256: String,
        val url: String,
        val serverUrl: String,
        val uploadedLive: Boolean,
        val statusDetail: String,
        val stage: DeliveryVerificationStage = if (uploadedLive) {
            DeliveryVerificationStage.ACCEPTED
        } else {
            DeliveryVerificationStage.FAILED
        },
        val verifiedRetrieval: Boolean = false,
        val httpCode: Int = 0,
        val mirroredServers: List<String> = emptyList()
    )

    sealed interface BlobFetchOutcome {
        data class Verified(
            val bytes: ByteArray,
            val sha256: String,
            val sourceServer: String
        ) : BlobFetchOutcome

        data class HashMismatch(
            val expectedSha256: String,
            val actualSha256: String,
            val offendingServer: String
        ) : BlobFetchOutcome

        data class Missing(
            val expectedSha256: String,
            val triedServers: List<String>,
            val detail: String
        ) : BlobFetchOutcome
    }

    /**
     * Probes a Blossom server via `OPTIONS /upload` and `HEAD /upload` to determine availability,
     * auth policy, max upload size (`X-Max-Upload-Size` / `X-Content-Length`), and latency.
     */
    suspend fun probeServerCapabilities(rawServerUrl: String): BlossomServerCapabilities =
        withContext(Dispatchers.IO) {
            val clean = normalizeServerUrl(rawServerUrl)
            if (clean.isBlank()) {
                return@withContext BlossomServerCapabilities(
                    serverUrl = rawServerUrl,
                    reachable = false,
                    requiresAuth = true,
                    maxUploadBytes = 0L,
                    supportsMirror = false,
                    latencyMs = 0L,
                    statusDetail = "Invalid server URL"
                )
            }
            val start = System.currentTimeMillis()
            runCatching {
                val optionsReq = Request.Builder()
                    .url("$clean/upload")
                    .method("OPTIONS", null)
                    .build()
                okHttpClient.newCall(optionsReq).execute().use { resp ->
                    val rtt = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    val maxHeader = resp.header("X-Max-Upload-Size")
                        ?: resp.header("X-Max-Content-Length")
                        ?: "0"
                    val maxBytes = maxHeader.toLongOrNull() ?: 0L
                    val wwwAuth = resp.header("WWW-Authenticate").orEmpty()
                    val requiresAuth = resp.code == 401 || wwwAuth.contains("Nostr", ignoreCase = true) || resp.code in 200..299
                    val mirrorCheck = runCatching {
                        val mReq = Request.Builder().url("$clean/mirror").method("OPTIONS", null).build()
                        okHttpClient.newCall(mReq).execute().use { it.code != 404 }
                    }.getOrDefault(false)
                    BlossomServerCapabilities(
                        serverUrl = clean,
                        reachable = resp.code < 500,
                        requiresAuth = requiresAuth,
                        maxUploadBytes = maxBytes,
                        supportsMirror = mirrorCheck,
                        latencyMs = rtt,
                        statusDetail = "HTTP ${resp.code} (${rtt}ms)"
                    )
                }
            }.getOrElse { err ->
                val rtt = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                BlossomServerCapabilities(
                    serverUrl = clean,
                    reachable = false,
                    requiresAuth = true,
                    maxUploadBytes = 0L,
                    supportsMirror = false,
                    latencyMs = rtt,
                    statusDetail = err.localizedMessage?.take(50) ?: "Unreachable"
                )
            }
        }

    suspend fun uploadBlob(
        serverUrl: String,
        bytes: ByteArray,
        mimeType: String,
        extension: String,
        privKeyHex: String,
        verifyRetrieval: Boolean = true
    ): BlobUploadResult {
        val pubHex = NostrCrypto.derivePublicKeyHex(privKeyHex)
        return uploadBlobWithSigner(
            serverUrl = serverUrl,
            bytes = bytes,
            mimeType = mimeType,
            extension = extension,
            pubkeyHex = pubHex,
            verifyRetrieval = verifyRetrieval,
            signAuthEvent = { unsigned ->
                NostrEvent.createAndSign(
                    privKeyHex = privKeyHex,
                    kind = unsigned.kind,
                    content = unsigned.content,
                    tags = unsigned.tags,
                    createdAt = unsigned.createdAt
                )
            }
        )
    }

    /**
     * Uploads a content-addressed blob to `serverUrl` using BUD-02/BUD-11 Kind 24242 auth
     * signed by any active identity (Local Vault, NIP-55 Android Signer, or NIP-46 Bunker),
     * and optionally performs an uncached `GET` + SHA-256 verification to prove `VERIFIED_RETRIEVAL`.
     */
    suspend fun uploadBlobWithSigner(
        serverUrl: String,
        bytes: ByteArray,
        mimeType: String,
        extension: String,
        pubkeyHex: String,
        verifyRetrieval: Boolean = true,
        signAuthEvent: suspend (UnsignedNostrEvent) -> NostrEvent
    ): BlobUploadResult = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(serverUrl)
        val sha256 = NostrCrypto.sha256Hex(bytes).lowercase()
        val extSuffix = if (extension.isNotBlank()) ".${extension.trimStart('.')}" else ""

        if (cleanServer.isBlank()) {
            return@withContext BlobUploadResult(
                sha256 = sha256,
                url = "",
                serverUrl = serverUrl,
                uploadedLive = false,
                statusDetail = "Invalid Blossom server URL",
                stage = DeliveryVerificationStage.FAILED
            )
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val expirationSec = (nowSec + 600L).toString()

        val authEvent = runCatching {
            signAuthEvent(
                UnsignedNostrEvent(
                    pubkey = pubkeyHex,
                    createdAt = nowSec,
                    kind = NostrKinds.BLOSSOM_AUTH,
                    content = "Upload blob $sha256",
                    tags = listOf(
                        listOf("t", "upload"),
                        listOf("x", sha256),
                        listOf("expiration", expirationSec)
                    )
                )
            )
        }.getOrElse { err ->
            return@withContext BlobUploadResult(
                sha256 = sha256,
                url = "$cleanServer/$sha256$extSuffix",
                serverUrl = cleanServer,
                uploadedLive = false,
                statusDetail = "Signer rejected Blossom auth: ${err.message ?: "denied"}",
                stage = DeliveryVerificationStage.REJECTED
            )
        }

        val base64Auth = Base64.encodeToString(
            authEvent.toJsonString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val startMs = System.currentTimeMillis()
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
                val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val bodyStr = resp.body?.string().orEmpty()
                val xReason = resp.header("X-Reason").orEmpty()
                if (resp.isSuccessful) {
                    val respObj = runCatching { JSONObject(bodyStr) }.getOrNull()
                    val returnedSha = respObj?.optString("sha256")?.trim()?.lowercase().orEmpty()
                    if (returnedSha.isNotBlank() && returnedSha != sha256) {
                        healthObserver?.invoke(
                            cleanServer, true, false, false, true, rtt,
                            "Server returned mismatched SHA-256 ($returnedSha)"
                        )
                        return@use BlobUploadResult(
                            sha256 = sha256,
                            url = "$cleanServer/$sha256$extSuffix",
                            serverUrl = cleanServer,
                            uploadedLive = false,
                            statusDetail = "Hash mismatch from $cleanServer",
                            stage = DeliveryVerificationStage.FAILED,
                            httpCode = resp.code
                        )
                    }

                    val parsedUrl = respObj?.optString("url").orEmpty()
                    val finalUrl = parsedUrl.ifBlank { "$cleanServer/$sha256$extSuffix" }

                    val verified = if (verifyRetrieval) {
                        verifyRemoteBlobBytes(cleanServer, sha256, extension, finalUrl)
                    } else {
                        false
                    }
                    cacheVerifiedBytes(sha256, bytes)

                    val stage = if (verified) {
                        DeliveryVerificationStage.VERIFIED_RETRIEVAL
                    } else {
                        DeliveryVerificationStage.ACCEPTED
                    }
                    val detail = if (verified) {
                        "Uploaded & SHA-256 retrieval verified on $cleanServer"
                    } else {
                        "Accepted by $cleanServer (HTTP ${resp.code})"
                    }
                    healthObserver?.invoke(cleanServer, true, true, verified, false, rtt, detail)
                    BlobUploadResult(
                        sha256 = sha256,
                        url = finalUrl,
                        serverUrl = cleanServer,
                        uploadedLive = true,
                        statusDetail = detail,
                        stage = stage,
                        verifiedRetrieval = verified,
                        httpCode = resp.code
                    )
                } else {
                    val reason = xReason.ifBlank { bodyStr.take(60) }.ifBlank { "HTTP ${resp.code}" }
                    val stage = if (resp.code in listOf(401, 402, 403, 413, 415)) {
                        DeliveryVerificationStage.REJECTED
                    } else {
                        DeliveryVerificationStage.FAILED
                    }
                    healthObserver?.invoke(cleanServer, true, false, false, false, rtt, "Rejected ($reason)")
                    BlobUploadResult(
                        sha256 = sha256,
                        url = "$cleanServer/$sha256$extSuffix",
                        serverUrl = cleanServer,
                        uploadedLive = false,
                        statusDetail = "Rejected by $cleanServer: $reason",
                        stage = stage,
                        httpCode = resp.code
                    )
                }
            }
        }.getOrElse { err ->
            val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val msg = err.localizedMessage?.take(50) ?: "Upload network error"
            healthObserver?.invoke(cleanServer, true, false, false, false, rtt, msg)
            BlobUploadResult(
                sha256 = sha256,
                url = "$cleanServer/$sha256$extSuffix",
                serverUrl = cleanServer,
                uploadedLive = false,
                statusDetail = "Unreachable ($cleanServer): $msg",
                stage = DeliveryVerificationStage.DISCONNECTED
            )
        }
    }

    /**
     * Performs a live network `GET` bypassing all local caches to verify that `serverUrl`
     * genuinely serves bytes matching `expectedSha256`.
     */
    suspend fun verifyRemoteBlobBytes(
        serverUrl: String,
        expectedSha256: String,
        extensionHint: String = "",
        directUrl: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(serverUrl)
        val normSha = expectedSha256.trim().lowercase()
        if (normSha.length != 64) return@withContext false

        val cleanExt = extensionHint.trim().trimStart('.').lowercase()
        val candidateUrls = buildList {
            if (directUrl.startsWith("http", ignoreCase = true)) add(directUrl)
            if (cleanServer.isNotBlank()) {
                add("$cleanServer/$normSha")
                if (cleanExt.isNotBlank()) add("$cleanServer/$normSha.$cleanExt")
            }
        }.distinct()

        for (candidateUrl in candidateUrls) {
            val matched = runCatching {
                val req = Request.Builder()
                    .url(candidateUrl)
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val bodyBytes = resp.body?.bytes() ?: return@use false
                    NostrCrypto.sha256Hex(bodyBytes).equals(normSha, ignoreCase = true)
                }
            }.getOrDefault(false)
            if (matched) return@withContext true
        }
        false
    }

    /**
     * Fetches a content-addressed blob with full outcome discrimination (`Verified`, `HashMismatch`, `Missing`)
     * so callers can distinguish missing blobs from corrupt/tampered blobs and recover across mirrors.
     */
    suspend fun fetchBlobDetailed(
        servers: List<String>,
        sha256: String,
        extensionHint: String = "",
        directUrl: String = ""
    ): BlobFetchOutcome = withContext(Dispatchers.IO) {
        val normalizedSha = sha256.trim().lowercase()
        if (normalizedSha.length == 64) {
            readCachedBytes(normalizedSha)?.let { cached ->
                return@withContext BlobFetchOutcome.Verified(cached, normalizedSha, "local-cache")
            }
        }

        var firstMismatch: BlobFetchOutcome.HashMismatch? = null

        if (directUrl.startsWith("https://", ignoreCase = true) || directUrl.startsWith("http://", ignoreCase = true)) {
            val directBytes = fetchRawUrlBytes(directUrl)
            if (directBytes != null && directBytes.isNotEmpty()) {
                if (normalizedSha.length != 64) {
                    val computed = NostrCrypto.sha256Hex(directBytes).lowercase()
                    return@withContext BlobFetchOutcome.Verified(directBytes, computed, directUrl)
                }
                val actual = NostrCrypto.sha256Hex(directBytes).lowercase()
                if (actual == normalizedSha) {
                    cacheVerifiedBytes(normalizedSha, directBytes)
                    return@withContext BlobFetchOutcome.Verified(directBytes, normalizedSha, directUrl)
                } else {
                    firstMismatch = BlobFetchOutcome.HashMismatch(normalizedSha, actual, directUrl)
                }
            }
        }

        if (normalizedSha.length != 64) {
            return@withContext BlobFetchOutcome.Missing(normalizedSha, emptyList(), "Invalid or empty SHA-256")
        }

        val cleanExt = extensionHint.trim().trimStart('.').lowercase()
        val suffixes = buildList {
            add("")
            if (cleanExt.isNotBlank()) add(".$cleanExt")
            if (cleanExt != "html" && cleanExt.isBlank()) add(".html")
        }.distinct()

        val allServers = (servers + DEFAULT_BLOSSOM_SERVERS)
            .map { normalizeServerUrl(it) }
            .filter { it.isNotBlank() }
            .distinct()

        for (srv in allServers) {
            val startMs = System.currentTimeMillis()
            var serverHadMismatch = false
            for (suffix in suffixes) {
                val attempt = runCatching {
                    val req = Request.Builder()
                        .url("$srv/$normalizedSha$suffix")
                        .header("Accept", "*/*")
                        .get()
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body?.bytes()
                    }
                }.getOrNull()

                if (attempt != null) {
                    val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val actualSha = NostrCrypto.sha256Hex(attempt).lowercase()
                    if (actualSha == normalizedSha) {
                        cacheVerifiedBytes(normalizedSha, attempt)
                        healthObserver?.invoke(srv, false, true, true, false, rtt, "Verified GET $normalizedSha")
                        return@withContext BlobFetchOutcome.Verified(attempt, normalizedSha, srv)
                    } else {
                        serverHadMismatch = true
                        healthObserver?.invoke(
                            srv, false, false, false, true, rtt,
                            "SHA-256 mismatch: expected ${normalizedSha.take(10)}…, got ${actualSha.take(10)}…"
                        )
                        if (firstMismatch == null) {
                            firstMismatch = BlobFetchOutcome.HashMismatch(normalizedSha, actualSha, srv)
                        }
                    }
                }
            }
            if (!serverHadMismatch) {
                val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                healthObserver?.invoke(srv, false, false, false, false, rtt, "Blob $normalizedSha not found")
            }
        }

        firstMismatch ?: BlobFetchOutcome.Missing(
            expectedSha256 = normalizedSha,
            triedServers = allServers,
            detail = "Blob ${normalizedSha.take(12)}… not found across ${allServers.size} Blossom servers"
        )
    }

    suspend fun fetchBlobBytes(
        servers: List<String>,
        sha256: String,
        extensionHint: String = "",
        directUrl: String = ""
    ): ByteArray? {
        return when (val out = fetchBlobDetailed(servers, sha256, extensionHint, directUrl)) {
            is BlobFetchOutcome.Verified -> out.bytes
            else -> null
        }
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

    fun hasCachedBlob(sha256: String): Boolean {
        val norm = sha256.trim().lowercase()
        if (norm.length != 64) return false
        if (memoryBlobCache.containsKey(norm)) return true
        val dir = cacheDir ?: return false
        return File(dir, norm).exists()
    }

    fun putVerifiedBlobInCache(bytes: ByteArray): String {
        val sha = NostrCrypto.sha256Hex(bytes).lowercase()
        cacheVerifiedBytes(sha, bytes)
        return sha
    }

    private fun readCachedBytes(sha256: String): ByteArray? {
        memoryBlobCache[sha256]?.let { cached ->
            if (NostrCrypto.sha256Hex(cached).equals(sha256, ignoreCase = true)) {
                return cached
            } else {
                memoryBlobCache.remove(sha256)
            }
        }
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

        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            return when {
                trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
                trimmed.startsWith("http://", ignoreCase = true) -> "http://" + trimmed.substring(7)
                else -> "https://$trimmed"
            }
        }
    }
}
