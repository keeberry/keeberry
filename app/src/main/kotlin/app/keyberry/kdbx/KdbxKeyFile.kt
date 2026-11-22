package app.keyberry.kdbx

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
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
