package app.keeberry.kdbx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import org.xml.sax.Attributes
import org.xml.sax.SAXException

/**
 * One XML element of a database's inner document. Everything is kept, including elements KeeBerry
 * doesn't show, so a save writes back exactly what it read apart from the change it makes.
 * A protected value is held decrypted in [secret]; anything else that has text holds it in [text].
 */
internal class XmlNode(val name: String, val attributes: MutableList<Pair<String, String>> = mutableListOf()) {
    val children = mutableListOf<XmlNode>()
    var text: String? = null
    var secret: ByteArray? = null

    val protected: Boolean
        get() = attributes.any { (key, value) -> key == "Protected" && value.equals("true", ignoreCase = true) }

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    fun childrenNamed(name: String): List<XmlNode> = children.filter { it.name == name }

    /** Adds a child with [text], or an empty one. */
    fun add(name: String, text: String? = null): XmlNode = XmlNode(name).also {
        it.text = text
        children.add(it)
    }

    fun deepCopy(): XmlNode {
        val copy = XmlNode(name, attributes.toMutableList())
        copy.text = text
        copy.secret = secret?.copyOf()
        children.mapTo(copy.children, XmlNode::deepCopy)
        return copy
    }

    fun clear() {
        secret?.fill(0)
        children.forEach(XmlNode::clear)
    }
}

/** A decrypted KDBX 4 payload: the inner header's attachments and the XML document. */
internal class KdbxDocument(val header: KdbxHeader, val binaries: List<ByteArray>, val root: XmlNode) {
    fun clear() {
        root.clear()
        binaries.forEach { it.fill(0) }
    }

    /** Writes the document as UTF-8, hiding protected values with [protect] in document order. */
    fun write(output: OutputStream, protect: (ByteArray) -> Unit) {
        val writer = output.writer(Charsets.UTF_8).buffered(64 * 1024)
        writer.write("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n")
        writeNode(writer, root, 0, protect)
        writer.flush()
    }

    private fun writeNode(writer: Writer, node: XmlNode, depth: Int, protect: (ByteArray) -> Unit) {
        repeat(depth) { writer.write('\t'.code) }
        writer.write("<")
        writer.write(node.name)
        for ((key, value) in node.attributes) {
            writer.write(" ")
            writer.write(key)
            writer.write("=\"")
            writer.write(escape(value, attribute = true))
            writer.write("\"")
        }
        val secret = node.secret
        val content = when {
            secret != null && secret.isNotEmpty() -> {
                // The value is wiped once its ciphertext is in the document.
                val hidden = secret.copyOf()
                try {
                    protect(hidden)
                    Base64.getEncoder().encodeToString(hidden)
                } finally {
                    hidden.fill(0)
                }
            }
            secret != null -> ""
            else -> node.text?.let { escape(it, attribute = false) }.orEmpty()
        }
        when {
            node.children.isNotEmpty() -> {
                writer.write(">\n")
                node.children.forEach { writeNode(writer, it, depth + 1, protect) }
                repeat(depth) { writer.write('\t'.code) }
                writer.write("</")
                writer.write(node.name)
                writer.write(">\n")
            }
            content.isEmpty() -> writer.write(" />\n")
            else -> {
                writer.write(">")
                writer.write(content)
                writer.write("</")
                writer.write(node.name)
                writer.write(">\n")
            }
        }
    }

    /**
     * Escapes text so a parser reads back the same characters: a carriage return, and in attributes
     * also tabs and line feeds, are written as references, which line-end handling leaves alone.
     */
    private fun escape(text: String, attribute: Boolean): String {
        val escaped = xmlText(text)
        if (!attribute && '\r' !in escaped) return escaped
        return buildString(escaped.length) {
            for (char in escaped) {
                when {
                    char == '\r' -> append("&#13;")
                    attribute && char == '\n' -> append("&#10;")
                    attribute && char == '\t' -> append("&#9;")
                    else -> append(char)
                }
            }
        }
    }

    companion object {
        /**
         * Reads a decrypted KDBX 4 payload whole, checking every block. [payload] is still
         * compressed if the header says so.
         */
        fun read(
            header: KdbxHeader,
            payload: InputStream,
            chaCha20: (ByteArray, ByteArray) -> Cipher,
            checkCancelled: () -> Unit,
        ): KdbxDocument {
            val decompressed = if (header.compression == 1) GZIPInputStream(payload, 8192) else payload
            val source = DataInputStream(BoundedInput(decompressed, 256L * 1024 * 1024, checkCancelled))
            val binaries = mutableListOf<ByteArray>()
            var root: XmlNode? = null
            var success = false
            try {
                val decrypt = readInnerHeader(source, binaries, chaCha20)
                val handler = TreeBuilder(decrypt)
                try {
                    parseXml(source, handler)
                } catch (error: SAXException) {
                    handler.clear()
                    val reason = error.cause
                    if (reason is KdbxException) throw reason
                    throw KdbxException(KdbxFailure.CORRUPT)
                } catch (_: IllegalArgumentException) {
                    handler.clear()
                    throw KdbxException(KdbxFailure.CORRUPT)
                }
                root = handler.root ?: throw KdbxException(KdbxFailure.CORRUPT)
                checkKdbx(root.name == "KeePassFile" && root.child("Root") != null)
                // Reaching the end authenticates the last blocks too.
                source.drain()
                if (decompressed !== payload) payload.drain()
                success = true
                return KdbxDocument(header, binaries, root)
            } finally {
                if (!success) {
                    root?.clear()
                    binaries.forEach { it.fill(0) }
                }
            }
        }

        private fun readInnerHeader(
            source: DataInputStream,
            binaries: MutableList<ByteArray>,
            chaCha20: (ByteArray, ByteArray) -> Cipher,
        ): ((ByteArray) -> Unit)? {
            var id: Int? = null
            var key: ByteArray? = null
            try {
                while (true) {
                    val field = source.readUnsignedByte()
                    val size = source.readLittleInt()
                    checkKdbx(size >= 0)
                    when (field) {
                        0 -> {
                            checkKdbx(size == 0)
                            return innerStream(id ?: throw KdbxException(KdbxFailure.CORRUPT), key ?: ByteArray(0), chaCha20)
                        }
                        1 -> {
                            checkKdbx(size == 4 && id == null)
                            id = source.readLittleInt()
                        }
                        2 -> {
                            checkKdbx(key == null && size in 1..1024)
                            key = source.takeBytes(size)
                        }
                        // An attachment: a flags byte, then its content.
                        3 -> {
                            checkKdbx(size >= 1)
                            binaries.add(source.takeBytes(size))
                        }
                        else -> source.takeBytes(size).fill(0)
                    }
                }
            } finally {
                key?.fill(0)
            }
        }
    }
}

/** The most text a document may hold, so a save can't exhaust memory on a huge database. */
private const val MAX_TEXT_CHARS = 64L * 1024 * 1024

/** Builds the [XmlNode] tree, decrypting protected values in document order as the reader does. */
private class TreeBuilder(private val decrypt: ((ByteArray) -> Unit)?) : StrictXmlHandler() {
    private val path = ArrayDeque<XmlNode>()
    private val texts = ArrayDeque<StringBuilder>()
    private var chars = 0L
    var root: XmlNode? = null
        private set

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        checkKdbx(path.size < 128, KdbxFailure.LIMIT)
        val parent = path.lastOrNull()
        // KDBX has no mixed content, so text before a child element is a damaged document.
        checkKdbx(texts.lastOrNull()?.isBlank() != false)
        val node = XmlNode(qName, MutableList(attributes.length) { attributes.getQName(it) to attributes.getValue(it) })
        if (parent == null) {
            checkKdbx(root == null)
            root = node
        } else parent.children.add(node)
        path.addLast(node)
        texts.addLast(StringBuilder())
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        val text = texts.lastOrNull() ?: return
        chars += length
        checkKdbx(chars <= MAX_TEXT_CHARS, KdbxFailure.LIMIT)
        text.append(ch, start, length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val node = path.removeLast()
        val text = texts.removeLast()
        try {
            if (node.children.isNotEmpty()) {
                checkKdbx(text.isBlank())
                return
            }
            if (node.protected) {
                val bytes = decodeBase64(text)
                if (bytes.isNotEmpty()) (decrypt ?: throw KdbxException(KdbxFailure.CORRUPT))(bytes)
                node.secret = bytes
            } else node.text = text.toString()
        } finally {
            for (index in text.indices) text.setCharAt(index, '\u0000')
        }
    }

    fun clear() {
        root?.clear()
        texts.forEach { text -> for (index in text.indices) text.setCharAt(index, '\u0000') }
    }
}

/** A value to store in an entry. [value] is UTF-8, which the caller wipes. */
internal class EntryField(val name: String, val value: ByteArray, val protected: Boolean)

/** Thrown when the entry being edited is no longer in the database, for example deleted elsewhere. */
internal class EntryMissingException : Exception()

/** Thrown when the group chosen for a new entry is no longer in the database. */
internal class GroupMissingException : Exception()

/** Thrown when the recycle bin no longer holds the entries shown, so emptying it would lose others. */
internal class BinChangedException : Exception()

/**
 * The changes KeeBerry makes to a document: a new entry, new values for an existing one, deleting
 * one, restoring one from the recycle bin, or emptying the bin. Like KeePass, an edit keeps the entry's previous
 * version in its history.
 */
internal class EntryEdits(private val random: SecureRandom, private val now: () -> Long) {
    /**
     * Stores [fields] in the entry [uuid], or in a new entry when [uuid] is null. A new entry goes in
     * [group], or in the root group when that is null. Returns the entry's UUID.
     */
    fun apply(document: KdbxDocument, uuid: ByteArray?, fields: List<EntryField>, group: ByteArray? = null): ByteArray {
        val root = document.root.child("Root") ?: throw KdbxException(KdbxFailure.CORRUPT)
        val time = kdbxTime(now())
        if (uuid == null) {
            val top = root.child("Group") ?: throw KdbxException(KdbxFailure.CORRUPT)
            val target = if (group == null) top else findGroup(top, group) ?: throw GroupMissingException()
            val created = ByteArray(16).also(random::nextBytes)
            val entry = newEntry(created, time)
            setFields(entry, fields)
            addEntry(target, entry)
            return created
        }
        val entry = findPath(root, uuid)?.last() ?: throw EntryMissingException()
        keepHistory(document, entry)
        setFields(entry, fields)
        val times = entry.child("Times") ?: entry.add("Times").also { addTimes(it, time) }
        for (name in listOf("LastModificationTime", "LastAccessTime")) {
            (times.child(name) ?: times.add(name)).text = time
        }
        return uuid
    }

    /**
     * Deletes the entry [uuid] as KeePass does: it moves to the recycle bin, which is created if the
     * database has none yet. With the recycle bin turned off, or for an entry already in it, the entry
     * is removed and listed under the deleted objects, so syncing with another copy doesn't restore it.
     */
    fun delete(document: KdbxDocument, uuid: ByteArray) {
        val root = document.root.child("Root") ?: throw KdbxException(KdbxFailure.CORRUPT)
        val top = root.child("Group") ?: throw KdbxException(KdbxFailure.CORRUPT)
        val path = findPath(top, uuid) ?: throw EntryMissingException()
        val entry = path.last()
        val parent = path[path.size - 2]
        val meta = document.root.child("Meta")
        val time = kdbxTime(now())
        val useBin = meta != null && meta.child("RecycleBinEnabled")?.text?.trim()?.equals("false", ignoreCase = true) != true
        if (useBin) {
            val bin = recycleBin(meta, top, time)
            if (path.none { it === bin }) {
                move(document, entry, parent, bin, time)
                return
            }
        }
        parent.children.remove(entry)
        entry.clear()
        val deleted = root.child("DeletedObjects") ?: root.add("DeletedObjects")
        deleted.add("DeletedObject").apply {
            add("UUID", Base64.getEncoder().encodeToString(uuid))
            add("DeletionTime", time)
        }
    }

    /**
     * Moves the entry [uuid] out of the recycle bin, where it may sit in a deleted group, as KeePass
     * restores it: back to the group it was deleted from, or to the root group when that group is
     * gone or is in the bin too.
     */
    fun restore(document: KdbxDocument, uuid: ByteArray) {
        val root = document.root.child("Root") ?: throw KdbxException(KdbxFailure.CORRUPT)
        val top = root.child("Group") ?: throw KdbxException(KdbxFailure.CORRUPT)
        val bin = document.root.child("Meta")?.let(::named)?.let { findGroup(top, it) }
        // Restored from elsewhere since the unlock, or never deleted.
        if (bin == null || bin === top) throw EntryMissingException()
        val path = findPath(bin, uuid) ?: throw EntryMissingException()
        val entry = path.last()
        val previous = entry.child("PreviousParentGroup")?.text?.let(::decodeUuid)?.let { findGroup(top, it) }
        val outside = previous?.takeIf { group -> uuidOf(group)?.let { findGroup(bin, it) } == null }
        move(document, entry, path[path.size - 2], outside ?: top, kdbxTime(now()))
    }

    /**
     * Deletes everything in the recycle bin for good, as KeePass empties it: its entries and groups
     * go, each listed under the deleted objects, and the bin itself stays. [expected] names the entries
     * the user saw there, by [hexString] UUID or an empty string for one without a valid UUID. If the
     * bin holds others now, nothing is deleted.
     */
    fun emptyBin(document: KdbxDocument, expected: List<String>) {
        val root = document.root.child("Root") ?: throw KdbxException(KdbxFailure.CORRUPT)
        val top = root.child("Group") ?: throw KdbxException(KdbxFailure.CORRUPT)
        val bin = document.root.child("Meta")?.let(::named)?.let { findGroup(top, it) }?.takeIf { it !== top }
        val contents = bin?.children?.filter { it.name == "Entry" || it.name == "Group" }.orEmpty()
        val entries = mutableListOf<XmlNode>()
        val groups = mutableListOf<XmlNode>()
        fun collect(node: XmlNode) {
            if (node.name == "Entry") entries.add(node)
            else {
                groups.add(node)
                node.children.filter { it.name == "Entry" || it.name == "Group" }.forEach(::collect)
            }
        }
        contents.forEach(::collect)
        val found = entries.map { entry -> uuidOf(entry)?.takeIf { it.size == 16 }?.hexString().orEmpty() }
        if (found.sorted() != expected.sorted()) throw BinChangedException()
        if (bin == null) return
        val time = kdbxTime(now())
        val deleted = root.child("DeletedObjects") ?: root.add("DeletedObjects")
        for (node in entries + groups) {
            val uuid = uuidOf(node)?.takeIf { it.size == 16 } ?: continue
            deleted.add("DeletedObject").apply {
                add("UUID", Base64.getEncoder().encodeToString(uuid))
                add("DeletionTime", time)
            }
        }
        bin.children.removeAll(contents)
        contents.forEach(XmlNode::clear)
    }

    /**
     * Moves [entry] from [parent] to [target] and notes when. KDBX 4.1 also records where it came
     * from, which is how a restore from the recycle bin finds its group.
     */
    private fun move(document: KdbxDocument, entry: XmlNode, parent: XmlNode, target: XmlNode, time: String) {
        parent.children.remove(entry)
        addEntry(target, entry)
        val times = entry.child("Times") ?: entry.add("Times").also { addTimes(it, time) }
        (times.child("LocationChanged") ?: times.add("LocationChanged")).text = time
        val from = uuidOf(parent) ?: return
        if (document.header.major != 4 || document.header.minor < 1) return
        val previous = entry.child("PreviousParentGroup") ?: XmlNode("PreviousParentGroup").also { node ->
            // Where KeePass writes it: just before the entry's times.
            val at = entry.children.indexOfFirst { it.name == "Times" }
            if (at < 0) entry.children.add(node) else entry.children.add(at, node)
        }
        previous.text = Base64.getEncoder().encodeToString(from)
    }

    /** The recycle bin's UUID as [meta] names it, if the database has one. */
    private fun named(meta: XmlNode): ByteArray? = meta.child("RecycleBinUUID")?.text?.let(::decodeUuid)
        ?.takeIf { uuid -> uuid.size == 16 && uuid.any { it != 0.toByte() } }

    /** The recycle bin Meta names, or a new one in the root group, as KeePass creates it. */
    private fun recycleBin(meta: XmlNode, top: XmlNode, time: String): XmlNode {
        named(meta)?.let { uuid -> findGroup(top, uuid)?.let { return it } }
        val uuid = ByteArray(16).also(random::nextBytes)
        val bin = XmlNode("Group").apply {
            add("UUID", Base64.getEncoder().encodeToString(uuid))
            add("Name", "Recycle Bin")
            add("Notes")
            add("IconID", "43")
            addTimes(add("Times"), time)
            add("IsExpanded", "False")
            add("DefaultAutoTypeSequence")
            add("EnableAutoType", "false")
            add("EnableSearching", "false")
            add("LastTopVisibleEntry", Base64.getEncoder().encodeToString(ByteArray(16)))
        }
        top.children.add(bin)
        (meta.child("RecycleBinUUID") ?: meta.add("RecycleBinUUID")).text = Base64.getEncoder().encodeToString(uuid)
        (meta.child("RecycleBinChanged") ?: meta.add("RecycleBinChanged")).text = time
        return bin
    }

    /** KeePass lists a group's entries before its subgroups. */
    private fun addEntry(group: XmlNode, entry: XmlNode) {
        val firstGroup = group.children.indexOfFirst { it.name == "Group" }
        if (firstGroup < 0) group.children.add(entry) else group.children.add(firstGroup, entry)
    }

    /** The groups from [node] down to the entry [uuid], then the entry. History isn't searched. */
    private fun findPath(node: XmlNode, uuid: ByteArray): List<XmlNode>? {
        for (child in node.children) {
            when (child.name) {
                "Entry" -> if (uuidOf(child)?.contentEquals(uuid) == true) return listOf(node, child)
                "Group" -> findPath(child, uuid)?.let { return listOf(node) + it }
            }
        }
        return null
    }

    /** [node] itself or the group inside it with [uuid]. */
    private fun findGroup(node: XmlNode, uuid: ByteArray): XmlNode? {
        if (uuidOf(node)?.contentEquals(uuid) == true) return node
        for (child in node.childrenNamed("Group")) findGroup(child, uuid)?.let { return it }
        return null
    }

    private fun uuidOf(node: XmlNode): ByteArray? = node.child("UUID")?.text?.let(::decodeUuid)

    private fun decodeUuid(text: String): ByteArray? = runCatching { decodeBase64(text) }.getOrNull()

    /** Copies the entry, without its own history, to the end of its history, within the limit. */
    private fun keepHistory(document: KdbxDocument, entry: XmlNode) {
        val snapshot = entry.deepCopy()
        snapshot.children.removeAll { it.name == "History" }
        val history = entry.child("History") ?: entry.add("History")
        history.children.add(snapshot)
        val limit = document.root.child("Meta")?.child("HistoryMaxItems")?.text?.trim()?.toIntOrNull() ?: 10
        // A negative limit means no limit.
        if (limit < 0) return
        while (history.children.size > limit) history.children.removeAt(0).clear()
    }

    /** Replaces the entry's strings where they were, keeping KeePass's element order. */
    private fun setFields(entry: XmlNode, fields: List<EntryField>) {
        var at = entry.children.indexOfFirst { it.name == "String" }
        if (at < 0) {
            at = entry.children.indexOfFirst { it.name in AFTER_STRINGS }.takeIf { it >= 0 } ?: entry.children.size
        }
        val removed = entry.children.filter { it.name == "String" }
        entry.children.removeAll { it.name == "String" }
        removed.forEach(XmlNode::clear)
        at = at.coerceAtMost(entry.children.size)
        val strings = fields.map { field ->
            XmlNode("String").apply {
                add("Key", field.name)
                val value = XmlNode("Value", if (field.protected) mutableListOf("Protected" to "True") else mutableListOf())
                if (field.protected) value.secret = field.value.copyOf()
                else value.text = field.value.toString(Charsets.UTF_8)
                children.add(value)
            }
        }
        entry.children.addAll(at, strings)
    }

    private fun newEntry(uuid: ByteArray, time: String): XmlNode = XmlNode("Entry").apply {
        add("UUID", Base64.getEncoder().encodeToString(uuid))
        add("IconID", "0")
        add("ForegroundColor")
        add("BackgroundColor")
        add("OverrideURL")
        add("Tags")
        addTimes(add("Times"), time)
        add("AutoType").apply {
            add("Enabled", "True")
            add("DataTransferObfuscation", "0")
        }
        add("History")
    }

    private fun addTimes(times: XmlNode, time: String) {
        times.add("CreationTime", time)
        times.add("LastModificationTime", time)
        times.add("LastAccessTime", time)
        times.add("ExpiryTime", time)
        times.add("Expires", "False")
        times.add("UsageCount", "0")
        times.add("LocationChanged", time)
    }

    private companion object {
        val AFTER_STRINGS = setOf("Binary", "AutoType", "History", "CustomData", "QualityCheck", "PreviousParentGroup")
    }
}

/**
 * Saves one entry change to a KDBX 4 database: decrypts [current] with [key], applies the change
 * to the whole document and encrypts it again, keeping everything else as it was.
 */
internal class KdbxSaver(
    private val chaCha20: (ByteArray, ByteArray) -> Cipher = ::platformChaCha20,
    private val checkCancelled: () -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    private val argon2: Argon2Kdf = BouncyCastleArgon2(),
    private val aesKdf: AesKdf = JcaAesKdf,
    private val onStage: (KdbxStage) -> Unit = {},
) {
    private val reader = KdbxReader(chaCha20 = chaCha20, checkCancelled = checkCancelled)

    class Saved(val bytes: ByteArray, val uuid: ByteArray)

    /** A database encrypted under a new master key, and that key. */
    class Rekeyed(val bytes: ByteArray, val key: KdbxKey)

    /**
     * Encrypts [current], which [key] opens, under a new master key made of whichever of [password],
     * the key file [keyFile] opens and the YubiKey's [challengeResponse] are given, combined as
     * [KdbxReader] combines them. The KDF keeps its settings but gets a new salt, as in KeePass, and
     * the document notes when its master key changed. [keyFile] is read and closed.
     */
    fun changeKey(
        current: ByteArray,
        key: KdbxKey,
        password: CharArray?,
        keyFile: (() -> InputStream)? = null,
        challengeResponse: ((ByteArray) -> ByteArray)? = null,
    ): Rekeyed {
        require(password != null || keyFile != null || challengeResponse != null)
        val document = reader.decryptWithKey(ByteArrayInputStream(current), key) { header, payload ->
            KdbxDocument.read(header, payload, chaCha20, checkCancelled)
        }
        var transformed: ByteArray? = null
        try {
            val parameters = kdfParametersWithNewSalt(document.header.kdfParameters, random)
            val header = KdbxHeader.read(DataInputStream(ByteArrayInputStream(document.header.rebuilt(mapOf(11 to parameters)))))
            transformed = if (keyFile == null) {
                transformKey(header, password, null, argon2, aesKdf, checkCancelled, challengeResponse, onStage)
            } else keyFile().use {
                transformKey(header, password, it, argon2, aesKdf, checkCancelled, challengeResponse, onStage)
            }
            document.root.child("Meta")?.let { meta ->
                (meta.child("MasterKeyChanged") ?: meta.add("MasterKeyChanged")).text = kdbxTime(now())
            }
            val output = ByteArrayOutputStream(current.size + 4096)
            KdbxWriter(random = random, now = now, chaCha20 = chaCha20)
                .save(output, header, transformed, document.binaries, document::write)
            return Rekeyed(output.toByteArray(), KdbxKey(transformed.copyOf()))
        } finally {
            transformed?.fill(0)
            document.clear()
        }
    }

    /** Stores [fields] in entry [uuid], or in a new entry in [group] when it is null. */
    fun save(current: ByteArray, key: KdbxKey, uuid: ByteArray?, fields: List<EntryField>, group: ByteArray? = null): Saved {
        var saved: ByteArray? = null
        val bytes = rewrite(current, key) { saved = EntryEdits(random, now).apply(it, uuid, fields, group) }
        return Saved(bytes, checkNotNull(saved))
    }

    /** Deletes entry [uuid], into the recycle bin when the database uses one. */
    fun delete(current: ByteArray, key: KdbxKey, uuid: ByteArray): ByteArray =
        rewrite(current, key) { EntryEdits(random, now).delete(it, uuid) }

    /** Moves entry [uuid] out of the recycle bin, back to the group it was deleted from. */
    fun restore(current: ByteArray, key: KdbxKey, uuid: ByteArray): ByteArray =
        rewrite(current, key) { EntryEdits(random, now).restore(it, uuid) }

    /** Deletes everything in the recycle bin for good, if it still holds just the [expected] entries. */
    fun emptyBin(current: ByteArray, key: KdbxKey, expected: List<String>): ByteArray =
        rewrite(current, key) { EntryEdits(random, now).emptyBin(it, expected) }

    private fun rewrite(current: ByteArray, key: KdbxKey, edit: (KdbxDocument) -> Unit): ByteArray {
        val document = reader.decryptWithKey(ByteArrayInputStream(current), key) { header, payload ->
            KdbxDocument.read(header, payload, chaCha20, checkCancelled)
        }
        val transformed = key.transformed()
        try {
            edit(document)
            val output = ByteArrayOutputStream(current.size + 4096)
            KdbxWriter(random = random, now = now, chaCha20 = chaCha20)
                .save(output, document.header, transformed, document.binaries, document::write)
            return output.toByteArray()
        } finally {
            transformed.fill(0)
            document.clear()
        }
    }

    /** Opens what [save] wrote, which also shows that it can be read back. */
    fun open(bytes: ByteArray, key: KdbxKey): Database = reader.open(ByteArrayInputStream(bytes), key)
}
