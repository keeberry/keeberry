package app.keeberry.kdbx

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import org.xml.sax.Attributes
import org.xml.sax.SAXException

internal fun readKeyFile(source: InputStream): ByteArray {
    val buffer = ByteArray(8192)
    try {
        var size = 0
        while (size < buffer.size) {
            val count = source.read(buffer, size, buffer.size - size)
            if (count == -1) break
            size += count
        }
        checkKdbx(size > 0, KdbxFailure.KEY_FILE)
        if (size < buffer.size) {
            xmlKey(ByteArrayInputStream(buffer, 0, size))?.let { return it }
            if (size == 32) return buffer.copyOf(size)
            if (size == 64) {
                val hex = buffer.decodeToString(0, size)
                if (hex.length == 64 && hex.all { it.digitToIntOrNull(16) != null }) {
                    return hex.decodeHex()
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(buffer, 0, size)
        while (true) {
            val count = source.read(buffer)
            if (count == -1) return digest.digest()
            digest.update(buffer, 0, count)
        }
    } finally {
        buffer.fill(0)
    }
}

/**
 * Writes a new random 256-bit key in KeePass's XML key file format 2.0 (.keyx), which KeePass 2.47+
 * and KeePassXC 2.7+ also read, then closes [output].
 */
internal fun writeKeyFile(output: OutputStream, random: SecureRandom = SecureRandom()) {
    val key = ByteArray(32).also(random::nextBytes)
    // The key's hex goes straight into a byte buffer that is wiped afterwards, never into a String.
    val line = ByteArray(LINE_INDENT.length + 4 * 9)
    try {
        output.use {
            val check = sha256(key).hexString(4).uppercase()
            it.write(KEY_FILE_HEAD.replace("%HASH%", check).toByteArray(Charsets.US_ASCII))
            for (half in 0..1) {
                var at = 0
                LINE_INDENT.forEach { char -> line[at++] = char.code.toByte() }
                for (index in half * 16 until half * 16 + 16) {
                    val value = key[index].toInt() and 0xff
                    line[at++] = HEX_DIGITS[value ushr 4].code.toByte()
                    line[at++] = HEX_DIGITS[value and 0x0f].code.toByte()
                    // Groups of four bytes, as KeePass lays them out.
                    if (index % 4 == 3) line[at++] = (if (index % 16 == 15) '\n' else ' ').code.toByte()
                }
                it.write(line, 0, at)
            }
            it.write(KEY_FILE_TAIL.toByteArray(Charsets.US_ASCII))
        }
    } finally {
        key.fill(0)
        line.fill(0)
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
private const val LINE_INDENT = "\t\t\t"
private const val KEY_FILE_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<KeyFile>\n\t<Meta>\n" +
    "\t\t<Version>2.0</Version>\n\t</Meta>\n\t<Key>\n\t\t<Data Hash=\"%HASH%\">\n"
private const val KEY_FILE_TAIL = "\t\t</Data>\n\t</Key>\n</KeyFile>\n"

private fun xmlKey(input: InputStream): ByteArray? {
    val handler = KeyFileXml()
    try {
        parseXml(input, handler)
    } catch (_: SAXException) {
        checkKdbx(!handler.recognized, KdbxFailure.KEY_FILE)
        return null
    }
    if (!handler.recognized) return null
    val data = handler.data.filterNot(Char::isWhitespace)
    val version = handler.version.trim()
    val key = try {
        when {
            version.startsWith("1.0") -> decodeBase64(data)
            version == "2.0" -> data.decodeHex()
            else -> throw KdbxException(KdbxFailure.KEY_FILE)
        }
    } catch (_: IllegalArgumentException) {
        throw KdbxException(KdbxFailure.KEY_FILE)
    }
    var success = false
    try {
        checkKdbx(key.size == 32, KdbxFailure.KEY_FILE)
        if (version == "2.0") {
            val expected = handler.checksum
            checkKdbx(expected.length == 8 &&
                sha256(key).hexString(4).equals(expected, ignoreCase = true), KdbxFailure.KEY_FILE)
        }
        success = true
        return key
    } finally {
        if (!success) key.fill(0)
    }
}

private class KeyFileXml : StrictXmlHandler() {
    private val path = ArrayDeque<String>()
    private var text: StringBuilder? = null
    var recognized = false
        private set
    var version = ""
        private set
    var data = ""
        private set
    var checksum = ""
        private set

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (path.isEmpty()) {
            recognized = qName == "KeyFile"
            if (!recognized) throw SAXException()
        }
        if (path.size == 2) {
            if (path.last() == "Meta" && qName == "Version") text = StringBuilder()
            if (path.last() == "Key" && qName == "Data") {
                text = StringBuilder()
                checksum = attributes.getValue("Hash").orEmpty()
            }
        } else if (text != null) {
            throw SAXException()
        }
        path.addLast(qName)
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        text?.append(chars, start, length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (path.size == 3) {
            text?.let {
                if (qName == "Version") version = it.toString()
                if (qName == "Data") data = it.toString()
            }
            text = null
        }
        path.removeLast()
    }
}
