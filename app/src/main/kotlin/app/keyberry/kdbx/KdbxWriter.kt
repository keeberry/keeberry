package app.keyberry.kdbx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.io.CipherOutputStream as BlockCipherOutputStream
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/** Argon2id settings for a new database. The defaults unlock in about a second on a recent phone. */
internal class NewDatabaseKdf(
    val memoryKiB: Int = 64 * 1024,
    val iterations: Int = 10,
    val lanes: Int = 2,
)

/**
 * Writes new, empty KDBX 4.0 databases: AES-256 with Argon2id, GZip and a ChaCha20 inner stream,
 * which KeePass 2.35+, KeePassXC 2.3+ and every current KeePass app open. It also saves changed
 * KDBX 4 databases under the settings they already have.
 */
internal class KdbxWriter(
    private val argon2: Argon2Kdf = BouncyCastleArgon2(),
    private val checkCancelled: () -> Unit = {},
    private val onStage: (KdbxStage) -> Unit = {},
    private val kdf: NewDatabaseKdf = NewDatabaseKdf(),
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    private val chaCha20: (ByteArray, ByteArray) -> Cipher = ::platformChaCha20,
) {
    /**
     * Writes a KDBX 4 database with the cipher, compression and KDF settings of [header] and the key
     * [transformed] derived under them, so saving needs neither the credentials nor a KDF run. The
     * master seed, IV and inner stream key are new. [binaries] are the inner header's attachments
     * as read, in order, since entries refer to them by position. [xml] writes the document, hiding
     * each protected value with the function it is given. Closes [output].
     */
    fun save(
        output: OutputStream,
        header: KdbxHeader,
        transformed: ByteArray,
        binaries: List<ByteArray>,
        xml: (OutputStream, (ByteArray) -> Unit) -> Unit,
    ) {
        val masterSeed = randomBytes(32)
        val iv = randomBytes(header.iv.size)
        val encoded = header.rebuilt(mapOf(4 to masterSeed, 7 to iv))
        val encryptionKey = MessageDigest.getInstance("SHA-256").apply { update(masterSeed) }.digest(transformed)
        val hmacKey = MessageDigest.getInstance("SHA-512").apply {
            update(masterSeed)
            update(transformed)
        }.digest(byteArrayOf(1))
        val streamKey = randomBytes(64)
        try {
            output.write(encoded)
            output.write(sha256(encoded))
            output.write(headerMac(encoded, hmacKey))
            val blocks = KdbxBlockOutput(output, hmacKey)
            val encrypted = encryptingStream(header.cipherId.hexString(), encryptionKey, iv, blocks)
            val payload = if (header.compression == 1) GZIPOutputStream(encrypted) else encrypted
            payload.use {
                it.innerField(1, littleInt(CHACHA20_STREAM))
                it.innerField(2, streamKey)
                binaries.forEach { binary -> it.innerField(3, binary) }
                it.innerField(0, ByteArray(0))
                val protect = checkNotNull(innerStream(CHACHA20_STREAM, streamKey, chaCha20))
                xml(it, protect)
            }
        } finally {
            encryptionKey.fill(0)
            hmacKey.fill(0)
            streamKey.fill(0)
        }
    }

    private fun encryptingStream(cipherId: String, key: ByteArray, iv: ByteArray, target: OutputStream): OutputStream =
        when (cipherId) {
            AES_CIPHER -> CipherOutputStream(target, Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            })
            TWOFISH_CIPHER -> {
                val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(TwofishEngine()), PKCS7Padding())
                cipher.init(true, ParametersWithIV(KeyParameter(key), iv))
                BlockCipherOutputStream(target, cipher)
            }
            // A stream cipher, so the reader's cipher encrypts as well as it decrypts.
            CHACHA20_CIPHER -> CipherOutputStream(target, chaCha20(key, iv))
            else -> throw KdbxException(KdbxFailure.UNSUPPORTED)
        }

    /** Writes a database named [name] with [password] as its only credential, then closes [output]. */
    fun create(output: OutputStream, name: String, password: CharArray) {
        val masterSeed = randomBytes(32)
        val iv = randomBytes(16)
        val header = ByteArrayOutputStream().apply {
            write(littleInt(SIGNATURE_1))
            write(littleInt(SIGNATURE_2))
            write(littleInt(VERSION_4_0))
            headerField(2, AES_CIPHER.decodeHex())
            headerField(3, littleInt(1))
            headerField(4, masterSeed)
            headerField(7, iv)
            headerField(11, kdfParameters(randomBytes(32)))
            headerField(0, "\r\n\r\n".toByteArray(Charsets.US_ASCII))
        }.toByteArray()
        // Deriving the key from the header as the reader parses it keeps both sides on one code path.
        val parsed = KdbxHeader.read(DataInputStream(ByteArrayInputStream(header)))
        val transformed = transformKey(parsed, password, null, argon2, JcaAesKdf, checkCancelled, null, onStage)
        val encryptionKey = MessageDigest.getInstance("SHA-256").apply { update(masterSeed) }.digest(transformed)
        val hmacKey = MessageDigest.getInstance("SHA-512").apply {
            update(masterSeed)
            update(transformed)
        }.digest(byteArrayOf(1))
        transformed.fill(0)
        try {
            checkCancelled()
            output.write(header)
            output.write(sha256(header))
            output.write(headerMac(header, hmacKey))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
            }
            GZIPOutputStream(CipherOutputStream(KdbxBlockOutput(output, hmacKey), cipher)).use { payload ->
                writeInnerHeader(payload)
                payload.write(databaseXml(name))
            }
        } finally {
            encryptionKey.fill(0)
            hmacKey.fill(0)
        }
    }

    private fun kdfParameters(salt: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x00, 0x01))
        kdfItem(0x42, "\$UUID", ARGON2ID_KDF.decodeHex())
        kdfItem(0x42, "S", salt)
        kdfItem(0x04, "P", littleInt(kdf.lanes))
        kdfItem(0x05, "M", littleLong(kdf.memoryKiB * 1024L))
        kdfItem(0x05, "I", littleLong(kdf.iterations.toLong()))
        kdfItem(0x04, "V", littleInt(0x13))
        write(0)
    }.toByteArray()

    private fun writeInnerHeader(payload: OutputStream) {
        val streamKey = randomBytes(64)
        try {
            payload.innerField(1, littleInt(CHACHA20_STREAM))
            payload.innerField(2, streamKey)
            payload.innerField(0, ByteArray(0))
        } finally {
            streamKey.fill(0)
        }
    }

    private fun databaseXml(name: String): ByteArray {
        val time = timestamp()
        val title = xmlText(name)
        val zero = Base64.getEncoder().encodeToString(ByteArray(16))
        val xml = """
            |<?xml version="1.0" encoding="utf-8" standalone="yes"?>
            |<KeePassFile>
            |	<Meta>
            |		<Generator>KeyBerry</Generator>
            |		<DatabaseName>$title</DatabaseName>
            |		<DatabaseNameChanged>$time</DatabaseNameChanged>
            |		<DatabaseDescription />
            |		<DatabaseDescriptionChanged>$time</DatabaseDescriptionChanged>
            |		<DefaultUserName />
            |		<DefaultUserNameChanged>$time</DefaultUserNameChanged>
            |		<MaintenanceHistoryDays>365</MaintenanceHistoryDays>
            |		<Color />
            |		<MasterKeyChanged>$time</MasterKeyChanged>
            |		<MasterKeyChangeRec>-1</MasterKeyChangeRec>
            |		<MasterKeyChangeForce>-1</MasterKeyChangeForce>
            |		<MemoryProtection>
            |			<ProtectTitle>False</ProtectTitle>
            |			<ProtectUserName>False</ProtectUserName>
            |			<ProtectPassword>True</ProtectPassword>
            |			<ProtectURL>False</ProtectURL>
            |			<ProtectNotes>False</ProtectNotes>
            |		</MemoryProtection>
            |		<RecycleBinEnabled>True</RecycleBinEnabled>
            |		<RecycleBinUUID>$zero</RecycleBinUUID>
            |		<RecycleBinChanged>$time</RecycleBinChanged>
            |		<EntryTemplatesGroup>$zero</EntryTemplatesGroup>
            |		<EntryTemplatesGroupChanged>$time</EntryTemplatesGroupChanged>
            |		<HistoryMaxItems>10</HistoryMaxItems>
            |		<HistoryMaxSize>6291456</HistoryMaxSize>
            |		<LastSelectedGroup>$zero</LastSelectedGroup>
            |		<LastTopVisibleGroup>$zero</LastTopVisibleGroup>
            |		<SettingsChanged>$time</SettingsChanged>
            |	</Meta>
            |	<Root>
            |		<Group>
            |			<UUID>${Base64.getEncoder().encodeToString(randomBytes(16))}</UUID>
            |			<Name>$title</Name>
            |			<Notes />
            |			<IconID>49</IconID>
            |			<Times>
            |				<LastModificationTime>$time</LastModificationTime>
            |				<CreationTime>$time</CreationTime>
            |				<LastAccessTime>$time</LastAccessTime>
            |				<ExpiryTime>$time</ExpiryTime>
            |				<Expires>False</Expires>
            |				<UsageCount>0</UsageCount>
            |				<LocationChanged>$time</LocationChanged>
            |			</Times>
            |			<IsExpanded>True</IsExpanded>
            |			<DefaultAutoTypeSequence />
            |			<EnableAutoType>null</EnableAutoType>
            |			<EnableSearching>null</EnableSearching>
            |			<LastTopVisibleEntry>$zero</LastTopVisibleEntry>
            |		</Group>
            |		<DeletedObjects />
            |	</Root>
            |</KeePassFile>
            |""".trimMargin()
        return xml.toByteArray(Charsets.UTF_8)
    }

    private fun timestamp(): String = kdbxTime(now())

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private companion object {
        const val SIGNATURE_1 = 0x9aa2d903.toInt()
        const val SIGNATURE_2 = 0xb54bfb67.toInt()
        const val VERSION_4_0 = 0x00040000
        const val AES_CIPHER = "31c1f2e6bf714350be5805216afc5aff"
        const val CHACHA20_CIPHER = "d6038a2b8b6f4cb5a524339a31dbb59a"
        const val TWOFISH_CIPHER = "ad68f29f576f4bb9a36ad47af965346c"
        const val ARGON2ID_KDF = "9e298b1956db4773b23dfc3ec6f0a1e6"
        const val CHACHA20_STREAM = 3
    }
}

/** KDBX 4 stores times as base64 of little-endian seconds since 0001-01-01 UTC. */
internal fun kdbxTime(millis: Long): String =
    Base64.getEncoder().encodeToString(littleLong(Math.floorDiv(millis, 1000L) + 62_135_596_800L))

/** Splits the payload into KDBX 4 blocks, each authenticated by its own HMAC-SHA256 key. */
private class KdbxBlockOutput(private val target: OutputStream, private val hmacKey: ByteArray) : OutputStream() {
    private val block = ByteArray(1024 * 1024)
    private val mac = Mac.getInstance("HmacSHA256")
    private var size = 0
    private var sequence = 0L
    private var closed = false

    override fun write(byte: Int) {
        if (size == block.size) writeBlock()
        block[size++] = byte.toByte()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            if (size == block.size) writeBlock()
            val count = minOf(length - written, block.size - size)
            bytes.copyInto(block, size, offset + written, offset + written + count)
            size += count
            written += count
        }
    }

    /** Writes the last block of data, then the empty block that ends the stream. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (size > 0) writeBlock()
            writeBlock()
            target.flush()
        } finally {
            block.fill(0)
            target.close()
        }
    }

    private fun writeBlock() {
        val index = littleLong(sequence)
        val length = littleInt(size)
        val blockKey = MessageDigest.getInstance("SHA-512").apply { update(index) }.digest(hmacKey)
        try {
            mac.init(SecretKeySpec(blockKey, "HmacSHA256"))
            mac.update(index)
            mac.update(length)
            mac.update(block, 0, size)
            target.write(mac.doFinal())
        } finally {
            blockKey.fill(0)
        }
        target.write(length)
        target.write(block, 0, size)
        sequence++
        size = 0
    }
}

private fun headerMac(header: ByteArray, hmacKey: ByteArray): ByteArray {
    val key = MessageDigest.getInstance("SHA-512").apply { update(littleLong(-1)) }.digest(hmacKey)
    try {
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(header)
    } finally {
        key.fill(0)
    }
}

private fun ByteArrayOutputStream.headerField(id: Int, value: ByteArray) {
    write(id)
    write(littleInt(value.size))
    write(value)
}

private fun OutputStream.innerField(id: Int, value: ByteArray) {
    write(id)
    write(littleInt(value.size))
    write(value)
}

private fun ByteArrayOutputStream.kdfItem(type: Int, name: String, value: ByteArray) {
    val key = name.toByteArray(Charsets.UTF_8)
    write(type)
    write(littleInt(key.size))
    write(key)
    write(littleInt(value.size))
    write(value)
}

private fun littleInt(value: Int): ByteArray = ByteBuffer.allocate(4).order(LITTLE_ENDIAN).putInt(value).array()

/** Escapes [text] for XML, dropping the characters XML 1.0 can't hold at all. */
internal fun xmlText(text: String): String = buildString(text.length) {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\t', '\n', '\r' -> append(char)
            '￾', '￿' -> Unit
            else -> if (char >= ' ') append(char)
        }
    }
}
