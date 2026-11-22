package app.keyberry.kdbx

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.io.CipherInputStream as BlockCipherInputStream
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/** The slow steps of an unlock, reported on the thread that calls [KdbxReader.open]. */
internal sealed interface KdbxStage {
    /** [percent] is null until the KDF reports how far it has got, and stays null if it cannot. */
    data class DerivingKey(val percent: Int?) : KdbxStage

    data object Decrypting : KdbxStage
}

internal class KdbxReader(
    private val argon2: Argon2Kdf = BouncyCastleArgon2(),
    private val checkCancelled: () -> Unit = {},
    private val chaCha20: (ByteArray, ByteArray) -> Cipher = ::platformChaCha20,
    private val aesKdf: AesKdf = JcaAesKdf,
    private val onStage: (KdbxStage) -> Unit = {},
) {
    fun open(
        input: InputStream,
        password: CharArray?,
        keyFile: InputStream? = null,
        challengeResponse: ((ByteArray) -> ByteArray)? = null,
    ): Database = guarded(input) { source -> open(source, password, keyFile, challengeResponse) }

    /**
     * Opens a database whose files can be read more than once. An empty [password] next to a key file
     * or YubiKey usually means there is none, so like KeePassXC this first opens without one and
     * retries with the empty password only if the credentials are rejected. The YubiKey is asked
     * once, and its response reused for the retry.
     */
    fun unlock(
        database: () -> InputStream,
        password: CharArray?,
        keyFile: (() -> InputStream)? = null,
        challengeResponse: ((ByteArray) -> ByteArray)? = null,
    ): Database {
        val optional = password != null && password.isEmpty() && (keyFile != null || challengeResponse != null)
        if (!optional) return attempt(database, password, keyFile, challengeResponse)
        val responses = challengeResponse?.let(::CachedResponse)
        try {
            return try {
                attempt(database, null, keyFile, responses?.let { it::respond })
            } catch (error: KdbxException) {
                if (error.failure != KdbxFailure.CREDENTIALS) throw error
                attempt(database, password, keyFile, responses?.let { it::respond })
            }
        } finally {
            responses?.clear()
        }
    }

    private fun attempt(
        database: () -> InputStream,
        password: CharArray?,
        keyFile: (() -> InputStream)?,
        challengeResponse: ((ByteArray) -> ByteArray)?,
    ): Database = database().use { input ->
        if (keyFile == null) open(input, password, null, challengeResponse)
        else keyFile().use { open(input, password, it, challengeResponse) }
    }

    /**
     * Opens a KDBX 4 database with the master key kept from an earlier unlock, as a save does to read
     * the file it is about to replace. Credentials that changed since then fail as [KdbxFailure.CREDENTIALS].
     */
    fun open(input: InputStream, key: KdbxKey): Database = decryptWithKey(input, key) { header, payload ->
        readPayload(payload, header, null).withKey(key.copy())
    }

    /** Like [open] with a key, but hands the decrypted, still compressed payload to [read]. */
    fun <T> decryptWithKey(input: InputStream, key: KdbxKey, read: (KdbxHeader, InputStream) -> T): T =
        guarded(input) { source ->
            val header = KdbxHeader.read(source)
            checkKdbx(header.major == 4, KdbxFailure.UNSUPPORTED)
            decrypt(source, header, null, { key.transformed() }) { payload, _ -> read(header, payload) }
        }

    private fun <T> guarded(input: InputStream, read: (DataInputStream) -> T): T {
        try {
            return DataInputStream(BoundedInput(BufferedInputStream(input), MAX_STREAM_BYTES, checkCancelled))
                .use(read)
        } catch (_: EOFException) {
            throw KdbxException(KdbxFailure.CORRUPT)
        } catch (_: ZipException) {
            throw KdbxException(KdbxFailure.CORRUPT)
        } catch (_: GeneralSecurityException) {
            throw KdbxException(KdbxFailure.UNSUPPORTED)
        }
    }

    private fun open(
        source: DataInputStream,
        password: CharArray?,
        keyFile: InputStream?,
        challengeResponse: ((ByteArray) -> ByteArray)?,
    ): Database {
        val header = KdbxHeader.read(source)
        var kept: KdbxKey? = null
        try {
            val database = decrypt(source, header, challengeResponse, {
                transformKey(header, password, keyFile, argon2, aesKdf, checkCancelled, challengeResponse, onStage)
                    // Only KDBX 4 can be saved, and its YubiKey response is already in the derived key.
                    .also { if (header.major == 4) kept = KdbxKey(it.copyOf()) }
            }) { payload, legacyHeaderHash -> readPayload(payload, header, legacyHeaderHash) }
            val key = kept ?: return database
            kept = null
            return database.withKey(key)
        } finally {
            kept?.close()
        }
    }

    /**
     * Checks the header, derives the key with [transform] and passes the decrypted payload to [read],
     * with the header hash a KDBX 3 payload has to repeat.
     */
    private fun <T> decrypt(
        source: DataInputStream,
        header: KdbxHeader,
        challengeResponse: ((ByteArray) -> ByteArray)?,
        transform: () -> ByteArray,
        read: (InputStream, ByteArray?) -> T,
    ): T {
        val cipherId = header.cipherId.hexString()
        val masterSeed = header.masterSeed
        val iv = header.iv
        val ivLength = when (cipherId) {
            AES, TWOFISH -> 16
            CHACHA20 -> 12
            else -> throw KdbxException(KdbxFailure.UNSUPPORTED)
        }
        checkKdbx(iv.size == ivLength)
        val headerHash = sha256(header.encoded)
        val expectedMac = if (header.major == 4) {
            checkKdbx(MessageDigest.isEqual(headerHash, source.takeBytes(32)))
            source.takeBytes(32)
        } else null
        val transformed = transform()
        try {
            val encryptionKey = MessageDigest.getInstance("SHA-256").apply {
                update(masterSeed)
                // KDBX 3 uses the master seed as its challenge and adds the response after the KDF.
                if (header.major == 3 && challengeResponse != null) {
                    checkCancelled()
                    updateYubiKeyResponse(masterSeed, challengeResponse)
                    checkCancelled()
                }
            }.digest(transformed)
            onStage(KdbxStage.Decrypting)
            val decrypting = try {
                decryptingStream(cipherId, encryptionKey, iv)
            } finally {
                encryptionKey.fill(0)
            }
            if (expectedMac == null) {
                return decrypting(source).use { clear ->
                    val legacy = DataInputStream(clear)
                    checkKdbx(MessageDigest.isEqual(legacy.takeBytes(32), header.legacyStartBytes),
                        KdbxFailure.CREDENTIALS)
                    KdbxBlocks(legacy, null).use { read(it, headerHash) }
                }
            }
            val hmacKey = MessageDigest.getInstance("SHA-512").apply {
                update(masterSeed)
                update(transformed)
            }.digest(byteArrayOf(1))
            try {
                verifyHeaderMac(header.encoded, hmacKey, expectedMac)
                return KdbxBlocks(source, hmacKey).use { ciphertext ->
                    decrypting(ciphertext).use { read(it, null) }
                }
            } finally {
                hmacKey.fill(0)
            }
        } finally {
            transformed.fill(0)
        }
    }

    /** Wraps ciphertext in the outer cipher. Every cipher keeps its own copy of [key]. */
    private fun decryptingStream(cipherId: String, key: ByteArray, iv: ByteArray): (InputStream) -> InputStream =
        when (cipherId) {
            AES -> platformStream(Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            })
            TWOFISH -> twofishStream(key, iv)
            else -> platformStream(chaCha20(key, iv))
        }

    private fun platformStream(cipher: Cipher): (InputStream) -> InputStream = { CipherInputStream(it, cipher) }

    // Android has no Twofish provider, so this uses BouncyCastle directly. Padding errors surface as
    // IOExceptions, which only a wrong key or a damaged KDBX 3 file can cause.
    private fun twofishStream(key: ByteArray, iv: ByteArray): (InputStream) -> InputStream {
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(TwofishEngine()), PKCS7Padding())
        cipher.init(false, ParametersWithIV(KeyParameter(key), iv))
        return { BlockCipherInputStream(it, cipher) }
    }

    private fun verifyHeaderMac(bytes: ByteArray, hmacKey: ByteArray, expected: ByteArray) {
        val key = MessageDigest.getInstance("SHA-512").apply { update(littleLong(-1)) }.digest(hmacKey)
        try {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            checkKdbx(MessageDigest.isEqual(mac.doFinal(bytes), expected), KdbxFailure.CREDENTIALS)
        } finally {
            key.fill(0)
        }
    }

    private fun readPayload(stream: InputStream, header: KdbxHeader, legacyHeaderHash: ByteArray?): Database {
        val decompressed = if (header.compression == 1) GZIPInputStream(stream, 8192) else stream
        val payload = DataInputStream(BoundedInput(decompressed, MAX_STREAM_BYTES, checkCancelled))
        try {
            val decrypt = if (header.major == 3) {
                innerStream(header.legacyStreamId, header.legacyStreamKey, chaCha20)
            } else readInnerHeader(payload)
            val database = readDatabaseXml(payload, decrypt, legacyHeaderHash)
            var success = false
            try {
                payload.drain()
                if (decompressed !== stream) stream.drain()
                success = true
                return database
            } finally {
                if (!success) database.close()
            }
        } finally {
            payload.close()
        }
    }

    private fun readInnerHeader(source: DataInputStream): ((ByteArray) -> Unit)? {
        var id: Int? = null
        var key: ByteArray? = null
        try {
            while (true) {
                val field = source.readUnsignedByte()
                val size = source.readLittleInt()
                checkKdbx(size >= 0)
                when (field) {
                    0 -> {
                        checkKdbx(size == 0)
                        val streamId = id ?: throw KdbxException(KdbxFailure.CORRUPT)
                        return innerStream(streamId, key ?: ByteArray(0), chaCha20)
                    }
                    1 -> {
                        checkKdbx(size == 4 && id == null)
                        id = source.readLittleInt()
                    }
                    2 -> {
                        checkKdbx(key == null && size in 1..1024)
                        key = source.takeBytes(size)
                    }
                    else -> skipField(source, size)
                }
            }
        } finally {
            key?.fill(0)
        }
    }

    private fun skipField(source: DataInputStream, size: Int) {
        var remaining = size
        while (remaining > 0) {
            val skipped = source.skipBytes(remaining)
            if (skipped == 0) {
                source.readUnsignedByte()
                remaining--
            } else remaining -= skipped
        }
    }

    private companion object {
        const val AES = "31c1f2e6bf714350be5805216afc5aff"
        const val CHACHA20 = "d6038a2b8b6f4cb5a524339a31dbb59a"
        const val TWOFISH = "ad68f29f576f4bb9a36ad47af965346c"
        const val MAX_STREAM_BYTES = 256L * 1024 * 1024
    }
}

/**
 * The inner stream that hides protected values, as a function that XORs its keystream into a value in
 * place, both ways. Null for a database that doesn't protect values.
 */
internal fun innerStream(id: Int, key: ByteArray, chaCha20: (ByteArray, ByteArray) -> Cipher): ((ByteArray) -> Unit)? {
    if (id == 0) return null
    checkKdbx(key.isNotEmpty())
    return when (id) {
        2 -> {
            val digest = sha256(key)
            val salsa20 = Salsa20Engine()
            try {
                salsa20.init(false, ParametersWithIV(KeyParameter(digest),
                    byteArrayOf(0xe8.toByte(), 0x30, 0x09, 0x4b, 0x97.toByte(), 0x20, 0x5d, 0x2a)))
            } finally {
                digest.fill(0)
            }
            { bytes: ByteArray ->
                salsa20.processBytes(bytes, 0, bytes.size, bytes, 0)
                Unit
            }
        }
        3 -> {
            val digest = MessageDigest.getInstance("SHA-512").digest(key)
            val cipherKey = digest.copyOfRange(0, 32)
            val cipher = try {
                chaCha20(cipherKey, digest.copyOfRange(32, 44))
            } finally {
                digest.fill(0)
                cipherKey.fill(0)
            }
            { bytes: ByteArray -> checkKdbx(cipher.update(bytes, 0, bytes.size, bytes, 0) == bytes.size) }
        }
        else -> throw KdbxException(KdbxFailure.UNSUPPORTED)
    }
}

/** Replays a hardware key's response to the same challenge, so a retry needs no second touch. */
private class CachedResponse(private val ask: (ByteArray) -> ByteArray) {
    private var challenge: ByteArray? = null
    private var response: ByteArray? = null

    // The reader wipes each response it gets, so this hands out copies.
    fun respond(challenge: ByteArray): ByteArray {
        val cached = response
        if (cached != null && challenge.contentEquals(this.challenge)) return cached.copyOf()
        clear()
        val fresh = ask(challenge)
        this.challenge = challenge.copyOf()
        response = fresh.copyOf()
        return fresh
    }

    fun clear() {
        response?.fill(0)
        response = null
        challenge = null
    }
}

internal fun platformChaCha20(key: ByteArray, iv: ByteArray): Cipher =
    Cipher.getInstance("ChaCha20/NONE/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(iv))
    }
