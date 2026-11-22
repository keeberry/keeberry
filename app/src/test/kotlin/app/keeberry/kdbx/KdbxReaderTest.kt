package app.keeberry.kdbx

import java.io.InputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KdbxReaderTest {
    // The JVM names the initial counter explicitly. Android Conscrypt starts at zero.
    private val jvmChaCha = { key: ByteArray, nonce: ByteArray ->
        Cipher.getInstance("ChaCha20").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, 0))
        }
    }

    private val reader = KdbxReader(chaCha20 = jvmChaCha)

    @Test
    fun opensKeePassXcKdbx31WithPasswordAndKeyFileCombinations() {
        open("password.kdbx").use { assertSampleEntry(it.entries.single()) }
        fixture("fixture.keyx").use { key ->
            open("password-key.kdbx", keyFile = key).use { assertSampleEntry(it.entries.single()) }
        }
        fixture("fixture.keyx").use { key ->
            open("key-only.kdbx", password = null, keyFile = key).use { assertSampleEntry(it.entries.single()) }
        }
    }

    @Test
    fun opensKdbx40And41AcrossKdfsAndCiphers() {
        // These OpenSSL-generated fixtures were also opened by KeePassXC.
        // modern-twofish.kdbx is modern-aes-kdf.kdbx re-encrypted with Twofish, which KeePassXC opens.
        for (file in listOf("modern-argon2id.kdbx", "modern-argon2d-chacha.kdbx", "modern-aes-kdf.kdbx",
            "modern-twofish.kdbx")) {
            open(file).use { database ->
                assertEquals("Fixture vault", database.name)
                assertEquals(listOf("Sample entry", "Second entry"), database.entries.map { it.title })
                assertEquals("Fixture group", database.entries.first().group)
                assertSampleEntry(database.entries.first())
                // A protected history value precedes this field in the inner stream.
                assertEquals("second-password", database.entries.last().field("Password").text())
            }
        }
    }

    @Test
    fun rejectsWrongCredentialsForBothFormats() {
        for (file in listOf("password.kdbx", "modern-argon2id.kdbx", "modern-twofish.kdbx")) {
            val error = assertThrows(KdbxException::class.java) {
                open(file, password = "wrong".toCharArray()).close()
            }
            assertEquals(KdbxFailure.CREDENTIALS, error.failure)
        }
    }

    @Test
    fun opensLegacyDatabaseUsingYubiKeyAlone() {
        fixture("yubikey-legacy.kdbx").use { database ->
            reader.open(database, password = null) { challenge ->
                // Legacy KeePassXC challenges the master seed after AES-KDF.
                assertArrayEquals(paddedChallenge(0x20), challenge)
                mockYubiKey(challenge)
            }.use { assertSampleEntry(it.entries.single()) }
        }
    }

    @Test
    fun opensModernDatabaseUsingPasswordKeyFileAndYubiKey() {
        fixture("yubikey-modern.kdbx").use { database ->
            modernKeyFile().use { keyFile ->
                reader.open(database, password(), keyFile) { challenge ->
                    // A variable-length slot calculates HMAC over the original KDF seed.
                    assertArrayEquals(paddedChallenge(0x40), challenge)
                    mockYubiKey(challenge.copyOf(32))
                }.use { assertSampleEntry(it.entries.single()) }
            }
        }
    }

    @Test
    fun modernDatabaseRejectsWrongOrMissingYubiKey() {
        val wrongSlot = assertThrows(KdbxException::class.java) {
            fixture("yubikey-modern.kdbx").use { database ->
                modernKeyFile().use { keyFile ->
                    reader.open(database, password(), keyFile) { ByteArray(20) }.close()
                }
            }
        }
        assertEquals(KdbxFailure.CREDENTIALS, wrongSlot.failure)
        val missingKey = assertThrows(KdbxException::class.java) {
            modernKeyFile().use { keyFile -> open("yubikey-modern.kdbx", keyFile = keyFile).close() }
        }
        assertEquals(KdbxFailure.CREDENTIALS, missingKey.failure)
    }

    @Test
    fun skipsProtectedAttachmentsLargerThanTheTextLimit() {
        // A KDBX 3 attachment over 4 MiB once encoded, not a multiple of three bytes so it ends in
        // padding, and line-wrapped. The password after it only decrypts if the stream skipped it exactly.
        val stream = salsa20()
        val attachment = Random(1).nextBytes(5 * 1024 * 1024 + 1)
        val xml = "<KeePassFile><Meta><Binaries><Binary ID=\"0\" Protected=\"True\">" +
            protect(stream, attachment) + "</Binary></Binaries></Meta><Root><Group><Name>Root</Name><Entry>" +
            "<String><Key>Title</Key><Value>After</Value></String><String><Key>Password</Key>" +
            "<Value Protected=\"True\">${protect(stream, "after-attachment".toByteArray())}</Value></String>" +
            "</Entry></Group></Root></KeePassFile>"
        val decrypt = salsa20()
        readDatabaseXml(xml.byteInputStream(), { decrypt.processBytes(it, 0, it.size, it, 0) }, null).use { database ->
            assertEquals("after-attachment", database.entries.single().field("Password").text())
        }
    }

    @Test
    fun rejectsMalformedBase64InSkippedProtectedValues() {
        val xml = "<KeePassFile><Meta><Binaries><Binary Protected=\"True\">abc</Binary></Binaries></Meta>" +
            "<Root/></KeePassFile>"
        val decrypt = salsa20()
        val error = assertThrows(KdbxException::class.java) {
            readDatabaseXml(xml.byteInputStream(), { decrypt.processBytes(it, 0, it.size, it, 0) }, null).close()
        }
        assertEquals(KdbxFailure.CORRUPT, error.failure)
    }

    @Test
    fun keepsEntriesInTheRecycleBinApart() {
        // Created by keepassxc-cli: "Deleted entry" was removed, and the "Old" group with "Old entry" in it.
        open("recycle-bin.kdbx").use { database ->
            assertEquals(listOf("Live entry", "Work entry"), database.entries.map { it.title }.sorted())
            assertEquals("work-user", database.entries.single { it.title == "Work entry" }.field("UserName").text())
            assertTrue(database.entries.none { it.group.contains("Recycle Bin") })
            assertEquals(listOf("Deleted entry", "Old entry"), database.binned.map { it.title }.sorted())
            // KeePassXC noted that both came from the root group, the entry in "Old" by way of its group.
            assertEquals(listOf("Passwords", "Passwords"), database.binned.map { it.group })
            assertTrue(database.binned.all { it.uuid != null })
        }
    }

    @Test
    fun namesTheGroupARestorePutsABinnedEntryIn() {
        fun uuid(id: Int) = Base64.getEncoder().encodeToString(ByteArray(16) { id.toByte() })
        fun entry(title: String, previous: Int?) = "<Entry><UUID>${uuid(title.length + 40)}</UUID>" +
            (previous?.let { "<PreviousParentGroup>${uuid(it)}</PreviousParentGroup>" } ?: "") +
            "<String><Key>Title</Key><Value>$title</Value></String></Entry>"
        val xml = "<KeePassFile><Meta><RecycleBinUUID>${uuid(3)}</RecycleBinUUID></Meta><Root>" +
            "<Group><UUID>${uuid(1)}</UUID><Name>Root</Name>" +
            "<Group><UUID>${uuid(2)}</UUID><Name>Work</Name></Group>" +
            "<Group><UUID>${uuid(3)}</UUID><Name>Recycle Bin</Name>" +
            entry("From work", 2) + entry("Unknown origin", null) + entry("Gone group", 9) +
            "<Group><UUID>${uuid(4)}</UUID><Name>Old</Name>" + entry("From the bin", 4) + "</Group>" +
            "</Group></Group></Root></KeePassFile>"
        readDatabaseXml(xml.byteInputStream(), null, null).use { database ->
            assertEquals(emptyList<String>(), database.entries.map { it.title })
            // A group that is gone or in the bin itself leaves the root group.
            assertEquals(
                mapOf("From work" to "Root / Work", "Unknown origin" to "Root", "Gone group" to "Root", "From the bin" to "Root"),
                database.binned.associate { it.title to it.group },
            )
            assertEquals(listOf("Root", "Root / Work"), database.groups.map { it.path })
        }
    }

    @Test
    fun keepsEveryEntryWhenTheDatabaseHasNoRecycleBin() {
        val xml = "<KeePassFile><Meta><RecycleBinUUID>AAAAAAAAAAAAAAAAAAAAAA==</RecycleBinUUID></Meta>" +
            "<Root><Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><Name>Root</Name>" +
            "<Entry><String><Key>Title</Key><Value>Kept</Value></String></Entry></Group></Root></KeePassFile>"
        readDatabaseXml(xml.byteInputStream(), null, null).use { database ->
            assertEquals(listOf("Kept"), database.entries.map { it.title })
        }
    }

    @Test
    fun emptyPasswordNextToAKeyFileMeansNoPassword() {
        // KeePassXC leaves out an empty password, so this database only opens without one.
        reader.unlock({ fixture("key-only.kdbx") }, CharArray(0), { fixture("fixture.keyx") })
            .use { assertSampleEntry(it.entries.single()) }
    }

    @Test
    fun retriesWithAnExplicitlyEmptyPassword() {
        // Created by keepassxc-cli with a key file and a confirmed empty password.
        val withoutPassword = assertThrows(KdbxException::class.java) {
            reader.unlock({ fixture("empty-password-key.kdbx") }, null, { fixture("fixture.keyx") }).close()
        }
        assertEquals(KdbxFailure.CREDENTIALS, withoutPassword.failure)
        reader.unlock({ fixture("empty-password-key.kdbx") }, CharArray(0), { fixture("fixture.keyx") })
            .use { assertTrue(it.entries.isEmpty()) }
    }

    @Test
    fun opensYubiKeyOnlyDatabaseWhenThePasswordFieldIsEmpty() {
        var challenges = 0
        reader.unlock({ fixture("yubikey-legacy.kdbx") }, CharArray(0), challengeResponse = { challenge ->
            challenges++
            mockYubiKey(challenge)
        }).use { assertSampleEntry(it.entries.single()) }
        assertEquals(1, challenges)
    }

    @Test
    fun asksTheYubiKeyOnceWhenRetryingWithAnEmptyPassword() {
        var challenges = 0
        val error = assertThrows(KdbxException::class.java) {
            reader.unlock({ fixture("password.kdbx") }, CharArray(0), challengeResponse = { challenge ->
                challenges++
                mockYubiKey(challenge)
            }).close()
        }
        assertEquals(KdbxFailure.CREDENTIALS, error.failure)
        assertEquals(1, challenges)
    }

    @Test
    fun anEmptyPasswordAloneIsStillAPassword() {
        // With nothing else to unlock with, the empty password is the only key, so there is no retry.
        val error = assertThrows(KdbxException::class.java) {
            reader.unlock({ fixture("password.kdbx") }, CharArray(0)).close()
        }
        assertEquals(KdbxFailure.CREDENTIALS, error.failure)
    }

    @Test
    fun authenticatesTheLastBlockBeforeReturningEntries() {
        val bytes = fixture("modern-argon2id.kdbx").use { it.readBytes() }
        val lastMacByte = bytes.size - 5
        bytes[lastMacByte] = (bytes[lastMacByte].toInt() xor 1).toByte()
        val error = assertThrows(KdbxException::class.java) {
            reader.open(bytes.inputStream(), password()).close()
        }
        assertEquals(KdbxFailure.CORRUPT, error.failure)
    }

    @Test
    fun rejectsTruncatedAndOverBudgetDatabases() {
        val bytes = fixture("modern-argon2id.kdbx").use { it.readBytes() }
        val truncated = assertThrows(KdbxException::class.java) {
            reader.open(bytes.copyOf(bytes.size - 1).inputStream(), password()).close()
        }
        assertEquals(KdbxFailure.CORRUPT, truncated.failure)

        val limit = assertThrows(KdbxException::class.java) {
            KdbxReader(BouncyCastleArgon2(maxMemory = 512 * 1024), chaCha20 = jvmChaCha)
                .open(bytes.inputStream(), password()).close()
        }
        assertEquals(KdbxFailure.LIMIT, limit.failure)
    }

    @Test
    fun reportsKeyDerivationThenDecryption() {
        // AES-KDF reports how far it got. The Java Argon2 cannot, so it stays indeterminate.
        for (file in listOf("password.kdbx", "modern-aes-kdf.kdbx")) {
            val stages = stages(file)
            val percents = stages.filterIsInstance<KdbxStage.DerivingKey>().mapNotNull { it.percent }
            assertEquals(file, KdbxStage.DerivingKey(null), stages.first())
            assertEquals(file, KdbxStage.Decrypting, stages.last())
            assertEquals(file, 100, percents.last())
            assertEquals(file, percents.sorted().distinct(), percents)
        }
        assertEquals(listOf(KdbxStage.DerivingKey(null), KdbxStage.Decrypting), stages("modern-argon2id.kdbx"))
    }

    @Test
    fun normalizesRawHexAndXmlKeyFiles() {
        val key = ByteArray(32) { it.toByte() }
        val xml = "<KeyFile><Meta><Version>1.00</Version></Meta>" +
            "<Key><Data>${Base64.getEncoder().encodeToString(key)}</Data></Key></KeyFile>"
        assertArrayEquals(key, readKeyFile(key.inputStream()))
        assertArrayEquals(key, readKeyFile("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".byteInputStream()))
        assertArrayEquals(key, readKeyFile(xml.byteInputStream()))
    }

    @Test
    fun rejectsDocumentTypeDeclarations() {
        val xml = "<!DOCTYPE KeePassFile []><KeePassFile><Root/></KeePassFile>"
        val error = assertThrows(KdbxException::class.java) {
            readDatabaseXml(xml.byteInputStream(), null, null).close()
        }
        assertEquals(KdbxFailure.CORRUPT, error.failure)
    }

    private fun assertSampleEntry(entry: DatabaseEntry) {
        assertEquals("Sample entry", entry.title)
        assertEquals("fixture-user", entry.field("UserName").text())
        assertEquals("sample-password", entry.field("Password").text())
        assertTrue(entry.field("Password").sensitive)
    }

    private fun DatabaseEntry.field(name: String): DatabaseField = fields.single { it.name == name }

    private fun fixture(name: String): InputStream = checkNotNull(javaClass.getResourceAsStream("/kdbx/$name"))

    private fun password(): CharArray = "fixture-only".toCharArray()

    private fun paddedChallenge(seedStart: Int): ByteArray =
        ByteArray(64) { index -> if (index < 32) (seedStart + index).toByte() else 0x20 }

    private fun mockYubiKey(challenge: ByteArray): ByteArray = Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(ByteArray(20) { (it + 1).toByte() }, "HmacSHA1"))
        doFinal(challenge)
    }

    private fun salsa20(): Salsa20Engine = Salsa20Engine().apply {
        init(true, ParametersWithIV(KeyParameter(ByteArray(32) { it.toByte() }), ByteArray(8)))
    }

    private fun protect(stream: Salsa20Engine, value: ByteArray): String {
        val encrypted = ByteArray(value.size)
        stream.processBytes(value, 0, value.size, encrypted, 0)
        return Base64.getMimeEncoder().encodeToString(encrypted)
    }

    private fun modernKeyFile(): InputStream = ByteArray(32) { (it + 0x60).toByte() }.inputStream()

    private fun open(file: String, password: CharArray? = password(), keyFile: InputStream? = null): Database =
        fixture(file).use { reader.open(it, password, keyFile) }

    private fun stages(file: String): List<KdbxStage> {
        val stages = mutableListOf<KdbxStage>()
        fixture(file).use { KdbxReader(chaCha20 = jvmChaCha, onStage = { stages += it }).open(it, password()).close() }
        return stages
    }
}
