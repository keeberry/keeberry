package app.keeberry.kdbx

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun combinesPasswordKeyFileAndYubiKeyLikeTheReader() {
        val keyFile = newKeyFile()
        val challenges = mutableListOf<ByteArray>()
        val bytes = ByteArrayOutputStream().also {
            writer.create(it, "Personal", "secret".toCharArray(), { keyFile.inputStream() }) { challenge ->
                challenges += challenge.copyOf()
                yubiKey(challenge)
            }
        }.toByteArray()
        // The challenge is the KDF salt padded to 64 bytes, as KeePassXC sends it.
        assertEquals(64, challenges.single().size)
        assertTrue(challenges.single().copyOfRange(32, 64).all { it == 0x20.toByte() })

        reader.open(bytes.inputStream(), "secret".toCharArray(), keyFile.inputStream(), ::yubiKey).use {
            assertEquals("Personal", it.name)
        }
        val credentials = listOf<() -> Database>(
            { reader.open(bytes.inputStream(), "secret".toCharArray(), keyFile.inputStream()) },
            { reader.open(bytes.inputStream(), "secret".toCharArray(), null, ::yubiKey) },
            { reader.open(bytes.inputStream(), null, keyFile.inputStream(), ::yubiKey) },
            { reader.open(bytes.inputStream(), "secret".toCharArray(), newKeyFile().inputStream(), ::yubiKey) },
        )
        for (open in credentials) {
            assertEquals(KdbxFailure.CREDENTIALS, assertThrows(KdbxException::class.java) { open().close() }.failure)
        }
    }

    @Test
    fun writesDatabasesWithoutAPassword() {
        val keyFile = newKeyFile()
        val keyOnly = ByteArrayOutputStream().also { writer.create(it, "Key", null, { keyFile.inputStream() }) }
        reader.open(keyOnly.toByteArray().inputStream(), null, keyFile.inputStream()).use { assertEquals("Key", it.name) }
        // No password is not the same as an empty one.
        assertThrows(KdbxException::class.java) {
            reader.open(keyOnly.toByteArray().inputStream(), CharArray(0), keyFile.inputStream()).close()
        }

        val yubiKeyOnly = ByteArrayOutputStream().also { writer.create(it, "YubiKey", null, null, ::yubiKey) }
        reader.open(yubiKeyOnly.toByteArray().inputStream(), null, null, ::yubiKey).use { assertEquals("YubiKey", it.name) }

        assertThrows(IllegalArgumentException::class.java) { writer.create(ByteArrayOutputStream(), "None", null) }
    }

    @Test
    fun closesTheKeyFileItReads() {
        var closed = false
        val keyFile = object : java.io.FilterInputStream(newKeyFile().inputStream()) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        writer.create(ByteArrayOutputStream(), "Personal", null, { keyFile })
        assertTrue(closed)
    }

    @Test
    fun generatesKeePassXmlKeyFilesTheReaderAccepts() {
        val first = newKeyFile()
        val text = first.decodeToString()
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<KeyFile>"))
        assertTrue("<Version>2.0</Version>" in text)
        val data = Regex("<Data Hash=\"([0-9A-F]{8})\">\\s*((?:[0-9A-F]{8}\\s+){8})</Data>").find(text)
        checkNotNull(data) { text }
        val key = data.groupValues[2].filterNot(Char::isWhitespace).decodeHex()
        assertEquals(data.groupValues[1], sha256(key).hexString(4).uppercase())
        assertArrayEquals(key, readKeyFile(first.inputStream()))
        assertNotEquals(key.toList(), readKeyFile(newKeyFile().inputStream()).toList())
    }

    private fun newKeyFile(): ByteArray = ByteArrayOutputStream().also { writeKeyFile(it) }.toByteArray()

    private fun yubiKey(challenge: ByteArray): ByteArray = Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(ByteArray(20) { (it + 1).toByte() }, "HmacSHA1"))
        doFinal(challenge)
    }

    private fun create(name: String): ByteArray =
        ByteArrayOutputStream().also { writer.create(it, name, "secret".toCharArray()) }.toByteArray()
}
