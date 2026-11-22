package app.keeberry.kdbx

import java.io.Closeable

/**
 * An unlocked database. [key] is the derived master key of a KDBX 4 database, which lets it be saved
 * without asking for the credentials again. KDBX 3 databases have none and stay read only.
 * [groups] lists every group outside the recycle bin in document order, the root group first, and
 * [recycleBin] says whether deleting moves entries there instead of removing them.
 */
internal class Database(
    val name: String,
    val entries: List<DatabaseEntry>,
    internal val key: KdbxKey? = null,
    val groups: List<DatabaseGroup> = emptyList(),
    val recycleBin: Boolean = true,
) : Closeable {
    val editable: Boolean get() = key != null

    internal fun withKey(key: KdbxKey) = Database(name, entries, key, groups, recycleBin)

    override fun close() {
        entries.forEach(DatabaseEntry::clear)
        key?.close()
    }
}

/** [uuid] is null only for an entry stored without a valid one, which can't be edited. */
internal class DatabaseEntry(val group: String, val fields: List<DatabaseField>, val uuid: ByteArray? = null) {
    val title: String get() = fields.firstOrNull { it.name == "Title" }?.text().orEmpty()

    /** A stable name for the entry across saves, which reorder the list. */
    val key: String? get() = uuid?.hexString()

    fun clear() = fields.forEach(DatabaseField::clear)
}

/** A group an entry can be put in. [path] names it with its parents; [depth] is 0 for the root group. */
internal class DatabaseGroup(val uuid: ByteArray, val path: String, val depth: Int) {
    val key: String get() = uuid.hexString()
}

internal class DatabaseField(val name: String, private val bytes: ByteArray, val protected: Boolean) {
    val sensitive: Boolean = protected || name == "Password"

    fun text(): String = bytes.toString(Charsets.UTF_8)

    fun clear() = bytes.fill(0)
}

/** The KDF output of a KDBX 4 database. Combined with a fresh seed it encrypts every save. */
internal class KdbxKey(private val bytes: ByteArray) : Closeable {
    fun copy(): KdbxKey = KdbxKey(bytes.copyOf())

    /** A copy the caller wipes. */
    fun transformed(): ByteArray = bytes.copyOf()

    override fun close() = bytes.fill(0)
}
