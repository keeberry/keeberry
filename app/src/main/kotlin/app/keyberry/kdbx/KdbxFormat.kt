package app.keyberry.kdbx

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

/** [LIMIT] is over what Keyberry allows at all. [MEMORY] fits that, but not the memory free right now. */
internal enum class KdbxFailure { FORMAT, UNSUPPORTED, CORRUPT, CREDENTIALS, KEY_FILE, LIMIT, MEMORY }

internal class KdbxException(val failure: KdbxFailure) : IOException()

internal fun checkKdbx(valid: Boolean, failure: KdbxFailure = KdbxFailure.CORRUPT) {
    if (!valid) throw KdbxException(failure)
}

internal class KdbxHeader private constructor(
    val major: Int,
    val encoded: ByteArray,
    private val fields: Map<Int, ByteArray>,
) {
    val compression: Int = field(3, 4).littleEndian().int.also {
        checkKdbx(it in 0..1, KdbxFailure.UNSUPPORTED)
    }
    val cipherId: ByteArray get() = field(2, 16)
    val masterSeed: ByteArray get() = field(4, 32)
    val iv: ByteArray get() = field(7)
    val kdfParameters: ByteArray get() = field(11)
    val legacyTransformSeed: ByteArray get() = field(5, 32)
    val legacyRounds: Long get() = field(6, 8).littleEndian().long
    val legacyStreamKey: ByteArray get() = field(8)
    val legacyStartBytes: ByteArray get() = field(9, 32)
    val legacyStreamId: Int get() = field(10, 4).littleEndian().int

    /**
     * This KDBX 4 header with [replacements] for some fields, in the original field order. Fields
     * Keyberry doesn't know, such as public custom data, are kept as they are.
     */
    fun rebuilt(replacements: Map<Int, ByteArray>): ByteArray {
        checkKdbx(major == 4, KdbxFailure.UNSUPPORTED)
        return ByteArrayOutputStream().apply {
            write(encoded, 0, 12)
            for ((id, value) in fields) writeField(id, replacements[id] ?: value)
            writeField(0, "\r\n\r\n".toByteArray(Charsets.US_ASCII))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeField(id: Int, value: ByteArray) {
        write(id)
        write(ByteBuffer.allocate(4).order(LITTLE_ENDIAN).putInt(value.size).array())
        write(value)
    }

    private fun field(id: Int, size: Int? = null): ByteArray {
        val bytes = fields[id] ?: throw KdbxException(KdbxFailure.CORRUPT)
        checkKdbx(size == null || bytes.size == size)
        return bytes
    }

    companion object {
        private const val MAX_HEADER_BYTES = 1024 * 1024

        fun isRecognized(stream: InputStream): Boolean = try {
            val version = versionOf(DataInputStream(stream).takeBytes(12))
            version ushr 16 in 3..4
        } catch (_: KdbxException) {
            false
        } catch (_: EOFException) {
            false
        }

        fun read(source: DataInputStream): KdbxHeader {
            val prefix = source.takeBytes(12)
            val version = versionOf(prefix)
            val major = version ushr 16
            checkKdbx(major in 3..4 && (version and 0xffff) <= 1, KdbxFailure.UNSUPPORTED)
            val encoded = ByteArrayOutputStream().apply { write(prefix) }
            val fields = mutableMapOf<Int, ByteArray>()
            while (true) {
                val id = source.readUnsignedByte()
                val lengthBytes = source.takeBytes(if (major == 3) 2 else 4)
                val length = if (major == 3) {
                    lengthBytes.littleEndian().short.toInt() and 0xffff
                } else {
                    lengthBytes.littleEndian().int
                }
                checkKdbx(length >= 0)
                checkKdbx(length <= MAX_HEADER_BYTES - encoded.size() - 1 - lengthBytes.size,
                    KdbxFailure.LIMIT)
                val bytes = source.takeBytes(length)
                encoded.write(id)
                encoded.write(lengthBytes)
                encoded.write(bytes)
                if (id == 0) break
                checkKdbx(fields.put(id, bytes) == null)
            }
            return KdbxHeader(major, encoded.toByteArray(), fields)
        }

        private fun versionOf(prefix: ByteArray): Int {
            val fields = prefix.littleEndian()
            checkKdbx(fields.int == 0x9aa2d903.toInt() && fields.int == 0xb54bfb67.toInt(),
                KdbxFailure.FORMAT)
            return fields.int
        }
    }
}

internal fun DataInputStream.takeBytes(size: Int): ByteArray = ByteArray(size).also { readFully(it) }

internal fun DataInputStream.readLittleInt(): Int = Integer.reverseBytes(readInt())

internal fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(LITTLE_ENDIAN)

internal fun littleLong(value: Long): ByteArray =
    ByteBuffer.allocate(8).order(LITTLE_ENDIAN).putLong(value).array()

internal fun ByteArray.hexString(length: Int = size): String = buildString(length * 2) {
    val digits = "0123456789abcdef"
    for (index in 0 until length) {
        val value = this@hexString[index].toInt() and 0xff
        append(digits[value ushr 4])
        append(digits[value and 0x0f])
    }
}

internal fun String.decodeHex(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        val high = this[index * 2].digitToInt(16)
        val low = this[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

internal fun InputStream.drain() {
    val buffer = ByteArray(8192)
    try {
        while (read(buffer) != -1) Unit
    } finally {
        buffer.fill(0)
    }
}

internal class BoundedInput(
    source: InputStream,
    private var remaining: Long,
    private val checkCancelled: () -> Unit,
) : FilterInputStream(source) {
    override fun read(): Int {
        checkCancelled()
        val result = `in`.read()
        if (result != -1) consume(1)
        return result
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        checkCancelled()
        val result = `in`.read(bytes, offset, minOf(length.toLong(), remaining + 1).toInt())
        if (result > 0) consume(result.toLong())
        return result
    }

    override fun skip(count: Long): Long {
        checkCancelled()
        return `in`.skip(minOf(count, remaining)).also { consume(it) }
    }

    private fun consume(count: Long) {
        remaining -= count
        checkKdbx(remaining >= 0, KdbxFailure.LIMIT)
    }
}
