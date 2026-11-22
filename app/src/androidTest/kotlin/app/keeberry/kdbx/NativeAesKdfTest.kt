package app.keeberry.kdbx

import android.os.SystemClock
import android.util.Log
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
class NativeAesKdfTest {
    // Fail rather than quietly passing on the Java implementation if the native path is unavailable.
    private val native = NativeAesKdf(fallback = object : AesKdf {
        override fun transform(
            key: ByteArray,
            seed: ByteArray,
            rounds: Int,
            checkCancelled: () -> Unit,
            onProgress: (Float) -> Unit,
        ) = throw AssertionError("Native AES-KDF fell back to Java")
    })

    @Test
    fun matchesThePlatformAesAcrossRoundCounts() {
        val random = Random(3)
        // Includes zero and counts that end partway through a native slice.
        for (rounds in listOf(0, 1, 2, 7, 1000, (1 shl 20) + 3)) {
            val seed = random.nextBytes(32)
            val start = random.nextBytes(32)
            val expected = start.copyOf().also { JcaAesKdf.transform(it, seed, rounds, {}, {}) }
            val actual = start.copyOf().also { native.transform(it, seed, rounds, {}, {}) }
            assertArrayEquals("$rounds rounds", expected, actual)
        }
    }

    @Test
    fun opensAesKdfFixtureDatabases() {
        val reader = KdbxReader(aesKdf = native)
        for (file in listOf("password.kdbx", "modern-aes-kdf.kdbx", "modern-twofish.kdbx")) {
            fixture(file).use { input ->
                reader.open(input, "fixture-only".toCharArray()).use { database ->
                    assertEquals(file, "Sample entry", database.entries.first().title)
                }
            }
        }
    }

    @Test
    fun stopsBetweenSlicesWhenCancelled() {
        var checks = 0
        assertThrows(Cancelled::class.java) {
            native.transform(ByteArray(32), ByteArray(32), Int.MAX_VALUE, { if (++checks == 3) throw Cancelled() }, {})
        }
        assertEquals(3, checks)
    }

    @Test
    fun isFasterThanThePlatformAes() {
        val rounds = 2_000_000
        val seed = ByteArray(32) { it.toByte() }
        val platform = time { JcaAesKdf.transform(ByteArray(32), seed, rounds, {}, {}) }
        val hardware = time { native.transform(ByteArray(32), seed, rounds, {}, {}) }
        Log.i("NativeAesKdfTest", "AES-KDF ns/round: platform ${platform * 1e6 / rounds}, native ${hardware * 1e6 / rounds}")
        assertTrue("native $hardware ms, platform $platform ms", hardware < platform)
    }

    private fun time(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private class Cancelled : RuntimeException()

    private fun fixture(name: String): InputStream = checkNotNull(javaClass.getResourceAsStream("/kdbx/$name"))
}
