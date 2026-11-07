package com.example.nostr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.data.VaultAccountEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Nostr Signer Engine supporting:
 * 1. Local Android Keystore AES-256-GCM encrypted `nsec` (`LOCAL_KEYSTORE`)
 * 2. NIP-55 Android Signer Intents (`nostrsigner:` URI scheme, e.g. Amber) (`NIP55_ANDROID`)
 * 3. NIP-46 Remote Signer / Nostr Connect (`bunker://<pubkey>?relay=...&secret=...`) (`NIP46_BUNKER`)
 *
 * Private keys never leave `KeyVaultManager` or the external signer app and are NEVER exposed to WebView JS.
 */
class ExternalSignerManager(
    private val keyVault: KeyVaultManager,
    private val relayPool: RelayPoolClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Nip55IntentResult(
        val resultCode: Int,
        val data: Intent?
    )

    data class ParsedBunkerConfig(
        val remoteSignerPubkeyHex: String,
        val relays: List<String>,
        val secret: String
    )

    data class Nip55PublicKeyResult(
        val pubkeyHex: String,
        val npub: String,
        val signerPackage: String
    )

    // Bridges Compose ActivityResultLauncher callbacks for NIP-55 Android Intents
    private val pendingNip55Call = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Nip55IntentResult>?>(null)
    var launchNip55IntentCallback: ((Intent) -> Boolean)? = null

    // Tracks pending NIP-46 JSON-RPC request IDs -> (clientPrivKeyHex, remotePubkeyHex, deferred)
    private data class PendingNip46Rpc(
        val rpcId: String,
        val clientPrivKeyHex: String,
        val remoteSignerPubkeyHex: String,
        val deferred: CompletableDeferred<JSONObject>
    )

    private val pendingNip46Rpcs = ConcurrentHashMap<String, PendingNip46Rpc>()

    init {
        scope.launch {
            relayPool.incomingEvents.collect { (_, event) ->
                if (event.kind == NostrKinds.NIP46_NOSTR_CONNECT) {
                    handleIncomingNip46Event(event)
                }
            }
        }
    }

    fun onNip55ActivityResult(resultCode: Int, data: Intent?) {
        pendingNip55Call.getAndSet(null)?.complete(Nip55IntentResult(resultCode, data))
    }

    fun isNip55SignerInstalled(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        return context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
    }

    /**
     * Requests `get_public_key` from an installed NIP-55 Android signer (such as Amber).
     */
    suspend fun requestNip55Login(preferredPackage: String = ""): Nip55PublicKeyResult =
        withContext(Dispatchers.Main) {
            val launcher = launchNip55IntentCallback
                ?: error("Android Activity intent bridge not attached")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
                putExtra("type", "get_public_key")
                if (preferredPackage.isNotBlank()) {
                    `package` = preferredPackage
                }
            }
            val def = CompletableDeferred<Nip55IntentResult>()
            pendingNip55Call.getAndSet(def)?.cancel()

            val launched = runCatching { launcher(intent) }.getOrDefault(false)
            if (!launched) {
                pendingNip55Call.compareAndSet(def, null)
                error("No NIP-55 Android signer app (e.g. Amber) found on this device")
            }

            val res = withTimeoutOrNull(60_000L) { def.await() }
                ?: error("Timed out waiting for NIP-55 Android signer")
            if (res.resultCode != Activity.RESULT_OK || res.data == null) {
                error("NIP-55 login cancelled or rejected")
            }

            val rawPub = res.data.getStringExtra("result")
                ?: res.data.getStringExtra("signature")
                ?: error("NIP-55 signer did not return a public key")
            val pubHex = NostrCrypto.resolvePubkeyHex(rawPub)
                ?: error("Invalid public key returned by NIP-55 signer: $rawPub")
            val pkg = res.data.getStringExtra("package").orEmpty().ifBlank { preferredPackage }
            Nip55PublicKeyResult(
                pubkeyHex = pubHex,
                npub = NostrCrypto.encodeNpub(pubHex),
                signerPackage = pkg
            )
        }

    /**
     * Connects to a NIP-46 remote bunker (`bunker://<remote-pubkey>?relay=wss://...&secret=...`),
     * performs handshake (`connect` + `get_public_key`), and returns `(userPubkeyHex, clientKeyPair, parsedBunker)`.
     */
    suspend fun connectNip46Bunker(
        rawBunkerUri: String
    ): Triple<String, NostrCrypto.KeyPairHex, ParsedBunkerConfig> = withContext(Dispatchers.IO) {
        val parsed = parseBunkerUri(rawBunkerUri)
            ?: throw IllegalArgumentException("Enter a valid bunker://<pubkey>?relay=wss://... URI")
        val clientKp = NostrCrypto.generateKeyPair()
        relayPool.ensureNip46Subscription(parsed.relays, clientKp.pubKeyHex)

        // 1. Send `connect` RPC with optional secret
        val connectParams = JSONArray().apply {
            put(parsed.remoteSignerPubkeyHex)
            if (parsed.secret.isNotBlank()) {
                put(parsed.secret)
            }
        }
        val connectResp = executeNip46Rpc(
            bunker = parsed,
            clientPrivKeyHex = clientKp.privKeyHex,
            clientPubkeyHex = clientKp.pubKeyHex,
            method = "connect",
            params = connectParams,
            timeoutMs = 12_000L
        )
        val connectErr = connectResp.optString("error")
        if (connectErr.isNotBlank()) {
            error("Bunker rejected connect: $connectErr")
        }

        // 2. Query actual user public key (`get_public_key`) in case user pubkey differs from signer pubkey
        val pubResp = runCatching {
            executeNip46Rpc(
                bunker = parsed,
                clientPrivKeyHex = clientKp.privKeyHex,
                clientPubkeyHex = clientKp.pubKeyHex,
                method = "get_public_key",
                params = JSONArray(),
                timeoutMs = 8_000L
            )
        }.getOrNull()

        val resolvedUserPubkey = pubResp?.optString("result")
            ?.let { NostrCrypto.resolvePubkeyHex(it) }
            ?: parsed.remoteSignerPubkeyHex

        Triple(resolvedUserPubkey, clientKp, parsed)
    }

    /**
     * Signs an `UnsignedNostrEvent` using whichever backend `account` is configured with
     * (`LOCAL_KEYSTORE`, `NIP55_ANDROID`, or `NIP46_BUNKER`) and verifies the BIP-340 signature.
     */
    suspend fun signEventForAccount(
        account: VaultAccountEntity,
        unsigned: UnsignedNostrEvent
    ): NostrEvent {
        return when (account.resolvedSignerType) {
            SignerType.LOCAL_KEYSTORE -> withContext(Dispatchers.IO) {
                require(account.encryptedPrivKeyBase64.isNotBlank()) {
                    "Local private key missing from vault"
                }
                val privHex = keyVault.decryptPrivateKeyHex(account.encryptedPrivKeyBase64, account.ivBase64)
                NostrEvent.createAndSign(
                    privKeyHex = privHex,
                    kind = unsigned.kind,
                    content = unsigned.content,
                    tags = unsigned.tags,
                    createdAt = unsigned.createdAt
                )
            }

            SignerType.NIP55_ANDROID -> signViaNip55Intent(account, unsigned)

            SignerType.NIP46_BUNKER -> signViaNip46Bunker(account, unsigned)

            SignerType.NPUB_READONLY -> {
                error("Current account (${account.displayName}) is read-only (npub). Switch to or add a signing identity.")
            }
        }
    }

    suspend fun nip04Encrypt(account: VaultAccountEntity, peerPubkeyHex: String, plaintext: String): String {
        return when (account.resolvedSignerType) {
            SignerType.LOCAL_KEYSTORE -> withContext(Dispatchers.IO) {
                val privHex = keyVault.decryptPrivateKeyHex(account.encryptedPrivKeyBase64, account.ivBase64)
                NostrCrypto.nip04Encrypt(privHex, peerPubkeyHex, plaintext)
            }
            SignerType.NIP55_ANDROID -> callNip55CryptoIntent(account, "nip04_encrypt", plaintext, peerPubkeyHex)
            SignerType.NIP46_BUNKER -> callNip46Crypto(account, "nip04_encrypt", peerPubkeyHex, plaintext)
            SignerType.NPUB_READONLY -> error("Read-only npub account cannot encrypt")
        }
    }

    suspend fun nip04Decrypt(account: VaultAccountEntity, peerPubkeyHex: String, ciphertext: String): String {
        return when (account.resolvedSignerType) {
            SignerType.LOCAL_KEYSTORE -> withContext(Dispatchers.IO) {
                val privHex = keyVault.decryptPrivateKeyHex(account.encryptedPrivKeyBase64, account.ivBase64)
                NostrCrypto.nip04Decrypt(privHex, peerPubkeyHex, ciphertext)
            }
            SignerType.NIP55_ANDROID -> callNip55CryptoIntent(account, "nip04_decrypt", ciphertext, peerPubkeyHex)
            SignerType.NIP46_BUNKER -> callNip46Crypto(account, "nip04_decrypt", peerPubkeyHex, ciphertext)
            SignerType.NPUB_READONLY -> error("Read-only npub account cannot decrypt")
        }
    }

    suspend fun nip44Encrypt(account: VaultAccountEntity, peerPubkeyHex: String, plaintext: String): String {
        return when (account.resolvedSignerType) {
            SignerType.LOCAL_KEYSTORE -> withContext(Dispatchers.IO) {
                val privHex = keyVault.decryptPrivateKeyHex(account.encryptedPrivKeyBase64, account.ivBase64)
                NostrCrypto.nip44Encrypt(privHex, peerPubkeyHex, plaintext)
            }
            SignerType.NIP55_ANDROID -> callNip55CryptoIntent(account, "nip44_encrypt", plaintext, peerPubkeyHex)
            SignerType.NIP46_BUNKER -> callNip46Crypto(account, "nip44_encrypt", peerPubkeyHex, plaintext)
            SignerType.NPUB_READONLY -> error("Read-only npub account cannot encrypt")
        }
    }

    suspend fun nip44Decrypt(account: VaultAccountEntity, peerPubkeyHex: String, ciphertext: String): String {
        return when (account.resolvedSignerType) {
            SignerType.LOCAL_KEYSTORE -> withContext(Dispatchers.IO) {
                val privHex = keyVault.decryptPrivateKeyHex(account.encryptedPrivKeyBase64, account.ivBase64)
                NostrCrypto.nip44Decrypt(privHex, peerPubkeyHex, ciphertext)
            }
            SignerType.NIP55_ANDROID -> callNip55CryptoIntent(account, "nip44_decrypt", ciphertext, peerPubkeyHex)
            SignerType.NIP46_BUNKER -> callNip46Crypto(account, "nip44_decrypt", peerPubkeyHex, ciphertext)
            SignerType.NPUB_READONLY -> error("Read-only npub account cannot decrypt")
        }
    }

    private suspend fun signViaNip55Intent(
        account: VaultAccountEntity,
        unsigned: UnsignedNostrEvent
    ): NostrEvent = withContext(Dispatchers.Main) {
        val launcher = launchNip55IntentCallback
            ?: error("Android Activity intent bridge not attached")
        val pubHex = account.pubkeyHex.lowercase()
        val commitment = NostrEvent.computeCanonicalCommitment(
            pubkey = pubHex,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content
        )
        val eventId = NostrCrypto.sha256Hex(commitment)
        val eventJson = JSONObject().apply {
            put("id", eventId)
            put("pubkey", pubHex)
            put("created_at", unsigned.createdAt)
            put("kind", unsigned.kind)
            val tagsArr = JSONArray()
            unsigned.tags.forEach { row ->
                val inner = JSONArray()
                row.forEach { inner.put(it) }
                tagsArr.put(inner)
            }
            put("tags", tagsArr)
            put("content", unsigned.content)
        }.toString()

        val uri = Uri.parse("nostrsigner:${Uri.encode(eventJson)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtra("type", "sign_event")
            putExtra("id", eventId)
            putExtra("current_user", account.npub.ifBlank { pubHex })
            if (account.signerPackage.isNotBlank()) {
                `package` = account.signerPackage
            }
        }

        val def = CompletableDeferred<Nip55IntentResult>()
        pendingNip55Call.getAndSet(def)?.cancel()
        if (!launcher(intent)) {
            pendingNip55Call.compareAndSet(def, null)
            error("Unable to launch NIP-55 Android signer")
        }

        val res = withTimeoutOrNull(60_000L) { def.await() }
            ?: error("Timed out waiting for NIP-55 signature")
        if (res.resultCode != Activity.RESULT_OK || res.data == null) {
            error("NIP-55 signing request rejected")
        }

        val fullSignedJson = res.data.getStringExtra("event").orEmpty()
        if (fullSignedJson.startsWith("{")) {
            val parsed = NostrEvent.fromJson(JSONObject(fullSignedJson))
                ?: error("Malformed signed event returned by NIP-55 signer")
            require(parsed.verifySignature()) { "NIP-55 returned invalid Schnorr signature" }
            return@withContext parsed
        }

        val sig = (res.data.getStringExtra("signature") ?: res.data.getStringExtra("result")).orEmpty().trim()
        require(sig.length == 128) { "NIP-55 signer did not return a 64-byte hex signature" }
        val built = NostrEvent(
            id = eventId,
            pubkey = pubHex,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = sig
        )
        require(built.verifySignature()) { "NIP-55 signature failed BIP-340 verification" }
        built
    }

    private suspend fun callNip55CryptoIntent(
        account: VaultAccountEntity,
        type: String,
        payload: String,
        peerPubkeyHex: String
    ): String = withContext(Dispatchers.Main) {
        val launcher = launchNip55IntentCallback
            ?: error("Android Activity intent bridge not attached")
        val uri = Uri.parse("nostrsigner:${Uri.encode(payload)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtra("type", type)
            putExtra("pubKey", peerPubkeyHex)
            putExtra("current_user", account.npub.ifBlank { account.pubkeyHex })
            if (account.signerPackage.isNotBlank()) {
                `package` = account.signerPackage
            }
        }
        val def = CompletableDeferred<Nip55IntentResult>()
        pendingNip55Call.getAndSet(def)?.cancel()
        if (!launcher(intent)) {
            pendingNip55Call.compareAndSet(def, null)
            error("Unable to launch NIP-55 signer for $type")
        }
        val res = withTimeoutOrNull(60_000L) { def.await() }
            ?: error("Timed out waiting for NIP-55 $type")
        if (res.resultCode != Activity.RESULT_OK || res.data == null) {
            error("NIP-55 $type rejected")
        }
        res.data.getStringExtra("result")
            ?: res.data.getStringExtra("signature")
            ?: error("Empty result from NIP-55 $type")
    }

    private suspend fun signViaNip46Bunker(
        account: VaultAccountEntity,
        unsigned: UnsignedNostrEvent
    ): NostrEvent = withContext(Dispatchers.IO) {
        val bunker = parseBunkerUri(account.bunkerUri)
            ?: error("Invalid NIP-46 bunker URI on account")
        val clientPrivHex = keyVault.decryptPrivateKeyHex(account.encryptedPrivKeyBase64, account.ivBase64)
        val clientPubHex = account.bunkerClientPubkeyHex.ifBlank {
            NostrCrypto.derivePublicKeyHex(clientPrivHex)
        }
        relayPool.ensureNip46Subscription(bunker.relays, clientPubHex)

        val params = JSONArray().put(unsigned.toJsonString())
        val resp = executeNip46Rpc(
            bunker = bunker,
            clientPrivKeyHex = clientPrivHex,
            clientPubkeyHex = clientPubHex,
            method = "sign_event",
            params = params,
            timeoutMs = 20_000L
        )
        val err = resp.optString("error")
        if (err.isNotBlank()) {
            error("NIP-46 bunker rejected signing: $err")
        }
        val resultStr = resp.optString("result")
        val signed = NostrEvent.fromJson(JSONObject(resultStr))
            ?: error("Malformed signed event from NIP-46 bunker")
        require(signed.verifySignature()) { "NIP-46 bunker signature failed BIP-340 verification" }
        signed
    }

    private suspend fun callNip46Crypto(
        account: VaultAccountEntity,
        method: String,
        peerPubkeyHex: String,
        payload: String
    ): String = withContext(Dispatchers.IO) {
        val bunker = parseBunkerUri(account.bunkerUri)
            ?: error("Invalid NIP-46 bunker URI on account")
        val clientPrivHex = keyVault.decryptPrivateKeyHex(account.encryptedPrivKeyBase64, account.ivBase64)
        val clientPubHex = account.bunkerClientPubkeyHex.ifBlank {
            NostrCrypto.derivePublicKeyHex(clientPrivHex)
        }
        relayPool.ensureNip46Subscription(bunker.relays, clientPubHex)
        val params = JSONArray().put(peerPubkeyHex).put(payload)
        val resp = executeNip46Rpc(
            bunker = bunker,
            clientPrivKeyHex = clientPrivHex,
            clientPubkeyHex = clientPubHex,
            method = method,
            params = params,
            timeoutMs = 15_000L
        )
        val err = resp.optString("error")
        if (err.isNotBlank()) error("NIP-46 bunker $method error: $err")
        resp.optString("result")
    }

    private suspend fun executeNip46Rpc(
        bunker: ParsedBunkerConfig,
        clientPrivKeyHex: String,
        clientPubkeyHex: String,
        method: String,
        params: JSONArray,
        timeoutMs: Long
    ): JSONObject {
        val rpcId = "rpc_${UUID.randomUUID().toString().take(10)}"
        val reqPayload = JSONObject().apply {
            put("id", rpcId)
            put("method", method)
            put("params", params)
        }.toString()

        val encrypted = NostrCrypto.nip44Encrypt(
            privKeyHex = clientPrivKeyHex,
            peerPubKeyHex = bunker.remoteSignerPubkeyHex,
            plaintext = reqPayload
        )
        val rpcEvent = NostrEvent.createAndSign(
            privKeyHex = clientPrivKeyHex,
            kind = NostrKinds.NIP46_NOSTR_CONNECT,
            content = encrypted,
            tags = listOf(listOf("p", bunker.remoteSignerPubkeyHex))
        )

        val def = CompletableDeferred<JSONObject>()
        pendingNip46Rpcs[rpcId] = PendingNip46Rpc(
            rpcId = rpcId,
            clientPrivKeyHex = clientPrivKeyHex,
            remoteSignerPubkeyHex = bunker.remoteSignerPubkeyHex,
            deferred = def
        )

        relayPool.publishEvent(
            event = rpcEvent,
            timeoutMs = 3500L,
            targetRelayUrls = bunker.relays,
            verifyRetrieval = false
        )

        val response = withTimeoutOrNull(timeoutMs) { def.await() }
        pendingNip46Rpcs.remove(rpcId)
        return response ?: error("Timed out waiting for NIP-46 bunker response on ${bunker.relays.joinToString()}")
    }

    private fun handleIncomingNip46Event(event: NostrEvent) {
        if (pendingNip46Rpcs.isEmpty()) return
        for ((rpcId, pending) in pendingNip46Rpcs) {
            if (!event.pubkey.equals(pending.remoteSignerPubkeyHex, ignoreCase = true)) continue
            val decrypted = runCatching {
                if (event.content.contains("?iv=")) {
                    NostrCrypto.nip04Decrypt(pending.clientPrivKeyHex, event.pubkey, event.content)
                } else {
                    NostrCrypto.nip44Decrypt(pending.clientPrivKeyHex, event.pubkey, event.content)
                }
            }.getOrNull() ?: continue

            val json = runCatching { JSONObject(decrypted) }.getOrNull() ?: continue
            val respId = json.optString("id")
            if (respId == rpcId) {
                if (json.optString("result") == "auth_url") {
                    // Wait for actual signed approval result after auth_url notification
                    continue
                }
                pendingNip46Rpcs.remove(rpcId)?.deferred?.complete(json)
                break
            }
        }
    }

    companion object {
        /**
         * Parses `bunker://<64-char-hex-or-npub>?relay=wss://...&relay=wss://...&secret=...`
         */
        fun parseBunkerUri(raw: String): ParsedBunkerConfig? = runCatching {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("bunker://", ignoreCase = true) &&
                !trimmed.startsWith("nostrconnect://", ignoreCase = true)
            ) {
                return@runCatching null
            }
            val normalizedScheme = "https://" + trimmed.substringAfter("://")
            val uri = URI(normalizedScheme)
            val hostOrUser = uri.host ?: uri.authority?.substringBefore('?') ?: return@runCatching null
            val pubHex = NostrCrypto.resolvePubkeyHex(hostOrUser) ?: return@runCatching null

            val relays = mutableListOf<String>()
            var secret = ""
            val rawQuery = uri.rawQuery.orEmpty()
            if (rawQuery.isNotBlank()) {
                rawQuery.split('&').forEach { pair ->
                    val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                    val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                    when (k.lowercase()) {
                        "relay" -> {
                            val norm = RelayPoolClient.normalizeRelayUrl(v)
                            if (norm.isNotBlank()) relays.add(norm)
                        }
                        "secret" -> secret = v
                    }
                }
            }
            if (relays.isEmpty()) {
                relays.addAll(listOf("wss://relay.nsec.app", "wss://relay.damus.io", "wss://nos.lol"))
            }
            ParsedBunkerConfig(
                remoteSignerPubkeyHex = pubHex,
                relays = relays.distinct(),
                secret = secret
            )
        }.getOrNull()
    }
}
