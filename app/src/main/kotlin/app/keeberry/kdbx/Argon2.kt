package app.keeberry.kdbx

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

internal enum class Argon2Type { D, ID }

internal class Argon2Job(
    val type: Argon2Type,
    val version: Int,
    val salt: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val lanes: Int,
    val secret: ByteArray? = null,
    val additional: ByteArray? = null,
) {
    val memoryBytes: Long get() = memoryKiB * 1024L

    // Memory filled across all passes, which is what the run time scales with.
    val workKiB: Long get() = memoryKiB.toLong() * iterations
}

internal interface Argon2Kdf {
    /**
     * Returns the 32-byte transformed key, or throws [KdbxFailure.LIMIT] when [job] is over budget.
     * Implementations that can stop partway call [checkCancelled] while they run and let what it
     * throws propagate, and report the fraction done to [onProgress].
     */
    fun derive(
        key: ByteArray,
        job: Argon2Job,
        checkCancelled: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): ByteArray
}

/**
 * Pure Java Argon2 that fills every lane on the calling thread and the Java heap, so it gets a small
 * budget. It cannot stop or report partway, so it calls neither callback.
 */
internal class BouncyCastleArgon2(
    private val maxMemory: Long = minOf(Runtime.getRuntime().maxMemory() / 2, 512L * 1024 * 1024),
) : Argon2Kdf {
    override fun derive(
        key: ByteArray,
        job: Argon2Job,
        checkCancelled: () -> Unit,
        onProgress: (Float) -> Unit,
    ): ByteArray {
        checkKdbx(job.memoryBytes <= maxMemory && job.lanes <= 64 && job.iterations <= 100 &&
            job.workKiB <= 2L * 1024 * 1024, KdbxFailure.LIMIT)
        val type = when (job.type) {
            Argon2Type.D -> Argon2Parameters.ARGON2_d
            Argon2Type.ID -> Argon2Parameters.ARGON2_id
        }
        val builder = Argon2Parameters.Builder(type)
            .withVersion(job.version)
            .withSalt(job.salt)
            .withMemoryAsKB(job.memoryKiB)
            .withIterations(job.iterations)
            .withParallelism(job.lanes)
        job.secret?.let { builder.withSecret(it) }
        job.additional?.let { builder.withAdditional(it) }
        val params = builder.build()
        val output = ByteArray(32)
        var success = false
        try {
            val generator = Argon2BytesGenerator()
            generator.init(params)
            generator.generateBytes(key, output)
            success = true
            return output
        } finally {
            if (!success) output.fill(0)
            params.clear()
            builder.clear()
        }
    }
}

/**
 * The reference C Argon2 that KeePassXC also uses, built from `src/main/cpp`. It runs one thread per
 * lane and allocates outside the Java heap, so databases tuned for a desktop open in seconds rather
 * than minutes. After every quarter pass it calls checkCancelled and reports progress, so there is
 * no cap on passes. [fallback] is used only if the native library fails to load.
 */
internal class NativeArgon2(
    private val maxMemory: Long,
    private val freeMemory: () -> Long = { Long.MAX_VALUE },
    private val fallback: Argon2Kdf = BouncyCastleArgon2(),
) : Argon2Kdf {
    override fun derive(
        key: ByteArray,
        job: Argon2Job,
        checkCancelled: () -> Unit,
        onProgress: (Float) -> Unit,
    ): ByteArray {
        checkKdbx(job.memoryBytes <= maxMemory && job.lanes <= 64, KdbxFailure.LIMIT)
        if (!NativeCrypto.loaded) return fallback.derive(key, job, checkCancelled, onProgress)
        // Android rarely fails an allocation. It ends the process instead, so check first.
        checkKdbx(job.memoryBytes <= freeMemory(), KdbxFailure.MEMORY)
        val type = when (job.type) {
            Argon2Type.D -> ARGON2_D
            Argon2Type.ID -> ARGON2_ID
        }
        val slices = job.iterations * 4L
        var done = 0L
        val afterSlice = Runnable {
            // An exception from checkCancelled stops the native run and is rethrown from hash.
            checkCancelled()
            done++
            onProgress(done.toFloat() / slices)
        }
        val output = ByteArray(32)
        val result = Argon2Jni.hash(type, job.version, job.iterations, job.memoryKiB, job.lanes, key,
            job.salt, job.secret, job.additional, output, afterSlice)
        when (result) {
            ARGON2_OK -> return output
            // Parameters are validated up front, so what is left is running out of memory or threads.
            ARGON2_MEMORY_ALLOCATION_ERROR, ARGON2_THREAD_FAIL -> throw KdbxException(KdbxFailure.LIMIT)
            else -> throw KdbxException(KdbxFailure.CORRUPT)
        }
    }

    private companion object {
        // Values from argon2.h.
        const val ARGON2_D = 0
        const val ARGON2_ID = 2
        const val ARGON2_OK = 0
        const val ARGON2_MEMORY_ALLOCATION_ERROR = -22
        const val ARGON2_THREAD_FAIL = -33
    }
}

/** The native library built from `src/main/cpp`: Argon2 and the hardware AES-KDF. */
internal object NativeCrypto {
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("keeberry_crypto")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}

/** Implemented in `src/main/cpp/argon2_jni.c`. Returns an argon2.h error code. */
private object Argon2Jni {
    @JvmStatic
    external fun hash(
        type: Int,
        version: Int,
        passes: Int,
        memoryKiB: Int,
        lanes: Int,
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        additional: ByteArray?,
        output: ByteArray,
        cancel: Runnable,
    ): Int
}
