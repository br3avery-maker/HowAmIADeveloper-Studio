package com.example.nostr

import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object NostrCrypto {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val EXP_SQRT = P.add(BigInteger.ONE).shiftRight(2)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val FOUR = BigInteger.valueOf(4)
    private val SEVEN = BigInteger.valueOf(7)
    private val EIGHT = BigInteger.valueOf(8)
    private val secureRandom = SecureRandom()

    private data class AffinePoint(val x: BigInteger, val y: BigInteger)
    private data class JacobianPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val isInfinity: Boolean get() = z == BigInteger.ZERO
    }

    private val G = AffinePoint(GX, GY)
    private val JACOBIAN_INFINITY = JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)

    data class KeyPairHex(
        val privKeyHex: String,
        val pubKeyHex: String,
        val nsec: String,
        val npub: String
    )

    fun generateKeyPair(): KeyPairHex {
        var privBytes = ByteArray(32)
        while (true) {
            secureRandom.nextBytes(privBytes)
            val d = BigInteger(1, privBytes)
            if (d >= BigInteger.ONE && d < N) {
                val privHex = privBytes.toHex()
                val pubHex = derivePublicKeyHex(privHex)
                return KeyPairHex(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    nsec = encodeBech32("nsec", privBytes),
                    npub = encodeBech32("npub", pubHex.hexToBytes())
                )
            }
        }
    }

    fun importPrivateKey(input: String): KeyPairHex {
        val cleaned = input.trim()
        val privBytes = when {
            cleaned.startsWith("nsec1", ignoreCase = true) -> {
                val (hrp, data) = decodeBech32(cleaned)
                require(hrp == "nsec" && data.size == 32) { "Invalid nsec Bech32 key" }
                data
            }
            cleaned.length == 64 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> {
                cleaned.hexToBytes()
            }
            else -> throw IllegalArgumentException("Enter a valid nsec1… key or 64-char hex secret key")
        }
        val d = BigInteger(1, privBytes)
        require(d >= BigInteger.ONE && d < N) { "Secret key scalar out of secp256k1 range" }
        val privHex = privBytes.toHex()
        val pubHex = derivePublicKeyHex(privHex)
        return KeyPairHex(
            privKeyHex = privHex,
            pubKeyHex = pubHex,
            nsec = encodeBech32("nsec", privBytes),
            npub = encodeBech32("npub", pubHex.hexToBytes())
        )
    }

    fun derivePublicKeyHex(privKeyHex: String): String {
        val d0 = BigInteger(1, privKeyHex.hexToBytes())
        require(d0 >= BigInteger.ONE && d0 < N) { "Invalid secret key" }
        val pt = scalarMult(d0, G) ?: error("Point at infinity")
        return pt.x.toBytes32().toHex()
    }

    fun signSchnorrHex(msg32Hex: String, privKeyHex: String): String {
        val msg = msg32Hex.hexToBytes()
        require(msg.size == 32) { "Message hash must be 32 bytes" }
        val d0 = BigInteger(1, privKeyHex.hexToBytes())
        require(d0 >= BigInteger.ONE && d0 < N) { "Secret key out of range" }
        val pubPoint = scalarMult(d0, G) ?: error("Invalid public point")
        val d = if (!pubPoint.y.testBit(0)) d0 else N.subtract(d0)

        val aux = ByteArray(32).also { secureRandom.nextBytes(it) }
        val auxHash = taggedHash("BIP0340/aux", aux)
        val dBytes = d.toBytes32()
        val t = ByteArray(32) { i -> (dBytes[i].toInt() xor auxHash[i].toInt()).toByte() }
        val pxBytes = pubPoint.x.toBytes32()

        val nonceHash = taggedHash("BIP0340/nonce", t + pxBytes + msg)
        val k0 = BigInteger(1, nonceHash).mod(N)
        require(k0 != BigInteger.ZERO) { "Zero nonce" }
        val rPoint = scalarMult(k0, G) ?: error("Invalid nonce point")
        val k = if (!rPoint.y.testBit(0)) k0 else N.subtract(k0)
        val rxBytes = rPoint.x.toBytes32()

        val eHash = taggedHash("BIP0340/challenge", rxBytes + pxBytes + msg)
        val e = BigInteger(1, eHash).mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        return (rxBytes + s.toBytes32()).toHex()
    }

    fun verifySchnorrHex(msg32Hex: String, pubKeyHex: String, sigHex: String): Boolean {
        return runCatching {
            val msg = msg32Hex.hexToBytes()
            val pubBytes = pubKeyHex.hexToBytes()
            val sigBytes = sigHex.hexToBytes()
            if (msg.size != 32 || pubBytes.size != 32 || sigBytes.size != 64) return false

            val pubPoint = liftX(BigInteger(1, pubBytes)) ?: return false
            val rBytes = sigBytes.copyOfRange(0, 32)
            val sBytes = sigBytes.copyOfRange(32, 64)
            val r = BigInteger(1, rBytes)
            val s = BigInteger(1, sBytes)
            if (r >= P || s >= N) return false

            val e = BigInteger(1, taggedHash("BIP0340/challenge", rBytes + pubBytes + msg)).mod(N)
            val sG = toJacobian(scalarMult(s, G) ?: return false)
            val negEP = toJacobian(scalarMult(N.subtract(e).mod(N), pubPoint) ?: return false)
            val rCheck = toAffine(jacobianAdd(sG, negEP)) ?: return false
            !rCheck.y.testBit(0) && rCheck.x == r
        }.getOrDefault(false)
    }

    fun nip04Encrypt(privKeyHex: String, peerPubKeyHex: String, plaintext: String): String {
        val sharedKey = computeSharedSecret(privKeyHex, peerPubKeyHex)
        val iv = ByteArray(16).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedKey, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ctB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        return "$ctB64?iv=$ivB64"
    }

    fun nip04Decrypt(privKeyHex: String, peerPubKeyHex: String, payload: String): String {
        val parts = payload.split("?iv=")
        require(parts.size == 2) { "Malformed NIP-04 payload" }
        val sharedKey = computeSharedSecret(privKeyHex, peerPubKeyHex)
        val ct = Base64.decode(parts[0], Base64.DEFAULT)
        val iv = Base64.decode(parts[1], Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedKey, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /**
     * NIP-44 v2 versioned authenticated encryption (HKDF-SHA256 conversation key +
     * RFC 8439 ChaCha20 + length-prefixed padded plaintext + HMAC-SHA256 MAC).
     */
    fun nip44Encrypt(privKeyHex: String, peerPubKeyHex: String, plaintext: String): String {
        val rawBytes = plaintext.toByteArray(Charsets.UTF_8)
        require(rawBytes.isNotEmpty() && rawBytes.size <= 65535) { "NIP-44 plaintext length out of range" }
        val conversationKey = nip44ConversationKey(privKeyHex, peerPubKeyHex)
        val nonce = ByteArray(32).also { secureRandom.nextBytes(it) }
        val (chachaKey, chachaNonce, hmacKey) = nip44MessageKeys(conversationKey, nonce)

        val paddedLen = nip44CalcPaddedLen(rawBytes.size)
        val padded = ByteArray(2 + paddedLen)
        padded[0] = ((rawBytes.size ushr 8) and 0xff).toByte()
        padded[1] = (rawBytes.size and 0xff).toByte()
        System.arraycopy(rawBytes, 0, padded, 2, rawBytes.size)

        val ciphertext = chacha20Xor(chachaKey, chachaNonce, padded)
        val mac = hmacSha256(hmacKey, nonce + ciphertext)
        val envelope = ByteArray(1 + 32 + ciphertext.size + 32)
        envelope[0] = 0x02
        System.arraycopy(nonce, 0, envelope, 1, 32)
        System.arraycopy(ciphertext, 0, envelope, 33, ciphertext.size)
        System.arraycopy(mac, 0, envelope, 33 + ciphertext.size, 32)
        return Base64.encodeToString(envelope, Base64.NO_WRAP)
    }

    fun nip44Decrypt(privKeyHex: String, peerPubKeyHex: String, payloadBase64: String): String {
        val raw = Base64.decode(payloadBase64.trim(), Base64.DEFAULT)
        require(raw.size >= 99 && raw[0].toInt() == 2) { "Unsupported or malformed NIP-44 v2 payload" }
        val nonce = raw.copyOfRange(1, 33)
        val ciphertext = raw.copyOfRange(33, raw.size - 32)
        val macGiven = raw.copyOfRange(raw.size - 32, raw.size)

        val conversationKey = nip44ConversationKey(privKeyHex, peerPubKeyHex)
        val (chachaKey, chachaNonce, hmacKey) = nip44MessageKeys(conversationKey, nonce)
        val macExpected = hmacSha256(hmacKey, nonce + ciphertext)
        require(MessageDigest.isEqual(macGiven, macExpected)) { "NIP-44 HMAC verification failed" }

        val padded = chacha20Xor(chachaKey, chachaNonce, ciphertext)
        val unpaddedLen = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(unpaddedLen in 1..(padded.size - 2)) { "Invalid NIP-44 unpadded length" }
        return String(padded, 2, unpaddedLen, Charsets.UTF_8)
    }

    private fun nip44ConversationKey(privKeyHex: String, peerPubKeyHex: String): ByteArray {
        val sharedX = computeSharedSecret(privKeyHex, peerPubKeyHex)
        return hmacSha256("nip44-v2".toByteArray(Charsets.UTF_8), sharedX)
    }

    private fun nip44MessageKeys(conversationKey: ByteArray, nonce32: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(conversationKey.size == 32 && nonce32.size == 32)
        // HKDF-Expand(PRK = conversationKey, info = nonce32, L = 76)
        val t1 = hmacSha256(conversationKey, nonce32 + byteArrayOf(0x01))
        val t2 = hmacSha256(conversationKey, t1 + nonce32 + byteArrayOf(0x02))
        val t3 = hmacSha256(conversationKey, t2 + nonce32 + byteArrayOf(0x03))
        val okm = t1 + t2 + t3.copyOfRange(0, 12)
        return Triple(
            okm.copyOfRange(0, 32),
            okm.copyOfRange(32, 44),
            okm.copyOfRange(44, 76)
        )
    }

    private fun nip44CalcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        val nextPower = Integer.highestOneBit(unpaddedLen - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * (((unpaddedLen - 1) / chunk) + 1)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun chacha20Xor(key32: ByteArray, nonce12: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        val keyWords = IntArray(8)
        ByteBuffer.wrap(key32).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(keyWords)
        val nonceWords = IntArray(3)
        ByteBuffer.wrap(nonce12).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(nonceWords)

        var counter = 0
        var offset = 0
        val blockBytes = ByteArray(64)
        while (offset < input.size) {
            val state = intArrayOf(
                0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
                keyWords[0], keyWords[1], keyWords[2], keyWords[3],
                keyWords[4], keyWords[5], keyWords[6], keyWords[7],
                counter, nonceWords[0], nonceWords[1], nonceWords[2]
            )
            val working = state.clone()
            repeat(10) {
                quarterRound(working, 0, 4, 8, 12)
                quarterRound(working, 1, 5, 9, 13)
                quarterRound(working, 2, 6, 10, 14)
                quarterRound(working, 3, 7, 11, 15)
                quarterRound(working, 0, 5, 10, 15)
                quarterRound(working, 1, 6, 11, 12)
                quarterRound(working, 2, 7, 8, 13)
                quarterRound(working, 3, 4, 9, 14)
            }
            val buf = ByteBuffer.wrap(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 16) buf.putInt(working[i] + state[i])
            val take = minOf(64, input.size - offset)
            for (i in 0 until take) {
                out[offset + i] = (input[offset + i].toInt() xor blockBytes[i].toInt()).toByte()
            }
            offset += take
            counter++
        }
        return out
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun computeSharedSecret(privKeyHex: String, peerPubKeyHex: String): ByteArray {
        val d = BigInteger(1, privKeyHex.hexToBytes())
        val peerX = BigInteger(1, peerPubKeyHex.trim().removePrefix("02").removePrefix("03").hexToBytes())
        val peerPoint = liftX(peerX) ?: error("Invalid peer public key")
        val shared = scalarMult(d, peerPoint) ?: error("Invalid ECDH shared point")
        return shared.x.toBytes32()
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    fun sha256Hex(text: String): String = sha256(text.toByteArray(Charsets.UTF_8)).toHex()

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        val md = MessageDigest.getInstance("SHA-256")
        md.update(tagHash)
        md.update(tagHash)
        md.update(msg)
        return md.digest()
    }

    private fun liftX(x: BigInteger): AffinePoint? {
        if (x < BigInteger.ZERO || x >= P) return null
        val c = x.modPow(THREE, P).add(SEVEN).mod(P)
        val y = c.modPow(EXP_SQRT, P)
        if (y.modPow(TWO, P) != c) return null
        val evenY = if (y.testBit(0)) P.subtract(y) else y
        return AffinePoint(x, evenY)
    }

    private fun toJacobian(p: AffinePoint): JacobianPoint = JacobianPoint(p.x, p.y, BigInteger.ONE)

    private fun toAffine(p: JacobianPoint): AffinePoint? {
        if (p.isInfinity) return null
        val zInv = p.z.modInverse(P)
        val zInv2 = zInv.multiply(zInv).mod(P)
        val zInv3 = zInv2.multiply(zInv).mod(P)
        val x = p.x.multiply(zInv2).mod(P)
        val y = p.y.multiply(zInv3).mod(P)
        return AffinePoint(x, y)
    }

    private fun jacobianDouble(p: JacobianPoint): JacobianPoint {
        if (p.isInfinity || p.y == BigInteger.ZERO) return JACOBIAN_INFINITY
        val y2 = p.y.multiply(p.y).mod(P)
        val s = FOUR.multiply(p.x).multiply(y2).mod(P)
        val m = THREE.multiply(p.x.multiply(p.x).mod(P)).mod(P)
        val x3 = m.multiply(m).subtract(TWO.multiply(s)).mod(P)
        val y4 = y2.multiply(y2).mod(P)
        val y3 = m.multiply(s.subtract(x3)).subtract(EIGHT.multiply(y4)).mod(P)
        val z3 = TWO.multiply(p.y).multiply(p.z).mod(P)
        return JacobianPoint(x3, y3, z3)
    }

    private fun jacobianAdd(p: JacobianPoint, q: JacobianPoint): JacobianPoint {
        if (p.isInfinity) return q
        if (q.isInfinity) return p
        val z1z1 = p.z.multiply(p.z).mod(P)
        val z2z2 = q.z.multiply(q.z).mod(P)
        val u1 = p.x.multiply(z2z2).mod(P)
        val u2 = q.x.multiply(z1z1).mod(P)
        val s1 = p.y.multiply(q.z).multiply(z2z2).mod(P)
        val s2 = q.y.multiply(p.z).multiply(z1z1).mod(P)
        if (u1 == u2) {
            return if (s1 != s2) JACOBIAN_INFINITY else jacobianDouble(p)
        }
        val h = u2.subtract(u1).mod(P)
        val r = s2.subtract(s1).mod(P)
        val hh = h.multiply(h).mod(P)
        val hhh = h.multiply(hh).mod(P)
        val v = u1.multiply(hh).mod(P)
        val x3 = r.multiply(r).subtract(hhh).subtract(TWO.multiply(v)).mod(P)
        val y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(hhh)).mod(P)
        val z3 = p.z.multiply(q.z).multiply(h).mod(P)
        return JacobianPoint(x3, y3, z3)
    }

    private fun scalarMult(k: BigInteger, point: AffinePoint): AffinePoint? {
        if (k == BigInteger.ZERO) return null
        var res = JACOBIAN_INFINITY
        var addend = toJacobian(point)
        var scalar = k
        while (scalar > BigInteger.ZERO) {
            if (scalar.testBit(0)) {
                res = jacobianAdd(res, addend)
            }
            addend = jacobianDouble(addend)
            scalar = scalar.shiftRight(1)
        }
        return toAffine(res)
    }

    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    const val DEFAULT_FEATURED_CREATOR_NPUB =
        "npub1s06cx40d060vq887lj8hj6hku5a6hzch5ylkzj03qv86dn02tqhs0wzl0d"

    val DEFAULT_FEATURED_CREATOR_PUBKEY_HEX: String by lazy {
        decodeNpubToHex(DEFAULT_FEATURED_CREATOR_NPUB).orEmpty()
    }

    fun encodeNpub(pubKeyHex: String): String = encodeBech32("npub", pubKeyHex.hexToBytes())
    fun encodeNsec(privKeyHex: String): String = encodeBech32("nsec", privKeyHex.hexToBytes())

    fun decodeNpubToHex(npub: String): String? = runCatching {
        val cleaned = npub.trim().removePrefix("nostr:").removePrefix("NOSTR:")
        val (hrp, bytes) = decodeBech32(cleaned)
        if (hrp == "npub" && bytes.size == 32) bytes.toHex() else null
    }.getOrNull()

    /**
     * Resolves any Nostr public key representation (`npub1...`, `nostr:npub1...`, `nprofile1...`,
     * or raw 64-character hex pubkey) into a canonical lowercase 64-char hex pubkey.
     */
    fun resolvePubkeyHex(rawInput: String): String? {
        val cleaned = rawInput.trim()
            .removePrefix("nostr:")
            .removePrefix("NOSTR:")
            .trim()
        if (cleaned.isBlank()) return null

        if (cleaned.length == 64 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return cleaned.lowercase()
        }
        if (cleaned.startsWith("npub1", ignoreCase = true)) {
            return decodeNpubToHex(cleaned)
        }
        if (cleaned.startsWith("nprofile1", ignoreCase = true)) {
            return runCatching {
                val (hrp, tlv) = decodeBech32(cleaned)
                if (hrp != "nprofile") return@runCatching null
                var pos = 0
                while (pos + 2 <= tlv.size) {
                    val t = tlv[pos].toInt() and 0xFF
                    val len = tlv[pos + 1].toInt() and 0xFF
                    pos += 2
                    if (pos + len > tlv.size) break
                    if (t == 0 && len == 32) {
                        return@runCatching tlv.copyOfRange(pos, pos + 32).toHex()
                    }
                    pos += len
                }
                null
            }.getOrNull()
        }
        return null
    }

    /**
     * NIP-5A `pubkeyB36` encoder: encodes a raw 32-byte Nostr public key as a fixed-width
     * 50-character lowercase base36 string (`0-9a-z`), left-padded with `'0'` so `<pubkeyB36><dTag>`
     * fits inside a single 63-character DNS label (`*.nsite.lol`) with a deterministic 50-char split.
     */
    fun encodePubkeyBase36(pubKeyHex: String): String {
        val pubBytes = pubKeyHex.trim().lowercase().hexToBytes()
        require(pubBytes.size == 32) { "Public key must be 32 bytes (64 hex chars)" }
        return BigInteger(1, pubBytes).toString(36).lowercase().padStart(50, '0')
    }

    /**
     * Decodes a 50-character lowercase base36 NIP-5A `pubkeyB36` back into a 64-char hex pubkey.
     */
    fun decodePubkeyBase36(pubkeyB36: String): String {
        val cleaned = pubkeyB36.trim().lowercase()
        require(cleaned.length == 50 && cleaned.all { it in '0'..'9' || it in 'a'..'z' }) {
            "pubkeyB36 must be 50 lowercase base36 characters"
        }
        val value = BigInteger(cleaned, 36)
        require(value >= BigInteger.ZERO && value.bitLength() <= 256) {
            "pubkeyB36 value exceeds 32-byte unsigned range"
        }
        return value.toBytes32().toHex()
    }

    /**
     * NIP-19 TLV Bech32 coordinate encoder (`naddr1...`) for addressable Napplets (35129) & Nsites (35128).
     */
    fun encodeNaddr(kind: Int, pubKeyHex: String, dTag: String): String {
        val dBytes = dTag.toByteArray(Charsets.UTF_8)
        val pubBytes = runCatching { pubKeyHex.hexToBytes() }
            .getOrNull()
            ?.takeIf { it.size == 32 }
            ?: ByteArray(32)
        val kindBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(kind).array()
        val tlv = ByteArray(2 + dBytes.size + 2 + 32 + 2 + 4)
        var pos = 0
        // Type 0: d-tag identifier
        tlv[pos++] = 0x00
        tlv[pos++] = dBytes.size.toByte()
        System.arraycopy(dBytes, 0, tlv, pos, dBytes.size)
        pos += dBytes.size
        // Type 2: 32-byte author pubkey
        tlv[pos++] = 0x02
        tlv[pos++] = 32
        System.arraycopy(pubBytes, 0, tlv, pos, 32)
        pos += 32
        // Type 3: 32-bit unsigned big-endian kind
        tlv[pos++] = 0x03
        tlv[pos++] = 4
        System.arraycopy(kindBytes, 0, tlv, pos, 4)
        return encodeBech32("naddr", tlv)
    }

    data class DecodedNaddr(
        val kind: Int,
        val pubkeyHex: String,
        val dTag: String,
        val relays: List<String> = emptyList()
    )

    /**
     * Decodes a NIP-19 `naddr1...` Bech32 TLV coordinate into `(kind, pubkeyHex, dTag, relays)`.
     */
    fun decodeNaddr(rawInput: String): DecodedNaddr? = runCatching {
        val cleaned = rawInput.trim()
            .removePrefix("nostr:")
            .removePrefix("NOSTR:")
            .trim()
        val (hrp, tlv) = decodeBech32(cleaned)
        if (hrp != "naddr") return@runCatching null

        var dTag: String? = null
        var pubkeyHex: String? = null
        var kind: Int? = null
        val relays = mutableListOf<String>()

        var pos = 0
        while (pos + 2 <= tlv.size) {
            val type = tlv[pos].toInt() and 0xFF
            val len = tlv[pos + 1].toInt() and 0xFF
            pos += 2
            if (pos + len > tlv.size) break
            val slice = tlv.copyOfRange(pos, pos + len)
            when (type) {
                0 -> dTag = String(slice, Charsets.UTF_8)
                1 -> {
                    val url = String(slice, Charsets.UTF_8).trim()
                    if (url.startsWith("wss://", ignoreCase = true) || url.startsWith("ws://", ignoreCase = true)) {
                        relays.add(url)
                    }
                }
                2 -> if (len == 32) pubkeyHex = slice.toHex()
                3 -> if (len == 4) {
                    kind = ByteBuffer.wrap(slice).order(ByteOrder.BIG_ENDIAN).int
                }
            }
            pos += len
        }
        val pk = pubkeyHex ?: return@runCatching null
        val k = kind ?: return@runCatching null
        DecodedNaddr(
            kind = k,
            pubkeyHex = pk.lowercase(),
            dTag = dTag ?: "",
            relays = relays.distinct()
        )
    }.getOrNull()

    fun encodeBech32(hrp: String, data: ByteArray): String {
        val words = convertBits(data, 8, 5, true)
        val checksum = createBech32Checksum(hrp, words)
        val combined = words + checksum
        return buildString {
            append(hrp)
            append('1')
            for (w in combined) append(BECH32_CHARSET[w])
        }
    }

    fun decodeBech32(bech: String): Pair<String, ByteArray> {
        val lower = bech.trim().lowercase()
        val pos = lower.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= lower.length) { "Malformed Bech32 string" }
        val hrp = lower.substring(0, pos)
        val values = IntArray(lower.length - pos - 1) { idx ->
            val ch = lower[pos + 1 + idx]
            val v = BECH32_CHARSET.indexOf(ch)
            require(v >= 0) { "Invalid Bech32 character: $ch" }
            v
        }
        require(verifyBech32Checksum(hrp, values)) { "Bech32 checksum failed" }
        val payloadWords = values.copyOfRange(0, values.size - 6)
        val bytes = convertBitsToBytes(payloadWords, 5, 8, false)
        return hrp to bytes
    }

    private fun bech32Polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) {
                if (((b ushr i) and 1) != 0) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun verifyBech32Checksum(hrp: String, data: IntArray): Boolean =
        bech32Polymod(hrpExpand(hrp) + data) == 1

    private fun createBech32Checksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val polymod = bech32Polymod(values) xor 1
        return IntArray(6) { i -> (polymod ushr (5 * (5 - i))) and 31 }
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val ret = ArrayList<Int>()
        val maxv = (1 shl toBits) - 1
        for (b in data) {
            val value = b.toInt() and 0xff
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add((acc ushr bits) and maxv)
            }
        }
        if (pad && bits > 0) {
            ret.add((acc shl (toBits - bits)) and maxv)
        }
        return ret.toIntArray()
    }

    private fun convertBitsToBytes(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val ret = ArrayList<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (pad && bits > 0) {
            ret.add(((acc shl (toBits - bits)) and maxv).toByte())
        }
        return ret.toByteArray()
    }

    private fun BigInteger.toBytes32(): ByteArray {
        val raw = this.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToBytes(): ByteArray {
        val s = this.trim()
        require(s.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
