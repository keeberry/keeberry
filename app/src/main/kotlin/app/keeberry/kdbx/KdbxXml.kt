package app.keeberry.kdbx

import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

internal open class StrictXmlHandler : DefaultHandler2() {
    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        throw SAXException("Document types are disabled")
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
        throw SAXException("External entities are disabled")
    }

    override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource {
        throw SAXException("External entities are disabled")
    }
}

internal fun parseXml(source: InputStream, handler: StrictXmlHandler) {
    val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
    reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
    reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
    reader.contentHandler = handler
    reader.entityResolver = handler
    reader.errorHandler = handler
    // The KDBX reader still needs the stream to check framing and cipher finalization.
    reader.parse(InputSource(object : FilterInputStream(source) {
        override fun close() = Unit
    }))
}

internal fun decodeBase64(text: CharSequence): ByteArray =
    Base64.getDecoder().decode(text.filterNot(Char::isWhitespace).toString())

internal fun readDatabaseXml(
    source: InputStream,
    decrypt: ((ByteArray) -> Unit)?,
    legacyHeaderHash: ByteArray?,
): Database {
    val handler = DatabaseXml(decrypt, legacyHeaderHash)
    var success = false
    try {
        parseXml(source, handler)
        return handler.database().also { success = true }
    } catch (error: SAXException) {
        val reason = error.cause
        if (reason is KdbxException) throw reason
        throw KdbxException(KdbxFailure.CORRUPT)
    } catch (_: IllegalArgumentException) {
        throw KdbxException(KdbxFailure.CORRUPT)
    } finally {
        if (!success) handler.clear()
    }
}

private class DatabaseXml(
    private val decrypt: ((ByteArray) -> Unit)?,
    private val legacyHeaderHash: ByteArray?,
) : StrictXmlHandler() {
    private enum class Tag(val hasText: Boolean = false) {
        FILE, META, ROOT, GROUP, ENTRY, FIELD, KEY(true), VALUE(true),
        GROUP_NAME(true), GROUP_UUID(true), ENTRY_UUID(true), DATABASE_NAME(true), HEADER_HASH(true),
        RECYCLE_BIN_UUID(true), RECYCLE_BIN_ENABLED(true), OTHER,
    }

    private class Element(val tag: Tag, val protected: Boolean) {
        val text = if (tag.hasText) StringBuilder() else null

        // A protected value this reader doesn't keep, such as a KDBX 3 attachment, only has to move
        // the inner stream past its bytes. Counting them instead of buffering lifts the text limit.
        val skipped = if (protected && !tag.hasText) Base64Length() else null
    }

    /** The decoded size of base64 text that arrives in pieces, checked as strictly as a decode. */
    private class Base64Length {
        private var symbols = 0L
        private var padding = 0

        fun add(chars: CharArray, start: Int, length: Int) {
            for (index in start until start + length) {
                val char = chars[index]
                when {
                    char.isWhitespace() -> Unit
                    char == '=' -> padding++
                    else -> {
                        checkKdbx(padding == 0 && (char in 'A'..'Z' || char in 'a'..'z' ||
                            char in '0'..'9' || char == '+' || char == '/'))
                        symbols++
                    }
                }
            }
        }

        fun bytes(): Long {
            val total = symbols + padding
            checkKdbx(total % 4 == 0L && padding <= 2)
            return total / 4 * 3 - padding
        }
    }

    private class Group {
        var name = ""
        var uuid: ByteArray? = null
    }

    private val path = ArrayDeque<Element>()
    private val groups = ArrayDeque<Group>()
    // Every group with the groups it sits in, itself last, in document order.
    private val allGroups = mutableListOf<List<Group>>()
    private var recycleBinEnabled = true
    private val entries = mutableListOf<DatabaseEntry>()
    // The groups each entry sat in, so entries in the recycle bin can be dropped once its UUID is known.
    private val entryGroups = mutableListOf<List<Group>>()
    private var recycleBin: ByteArray? = null
    private var fields = mutableListOf<DatabaseField>()
    private var entryUuid: ByteArray? = null
    private var fieldName: String? = null
    private var fieldValue: ByteArray? = null
    private var fieldProtected = false
    private var name = ""
    private var hasRoot = false
    private var retainedBytes = 0L
    private var retainedFields = 0

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        val parent = path.lastOrNull()
        checkKdbx(path.size < 128, KdbxFailure.LIMIT)
        checkKdbx(parent?.text == null && parent?.skipped == null)
        val tag = tagFor(parent?.tag, qName)
        when (tag) {
            Tag.ROOT -> {
                checkKdbx(!hasRoot)
                hasRoot = true
            }
            Tag.GROUP -> {
                checkKdbx(allGroups.size < 25_000, KdbxFailure.LIMIT)
                groups.addLast(Group())
                allGroups.add(groups.toList())
            }
            else -> Unit
        }
        path.addLast(Element(tag, "true".equals(attributes.getValue("Protected"), ignoreCase = true)))
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        val element = path.lastOrNull() ?: return
        element.skipped?.add(chars, start, length)
        element.text?.let { text ->
            checkKdbx(length <= 4 * 1024 * 1024 - text.length, KdbxFailure.LIMIT)
            text.append(chars, start, length)
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val element = path.removeLast()
        element.skipped?.let { skipProtected(it.bytes()) }
        val text = element.text
        if (element.protected && text != null) {
            val bytes = decodeBase64(text)
            var retained = false
            try {
                if (bytes.isNotEmpty()) (decrypt ?: throw KdbxException(KdbxFailure.CORRUPT))(bytes)
                if (element.tag == Tag.VALUE) {
                    setFieldValue(bytes, true)
                    retained = true
                } else if (element.tag.hasText) {
                    recordText(element.tag, bytes.toString(Charsets.UTF_8))
                }
            } finally {
                if (!retained) bytes.fill(0)
            }
        } else if (text != null) {
            try {
                if (element.tag == Tag.VALUE) setFieldValue(utf8(text), false)
                else recordText(element.tag, text.toString())
            } finally {
                text.wipe()
            }
        }

        when (element.tag) {
            Tag.FIELD -> finishField()
            Tag.ENTRY -> {
                checkKdbx(entries.size < 25_000, KdbxFailure.LIMIT)
                val group = groups.map { it.name }.filter(String::isNotBlank).joinToString(" / ")
                retain(group.length.toLong() * 2)
                entries.add(DatabaseEntry(group, fields, entryUuid))
                entryGroups.add(groups.toList())
                fields = mutableListOf()
                entryUuid = null
            }
            Tag.GROUP -> groups.removeLast()
            else -> Unit
        }
    }

    /** Moves the inner stream past [count] protected bytes that are not kept. */
    private fun skipProtected(count: Long) {
        if (count == 0L) return
        val decrypt = decrypt ?: throw KdbxException(KdbxFailure.CORRUPT)
        val buffer = ByteArray(minOf(count, 64L * 1024).toInt())
        var remaining = count
        while (remaining > 0) {
            val chunk = if (remaining >= buffer.size) buffer else ByteArray(remaining.toInt())
            decrypt(chunk)
            // The output is keystream, so it doesn't stay in memory.
            chunk.fill(0)
            remaining -= chunk.size
        }
    }

    private fun tagFor(parent: Tag?, name: String): Tag = when (parent) {
        null -> Tag.FILE.also { checkKdbx(name == "KeePassFile") }
        Tag.FILE -> when (name) {
            "Meta" -> Tag.META
            "Root" -> Tag.ROOT
            else -> Tag.OTHER
        }
        Tag.META -> when (name) {
            "DatabaseName" -> Tag.DATABASE_NAME
            "HeaderHash" -> Tag.HEADER_HASH
            "RecycleBinUUID" -> Tag.RECYCLE_BIN_UUID
            "RecycleBinEnabled" -> Tag.RECYCLE_BIN_ENABLED
            else -> Tag.OTHER
        }
        Tag.ROOT, Tag.GROUP -> when (name) {
            "Group" -> Tag.GROUP
            "Entry" -> Tag.ENTRY
            "Name" -> if (parent == Tag.GROUP) Tag.GROUP_NAME else Tag.OTHER
            "UUID" -> if (parent == Tag.GROUP) Tag.GROUP_UUID else Tag.OTHER
            else -> Tag.OTHER
        }
        Tag.ENTRY -> when (name) {
            "String" -> Tag.FIELD
            "UUID" -> Tag.ENTRY_UUID
            else -> Tag.OTHER
        }
        Tag.FIELD -> when (name) {
            "Key" -> Tag.KEY
            "Value" -> Tag.VALUE
            else -> Tag.OTHER
        }
        else -> Tag.OTHER
    }

    private fun recordText(tag: Tag, text: String) {
        when (tag) {
            Tag.KEY -> {
                checkKdbx(fieldName == null)
                checkKdbx(text.length <= 1024, KdbxFailure.LIMIT)
                fieldName = text
            }
            Tag.GROUP_NAME -> {
                checkKdbx(text.length <= 1024, KdbxFailure.LIMIT)
                retain(text.length.toLong() * 2)
                groups.last().name = text
            }
            Tag.GROUP_UUID -> groups.last().uuid = uuidOf(text)
            Tag.ENTRY_UUID -> entryUuid = uuidOf(text)
            // An all-zero UUID means the database has no recycle bin.
            Tag.RECYCLE_BIN_UUID -> recycleBin = uuidOf(text)?.takeIf { uuid -> uuid.any { it != 0.toByte() } }
            // KeePass treats anything but False as enabled.
            Tag.RECYCLE_BIN_ENABLED -> recycleBinEnabled = !text.trim().equals("false", ignoreCase = true)
            Tag.DATABASE_NAME -> {
                checkKdbx(text.length <= 1024, KdbxFailure.LIMIT)
                name = text
            }
            Tag.HEADER_HASH -> if (legacyHeaderHash != null && text.isNotBlank()) {
                checkKdbx(MessageDigest.isEqual(legacyHeaderHash, decodeBase64(text)))
            }
            else -> Unit
        }
    }

    private fun setFieldValue(value: ByteArray, protected: Boolean) {
        if (fieldValue != null) {
            value.fill(0)
            throw KdbxException(KdbxFailure.CORRUPT)
        }
        fieldValue = value
        fieldProtected = protected
    }

    private fun finishField() {
        val key = fieldName ?: throw KdbxException(KdbxFailure.CORRUPT)
        val value = fieldValue ?: throw KdbxException(KdbxFailure.CORRUPT)
        checkKdbx(++retainedFields <= 150_000, KdbxFailure.LIMIT)
        retain(value.size + key.length.toLong() * 2)
        fields.add(DatabaseField(key, value, fieldProtected))
        fieldName = null
        fieldValue = null
    }

    private fun retain(bytes: Long) {
        retainedBytes += bytes
        checkKdbx(retainedBytes <= 24L * 1024 * 1024, KdbxFailure.LIMIT)
    }

    /** Lenient, since a UUID this reader can't decode only means it can't match the recycle bin. */
    private fun uuidOf(text: String): ByteArray? =
        if (text.length > 64) null else runCatching { decodeBase64(text) }.getOrNull()?.takeIf { it.size == 16 }

    /** Leaves out the recycle bin, the groups inside it and their entries, which it wipes. */
    fun database(): Database {
        checkKdbx(hasRoot)
        val bin = recycleBin
        fun binned(chain: List<Group>) = bin != null && chain.any { bin.contentEquals(it.uuid) }
        val live = mutableListOf<DatabaseEntry>()
        entries.forEachIndexed { index, entry ->
            if (binned(entryGroups[index])) entry.clear() else live.add(entry)
        }
        val groups = allGroups.filterNot(::binned).mapNotNull { chain ->
            // A group without a valid UUID can't be named in a save.
            val uuid = chain.last().uuid ?: return@mapNotNull null
            DatabaseGroup(uuid, chain.map { it.name }.filter(String::isNotBlank).joinToString(" / "), chain.size - 1)
        }
        return Database(name, live, groups = groups, recycleBin = recycleBinEnabled)
    }

    fun clear() {
        entries.forEach(DatabaseEntry::clear)
        fields.forEach(DatabaseField::clear)
        fieldValue?.fill(0)
        path.forEach { it.text?.wipe() }
    }

    private fun StringBuilder.wipe() {
        for (index in indices) setCharAt(index, '\u0000')
        setLength(0)
    }
}
