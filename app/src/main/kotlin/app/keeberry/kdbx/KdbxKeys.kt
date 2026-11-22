package app.keeberry.kdbx

import android.annotation.SuppressLint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal fun transformKey(
    header: KdbxHeader,
    password: CharArray?,
    keyFile: InputStream?,
    argon2: Argon2Kdf,
    aesKdf: AesKdf,
    checkCancelled: () -> Unit,
    challengeResponse: ((ByteArray) -> ByteArray)?,
    onStage: (KdbxStage) -> Unit = {},
): ByteArray {
    val kdf = if (header.major == 4) KdfDictionary(header.kdfParameters) else null
    val digest = MessageDigest.getInstance("SHA-256")
    if (password != null) {
        val encoded = utf8(CharBuffer.wrap(password))
        try {
            val passwordHash = sha256(encoded)
            digest.update(passwordHash)
            passwordHash.fill(0)
        } finally {
            encoded.fill(0)
        }
    }
    if (keyFile != null) {
        val fileKey = readKeyFile(BoundedInput(keyFile, 256L * 1024 * 1024, checkCancelled))
        digest.update(fileKey)
        fileKey.fill(0)
    }
    // KeePassXC adds the response to the composite key before the KDF in KDBX 4.
    if (kdf != null && challengeResponse != null) {
        checkCancelled()
        digest.updateYubiKeyResponse(kdf.bytes("S"), challengeResponse)
        checkCancelled()
    }
    val compositeKey = digest.digest()
    try {
        val progress = KdfProgress(onStage)
        if (kdf == null) {
            return runAesKdf(compositeKey, header.legacyTransformSeed, header.legacyRounds, aesKdf,
                checkCancelled, progress)
        }
        return when (kdf.algorithm) {
            KdfAlgorithm.AES ->
                runAesKdf(compositeKey, kdf.bytes("S"), kdf.long("R"), aesKdf, checkCancelled, progress)
            KdfAlgorithm.ARGON2D -> runArgon2(compositeKey, kdf, Argon2Type.D, argon2, checkCancelled, progress)
            KdfAlgorithm.ARGON2ID -> runArgon2(compositeKey, kdf, Argon2Type.ID, argon2, checkCancelled, progress)
        }
    } finally {
        compositeKey.fill(0)
    }
}

/** Reports key derivation in whole percent, so even a fast KDF sends at most about a hundred updates. */
private class KdfProgress(private val onStage: (KdbxStage) -> Unit) {
    private var percent = -1

    init {
        onStage(KdbxStage.DerivingKey(null))
    }

    fun report(fraction: Float) {
        val now = (fraction.coerceIn(0f, 1f) * 100).toInt()
        if (now <= percent) return
        percent = now
        onStage(KdbxStage.DerivingKey(now))
    }
}

internal fun MessageDigest.updateYubiKeyResponse(seed: ByteArray, respond: (ByteArray) -> ByteArray) {
    // Match KeePassXC's 64-byte challenge padding, including for fixed-length slots.
    checkKdbx(seed.size in 1..64, KdbxFailure.UNSUPPORTED)
    val challenge = ByteArray(64) { (64 - seed.size).toByte() }
    seed.copyInto(challenge)
    val response = respond(challenge)
    try {
        checkKdbx(response.size == 20, KdbxFailure.CREDENTIALS)
        val responseHash = sha256(response)
        update(responseHash)
        responseHash.fill(0)
    } finally {
        response.fill(0)
    }
}

private fun runAesKdf(
    key: ByteArray,
    seed: ByteArray,
    rounds: Long,
    engine: AesKdf,
    checkCancelled: () -> Unit,
    progress: KdfProgress,
): ByteArray {
    checkKdbx(seed.size == 32)
    // Cancelling bounds the time, so the cap only keeps the count within an Int.
    checkKdbx(rounds in 0..Int.MAX_VALUE, KdbxFailure.LIMIT)
    val state = key.copyOf()
    try {
        engine.transform(state, seed, rounds.toInt(), checkCancelled, progress::report)
        progress.report(1f)
        return sha256(state)
    } finally {
        state.fill(0)
    }
}

/** AES-KDF: encrypts both 16-byte halves of a 32-byte key with AES-256-ECB, over and over. */
internal interface AesKdf {
    /**
     * Applies [rounds] rounds to [key] in place under [seed]. Calls [checkCancelled] and reports the
     * fraction done to [onProgress] often enough to stop within a fraction of a second.
     */
    fun transform(key: ByteArray, seed: ByteArray, rounds: Int, checkCancelled: () -> Unit, onProgress: (Float) -> Unit)
}

/** The platform's AES, one call per round. Every call crosses into native code, which dominates. */
// AES-KDF is defined as repeated single-block AES encryption, which is ECB on one block at a time.
// No data is encrypted this way, so the usual reason to avoid ECB doesn't apply.
@SuppressLint("GetInstance")
internal object JcaAesKdf : AesKdf {
    override fun transform(
        key: ByteArray,
        seed: ByteArray,
        rounds: Int,
        checkCancelled: () -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
        val scratch = ByteArray(32)
        try {
            for (round in 0 until rounds) {
                if (round and 4095 == 0) {
                    checkCancelled()
                    onProgress(round.toFloat() / rounds)
                }
                checkKdbx(cipher.update(key, 0, 32, scratch, 0) == 32)
                scratch.copyInto(key)
            }
        } finally {
            scratch.fill(0)
        }
    }
}

/**
 * AES-KDF on the CPU's AES instructions, from `src/main/cpp`, in slices that leave room to cancel
 * and report progress. [fallback] runs when the library or the instructions are missing.
 */
internal class NativeAesKdf(private val fallback: AesKdf = JcaAesKdf) : AesKdf {
    override fun transform(
        key: ByteArray,
        seed: ByteArray,
        rounds: Int,
        checkCancelled: () -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        if (!supported) return fallback.transform(key, seed, rounds, checkCancelled, onProgress)
        var done = 0
        while (done < rounds) {
            checkCancelled()
            val slice = minOf(SLICE_ROUNDS, rounds - done)
            checkKdbx(AesKdfJni.transform(seed, key, slice))
            done += slice
            onProgress(done.toFloat() / rounds)
        }
    }

    private companion object {
        // Tens of milliseconds on a phone with AES instructions.
        const val SLICE_ROUNDS = 1 shl 20

        val supported: Boolean by lazy { NativeCrypto.loaded && AesKdfJni.supported() }
    }
}

/** Implemented in `src/main/cpp/aes_kdf_jni.c`. */
private object AesKdfJni {
    @JvmStatic
    external fun supported(): Boolean

    /** Applies [rounds] rounds to the 32-byte [key] in place. Returns false for bad arguments. */
    @JvmStatic
    external fun transform(seed: ByteArray, key: ByteArray, rounds: Int): Boolean
}

private fun runArgon2(
    key: ByteArray,
    kdf: KdfDictionary,
    type: Argon2Type,
    argon2: Argon2Kdf,
    checkCancelled: () -> Unit,
    progress: KdfProgress,
): ByteArray {
    val memory = kdf.long("M")
    val passes = kdf.long("I")
    val lanes = kdf.int("P")
    val version = kdf.int("V")
    val salt = kdf.bytes("S")
    checkKdbx(version == 0x10 || version == 0x13, KdbxFailure.UNSUPPORTED)
    checkKdbx(salt.size >= 8 && lanes > 0 && passes > 0 && memory >= 8L * lanes * 1024)
    // Each implementation applies its own budget; this only keeps the values within an Int.
    checkKdbx(memory / 1024 <= Int.MAX_VALUE && passes <= Int.MAX_VALUE, KdbxFailure.LIMIT)
    val job = Argon2Job(type, version, salt, (memory / 1024).toInt(), passes.toInt(), lanes,
        kdf.optionalBytes("K"), kdf.optionalBytes("A"))

    checkCancelled()
    val output = argon2.derive(key, job, checkCancelled, progress::report)
    var success = false
    try {
        checkCancelled()
        success = true
        return output
    } finally {
        if (!success) output.fill(0)
    }
}

/**
 * KDBX 4 KDF parameters [encoded] with a new random salt of the same length in place of "S", and
 * everything else, settings and unknown items alike, as it was and in the same order.
 */
internal fun kdfParametersWithNewSalt(encoded: ByteArray, random: SecureRandom): ByteArray {
    KdfDictionary(encoded) // Checks the version and that the KDF is one KeeBerry runs.
    val stream = DataInputStream(ByteArrayInputStream(encoded))
    val output = ByteArrayOutputStream(encoded.size)
    output.write(stream.takeBytes(2))
    var salted = false
    while (true) {
        val type = stream.readUnsignedByte()
        output.write(type)
        if (type == 0) break
        val name = stream.takeBytes(stream.readLittleInt())
        var value = stream.takeBytes(stream.readLittleInt())
        if (name.contentEquals(byteArrayOf('S'.code.toByte()))) {
            checkKdbx(type == 0x42 && value.isNotEmpty())
            value = ByteArray(value.size).also(random::nextBytes)
            salted = true
        }
        for (part in listOf(name, value)) {
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(part.size).array())
            output.write(part)
        }
    }
    checkKdbx(salted)
    return output.toByteArray()
}

private enum class KdfAlgorithm { AES, ARGON2D, ARGON2ID }

private class KdfDictionary(encoded: ByteArray) {
    private class Item(val type: Int, val value: ByteArray)

    private val items = mutableMapOf<String, Item>()
    val algorithm: KdfAlgorithm

    init {
        val stream = DataInputStream(ByteArrayInputStream(encoded))
        val version = stream.takeBytes(2).littleEndian().short.toInt() and 0xffff
        checkKdbx(version ushr 8 == 1, KdbxFailure.UNSUPPORTED)
        while (true) {
            val type = stream.readUnsignedByte()
            if (type == 0) break
            val name = field(stream).toString(Charsets.UTF_8)
            checkKdbx(name.isNotEmpty() && items.put(name, Item(type, field(stream))) == null)
        }
        checkKdbx(stream.read() == -1)
        algorithm = when (bytes("\$UUID").hexString()) {
            "c9d9f39a628a4460bf740d08c18a4fea" -> KdfAlgorithm.AES
            "ef636ddf8c29444b91f7a9a403e30a0c" -> KdfAlgorithm.ARGON2D
            "9e298b1956db4773b23dfc3ec6f0a1e6" -> KdfAlgorithm.ARGON2ID
            else -> throw KdbxException(KdbxFailure.UNSUPPORTED)
        }
    }

    fun bytes(name: String): ByteArray = item(name, 0x42)
    fun optionalBytes(name: String): ByteArray? = if (items.containsKey(name)) bytes(name) else null
    fun long(name: String): Long = item(name, 0x05, 8).littleEndian().long
    fun int(name: String): Int = item(name, 0x04, 4).littleEndian().int

    private fun item(name: String, type: Int, size: Int? = null): ByteArray {
        val item = items[name] ?: throw KdbxException(KdbxFailure.CORRUPT)
        checkKdbx(item.type == type && (size == null || item.value.size == size))
        return item.value
    }

    private fun field(stream: DataInputStream): ByteArray {
        val length = stream.readLittleInt()
        checkKdbx(length in 0..stream.available())
        return stream.takeBytes(length)
    }
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun utf8(chars: CharSequence): ByteArray {
    val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
    return try {
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } finally {
        if (encoded.hasArray()) encoded.array().fill(0)
    }
}
