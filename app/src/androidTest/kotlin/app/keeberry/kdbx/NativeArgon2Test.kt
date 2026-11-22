package app.keeberry.kdbx

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeArgon2Test {
    // Fail rather than quietly passing on the Java implementation if the native library is missing.
    private val native = NativeArgon2(maxMemory = 1L shl 30, fallback = object : Argon2Kdf {
        override fun derive(
            key: ByteArray,
            job: Argon2Job,
            checkCancelled: () -> Unit,
            onProgress: (Float) -> Unit,
        ): ByteArray = throw AssertionError("Native Argon2 fell back to BouncyCastle")
    })

    @Test
    fun matchesBouncyCastleAcrossTypesVersionsAndLanes() {
        val random = Random(42)
        for (type in Argon2Type.entries) for (version in listOf(0x10, 0x13)) for (lanes in listOf(1, 3, 8)) {
            val key = random.nextBytes(32)
            // 1027 KiB is not a multiple of 4 × lanes, so both sides must round memory the same way.
            val job = Argon2Job(type, version, random.nextBytes(16), 1027, 3, lanes)
            assertArrayEquals("$type v$version with $lanes lanes",
                BouncyCastleArgon2().derive(key, job), native.derive(key, job))
        }
    }

    @Test
    fun matchesBouncyCastleWithSecretAndAssociatedData() {
        val random = Random(7)
        for (type in Argon2Type.entries) {
            val key = random.nextBytes(32)
            val job = Argon2Job(type, 0x13, random.nextBytes(16), 1024, 2, 4,
                secret = random.nextBytes(32), additional = random.nextBytes(12))
            assertArrayEquals("$type", BouncyCastleArgon2().derive(key, job), native.derive(key, job))
        }
    }

    @Test
    fun opensArgon2FixtureDatabases() {
        val reader = KdbxReader(native)
        for (file in listOf("modern-argon2id.kdbx", "modern-argon2d-chacha.kdbx")) {
            fixture(file).use { input ->
                reader.open(input, "fixture-only".toCharArray()).use { database ->
                    assertEquals(listOf("Sample entry", "Second entry"), database.entries.map { it.title })
                }
            }
        }
    }

    @Test
    fun runsDesktopTunedSettingsTheJavaFallbackRejects() {
        // KeePassXC's 64 MiB default with the kind of pass count a fast desktop picks for one second.
        val job = Argon2Job(Argon2Type.D, 0x13, ByteArray(32), 64 * 1024, 100, 8)
        assertEquals(32, native.derive(ByteArray(32), job).size)
    }

    @Test
    fun stopsAfterTheCurrentSliceAndFreesItsMemoryWhenCancelled() {
        for (lanes in listOf(1, 4)) {
            // 1,000 passes over 256 MiB would take minutes if cancelling had no effect.
            val job = Argon2Job(Argon2Type.D, 0x13, ByteArray(32), 256 * 1024, 1000, lanes)
            val before = Debug.getNativeHeapAllocatedSize()
            var checks = 0
            assertThrows(Cancelled::class.java) {
                native.derive(ByteArray(32), job, checkCancelled = { if (++checks == 3) throw Cancelled() })
            }
            assertEquals("checks with $lanes lanes", 3, checks)
            // Upstream Argon2 kept the whole memory block when a run stopped early.
            val retained = Debug.getNativeHeapAllocatedSize() - before
            assertTrue("$retained bytes still allocated with $lanes lanes", retained < 16L * 1024 * 1024)
        }
    }

    @Test
    fun reportsLowFreeMemoryApartFromTheLimit() {
        val tight = NativeArgon2(maxMemory = 1L shl 30, freeMemory = { 1L shl 20 })
        val error = assertThrows(KdbxException::class.java) {
            tight.derive(ByteArray(32), Argon2Job(Argon2Type.ID, 0x13, ByteArray(16), 64 * 1024, 1, 2))
        }
        assertEquals(KdbxFailure.MEMORY, error.failure)
    }

    @Test
    fun reportsProgressAfterEveryQuarterPass() {
        val job = Argon2Job(Argon2Type.ID, 0x13, ByteArray(16), 1024, 5, 2)
        val reported = mutableListOf<Float>()
        native.derive(ByteArray(32), job, onProgress = { reported += it })
        assertEquals(List(20) { (it + 1) / 20f }, reported)
    }

    private class Cancelled : RuntimeException()

    private fun fixture(name: String): InputStream = checkNotNull(javaClass.getResourceAsStream("/kdbx/$name"))
}
