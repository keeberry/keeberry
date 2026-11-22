package app.keyberry.kdbx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class Argon2Test {
    private val key = ByteArray(32)

    @Test
    fun javaArgon2RejectsDatabasesTunedOnADesktop() {
        // KeePassXC's 64 MiB default with more passes than the Java implementation will run.
        assertLimit { BouncyCastleArgon2().derive(key, job(memoryKiB = 64 * 1024, iterations = 33)) }
    }

    @Test
    fun nativeArgon2CapsMemoryAndLanes() {
        val native = NativeArgon2(maxMemory = 256L * 1024 * 1024)
        assertLimit { native.derive(key, job(memoryKiB = 512 * 1024, iterations = 1)) }
        assertLimit { native.derive(key, job(memoryKiB = 1024, iterations = 1, lanes = 65)) }
    }

    @Test
    fun nativeArgon2FallsBackWhenTheLibraryIsMissing() {
        // The JVM test runtime has no Android native library, so this exercises the fallback path.
        val fallback = object : Argon2Kdf {
            var job: Argon2Job? = null
            override fun derive(
                key: ByteArray,
                job: Argon2Job,
                checkCancelled: () -> Unit,
                onProgress: (Float) -> Unit,
            ): ByteArray {
                this.job = job
                return ByteArray(32)
            }
        }
        val job = job(memoryKiB = 1024, iterations = 1)
        NativeArgon2(maxMemory = 256L * 1024 * 1024, fallback = fallback).derive(key, job)
        assertSame(job, fallback.job)
    }

    private fun job(memoryKiB: Int, iterations: Int, lanes: Int = 4) =
        Argon2Job(Argon2Type.D, 0x13, ByteArray(32), memoryKiB, iterations, lanes)

    private fun assertLimit(block: () -> Unit) {
        assertEquals(KdbxFailure.LIMIT, assertThrows(KdbxException::class.java, block).failure)
    }
}
