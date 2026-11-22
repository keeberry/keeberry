package app.keyberry.kdbx

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KdbxWriterTest {
    // The JVM names the initial counter explicitly. Android Conscrypt starts at zero.
    private val jvmChaCha = { key: ByteArray, nonce: ByteArray ->
        Cipher.getInstance("ChaCha20").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, 0))
        }
    }

    private val reader = KdbxReader(chaCha20 = jvmChaCha)

    // Small enough for a fast test; the format is the same at any size.
    private val writer = KdbxWriter(kdf = NewDatabaseKdf(memoryKiB = 1024, iterations = 2, lanes = 2))

    @Test
    fun writesAnEmptyKdbx4DatabaseTheReaderOpens() {
        val bytes = create("Personal")
        val header = KdbxHeader.read(DataInputStream(bytes.inputStream()))
        assertEquals(4, header.major)
        assertEquals("31c1f2e6bf714350be5805216afc5aff", header.cipherId.hexString())
        assertEquals(1, header.compression)
        reader.open(bytes.inputStream(), "secret".toCharArray()).use { database ->
            assertEquals("Personal", database.name)
            assertTrue(database.entries.isEmpty())
        }
    }

    @Test
    fun rejectsAnyOtherPassword() {
        val bytes = create("Personal")
        for (password in listOf("Secret", "", "secret ")) {
            val error = assertThrows(KdbxException::class.java) {
                reader.open(bytes.inputStream(), password.toCharArray()).close()
            }
            assertEquals(KdbxFailure.CREDENTIALS, error.failure)
        }
    }

    @Test
    fun escapesTheNameAndDropsCharactersXmlCannotHold() {
        create("Tom & Jerry's <\"keys\">\u0001\uFFFF").let { bytes ->
            reader.open(bytes.inputStream(), "secret".toCharArray()).use {
                assertEquals("Tom & Jerry's <\"keys\">", it.name)
            }
        }
    }

    @Test
    fun usesFreshKeysForEveryDatabase() {
        val first = create("Personal")
        val second = create("Personal")
        // Master seed, IV and KDF salt are all random, so even the headers differ.
        assertNotEquals(first.copyOf(200).toList(), second.copyOf(200).toList())
    }

    @Test
    fun reportsKeyDerivationAndStopsWhenCancelled() {
        val stages = mutableListOf<KdbxStage>()
        KdbxWriter(kdf = NewDatabaseKdf(memoryKiB = 1024, iterations = 2, lanes = 2), onStage = stages::add)
            .create(ByteArrayOutputStream(), "Personal", "secret".toCharArray())
        assertEquals(KdbxStage.DerivingKey(null), stages.first())

        class Cancelled : RuntimeException()
        val output = ByteArrayOutputStream()
        assertThrows(Cancelled::class.java) {
            KdbxWriter(checkCancelled = { throw Cancelled() }).create(output, "Personal", "secret".toCharArray())
        }
        assertEquals(0, output.size())
    }

    private fun create(name: String): ByteArray =
        ByteArrayOutputStream().also { writer.create(it, name, "secret".toCharArray()) }.toByteArray()
}
