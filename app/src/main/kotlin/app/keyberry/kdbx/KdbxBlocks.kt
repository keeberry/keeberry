package app.keyberry.kdbx

import java.io.DataInputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// KDBX 3 hashes plaintext blocks. KDBX 4 authenticates ciphertext blocks before decryption.
internal class KdbxBlocks(
    private val source: DataInputStream,
    private val hmacKey: ByteArray?,
) : InputStream() {
    private val digest = MessageDigest.getInstance(if (hmacKey == null) "SHA-256" else "SHA-512")
    private val mac = hmacKey?.let { Mac.getInstance("HmacSHA256") }
    private var block = ByteArray(0)
    private var size = 0
    private var offset = 0
    private var sequence = 0L
    private var ended = false

    override fun read(): Int = if (hasBytes()) block[offset++].toInt() and 0xff else -1

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!hasBytes()) return -1
        val count = minOf(length, size - this.offset)
        block.copyInto(bytes, offset, this.offset, this.offset + count)
        this.offset += count
        return count
    }

    private fun hasBytes(): Boolean {
        if (offset < size) return true
        if (ended) return false
        if (hmacKey == null) checkKdbx(source.readLittleInt().toLong() == sequence)
        val expected = source.takeBytes(32)
        val lengthBytes = source.takeBytes(4)
        val length = lengthBytes.littleEndian().int
        checkKdbx(length >= 0)
        checkKdbx(length <= 16 * 1024 * 1024, KdbxFailure.LIMIT)
        if (block.size < length) {
            block.fill(0)
            block = ByteArray(length)
        }
        source.readFully(block, 0, length)
        val actual = if (hmacKey != null && mac != null) {
            val index = littleLong(sequence)
            digest.update(index)
            val blockKey = digest.digest(hmacKey)
            try {
                mac.init(SecretKeySpec(blockKey, "HmacSHA256"))
                mac.update(index)
                mac.update(lengthBytes)
                mac.update(block, 0, length)
                mac.doFinal()
            } finally {
                blockKey.fill(0)
            }
        } else if (length == 0) {
            ByteArray(32)
        } else {
            digest.update(block, 0, length)
            digest.digest()
        }
        checkKdbx(MessageDigest.isEqual(expected, actual))
        sequence++
        size = length
        offset = 0
        if (length == 0) {
            checkKdbx(source.read() == -1)
            ended = true
        }
        return !ended
    }

    override fun close() {
        block.fill(0)
        ended = true
    }
}
