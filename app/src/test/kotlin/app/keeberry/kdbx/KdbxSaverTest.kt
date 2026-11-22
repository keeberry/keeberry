package app.keeberry.kdbx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KdbxSaverTest {
    // The JVM names the initial counter explicitly. Android Conscrypt starts at zero.
    private val jvmChaCha = { key: ByteArray, nonce: ByteArray ->
        Cipher.getInstance("ChaCha20").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, 0))
        }
    }

    private val reader = KdbxReader(chaCha20 = jvmChaCha)
    private val saver = KdbxSaver(chaCha20 = jvmChaCha, now = { 1_700_000_000_000L })

    private val modern = listOf("modern-argon2id.kdbx", "modern-argon2d-chacha.kdbx", "modern-aes-kdf.kdbx",
        "modern-twofish.kdbx")

    @Test
    fun keepsTheKeyOfKdbx4DatabasesOnly() {
        open("modern-argon2id.kdbx").use { assertTrue(it.editable) }
        open("password.kdbx").use { assertFalse(it.editable) }
    }

    @Test
    fun editsAnEntryAcrossCiphersAndKdfsAndTheCredentialsStillOpenIt() {
        for (file in modern) {
            val original = bytes(file)
            open(file).use { database ->
                val entry = database.entries.first()
                val saved = saver.save(original, database.key!!, entry.uuid, fields("Sample entry", "new-user", "new-password"))
                assertArrayEquals(entry.uuid, saved.uuid)
                // The settings stay, the seed and IV are new.
                val before = header(original)
                val after = header(saved.bytes)
                assertArrayEquals(before.cipherId, after.cipherId)
                assertArrayEquals(before.kdfParameters, after.kdfParameters)
                assertEquals(before.compression, after.compression)
                assertFalse(before.masterSeed.contentEquals(after.masterSeed))
                assertFalse(before.iv.contentEquals(after.iv))
                // The password alone still opens it, so other KeePass apps do too.
                reader.open(saved.bytes.inputStream(), password()).use { reopened ->
                    assertEquals(file, listOf("Sample entry", "Second entry"), reopened.entries.map { it.title })
                    val edited = reopened.entries.first()
                    assertEquals("new-user", edited.field("UserName").text())
                    assertEquals("new-password", edited.field("Password").text())
                    assertTrue(edited.field("Password").protected)
                    assertEquals("Fixture group", edited.group)
                    // The entry after it still decrypts, so the inner stream stayed in step.
                    assertEquals("second-password", reopened.entries.last().field("Password").text())
                }
            }
        }
    }

    @Test
    fun keepsThePreviousVersionInTheHistoryAndEverythingElseAsItWas() {
        val original = bytes("modern-argon2id.kdbx")
        open("modern-argon2id.kdbx").use { database ->
            val entry = database.entries.first()
            val saved = saver.save(original, database.key!!, entry.uuid, fields("Renamed", "fixture-user", "changed"))
            val before = document(original, database.key)
            val after = document(saved.bytes, database.key)
            assertTrue(same(before.root.child("Meta")!!, after.root.child("Meta")!!))
            val oldEntry = entries(before.root).first()
            val newEntry = entries(after.root).first()
            val history = newEntry.child("History")!!.children
            val previous = history.last()
            val withoutHistory = oldEntry.deepCopy().apply { children.removeAll { it.name == "History" } }
            assertTrue(same(withoutHistory, previous))
            assertEquals(oldEntry.child("History")?.children.orEmpty().size + 1, history.size)
            // Every other entry is untouched.
            assertTrue(same(entries(before.root)[1], entries(after.root)[1]))
            before.clear()
            after.clear()
        }
    }

    @Test
    fun addsAnEntryToTheRootGroup() {
        val original = bytes("modern-aes-kdf.kdbx")
        open("modern-aes-kdf.kdbx").use { database ->
            val fields = fields("Bank", "me", "pa&ss<word>") + EntryField("PIN", "1234".toByteArray(), protected = true)
            val saved = saver.save(original, database.key!!, null, fields)
            saver.open(saved.bytes, database.key).use { reopened ->
                assertTrue(reopened.editable)
                val added = reopened.entries.single { it.uuid.contentEquals(saved.uuid) }
                assertEquals("Bank", added.title)
                assertEquals("pa&ss<word>", added.field("Password").text())
                assertEquals("1234", added.field("PIN").text())
                assertTrue(added.field("PIN").sensitive)
                assertEquals(3, reopened.entries.size)
            }
        }
    }

    @Test
    fun keepsTheLineEndingsOfValuesAsTyped() {
        val original = bytes("modern-argon2id.kdbx")
        open("modern-argon2id.kdbx").use { database ->
            val notes = "first\r\nsecond\nthird\ttab"
            val fields = fields("Notes", "", "").map {
                if (it.name == "Notes") EntryField("Notes", notes.toByteArray(), false) else it
            }
            val saved = saver.save(original, database.key!!, null, fields)
            saver.open(saved.bytes, database.key).use { reopened ->
                val added = reopened.entries.single { it.uuid.contentEquals(saved.uuid) }
                assertEquals(notes, added.field("Notes").text())
            }
        }
    }

    @Test
    fun savesAYubiKeyDatabaseWithoutAskingTheKeyAgain() {
        val original = bytes("yubikey-modern.kdbx")
        val database = reader.open(original.inputStream(), password(), modernKeyFile(), ::modernYubiKey)
        database.use {
            val saved = saver.save(original, it.key!!, null, fields("Added", "", "secret"))
            var asked = 0
            reader.open(saved.bytes.inputStream(), password(), modernKeyFile()) { challenge ->
                asked++
                modernYubiKey(challenge)
            }.use { reopened -> assertTrue(reopened.entries.any { entry -> entry.title == "Added" }) }
            assertEquals(1, asked)
        }
    }

    @Test
    fun refusesAFileWhoseCredentialsChangedSinceTheUnlock() {
        open("modern-argon2id.kdbx").use { database ->
            val other = bytes("modern-aes-kdf.kdbx")
            val error = assertThrows(KdbxException::class.java) {
                saver.save(other, database.key!!, null, fields("x", "", ""))
            }
            assertEquals(KdbxFailure.CREDENTIALS, error.failure)
        }
    }

    @Test
    fun reportsAnEntryDeletedSinceTheUnlock() {
        open("modern-argon2id.kdbx").use { database ->
            assertThrows(EntryMissingException::class.java) {
                saver.save(bytes("modern-argon2id.kdbx"), database.key!!, ByteArray(16) { 7 }, fields("x", "", ""))
            }
        }
    }

    @Test
    fun closingTheDatabaseWipesItsKey() {
        val database = open("modern-argon2id.kdbx")
        val key = database.key!!
        database.close()
        assertTrue(key.transformed().all { it == 0.toByte() })
        assertNull(open("password.kdbx").use { it.key })
    }

    @Test
    fun listsTheGroupsOutsideTheRecycleBin() {
        val original = withSubgroup(bytes("modern-argon2id.kdbx"))
        reader.open(original.inputStream(), password()).use { database ->
            assertEquals(listOf("Fixture group", "Fixture group / Banking"), database.groups.map { it.path })
            assertEquals(listOf(0, 1), database.groups.map { it.depth })
            // Once the recycle bin exists, it and what's in it stay out of the list.
            val deleted = saver.delete(original, database.key!!, database.entries.first().uuid!!)
            saver.open(deleted, database.key).use { reopened ->
                assertEquals(database.groups.map { it.path }, reopened.groups.map { it.path })
            }
        }
    }

    @Test
    fun addsAnEntryToTheChosenGroup() {
        val original = withSubgroup(bytes("modern-argon2id.kdbx"))
        reader.open(original.inputStream(), password()).use { database ->
            val banking = database.groups.single { it.depth == 1 }
            val saved = saver.save(original, database.key!!, null, fields("Bank", "me", "pin"), banking.uuid)
            saver.open(saved.bytes, database.key).use { reopened ->
                assertEquals("Fixture group / Banking", reopened.entries.single { it.uuid.contentEquals(saved.uuid) }.group)
            }
            assertThrows(GroupMissingException::class.java) {
                saver.save(original, database.key, null, fields("x", "", ""), ByteArray(16) { 9 })
            }
        }
    }

    @Test
    fun deletingMovesTheEntryToTheRecycleBin() {
        val original = bytes("modern-argon2id.kdbx")
        open("modern-argon2id.kdbx").use { database ->
            val (first, second) = database.entries
            val once = saver.delete(original, database.key!!, first.uuid!!)
            val twice = saver.delete(once, database.key, second.uuid!!)
            saver.open(twice, database.key).use { assertTrue(it.entries.isEmpty()) }
            val document = document(twice, database.key)
            val binUuid = decodeBase64(document.root.child("Meta")!!.child("RecycleBinUUID")!!.text!!)
            val bins = groups(document.root).filter { it.child("Name")?.text == "Recycle Bin" }
            // The second delete found the bin the first one made.
            assertEquals(1, bins.size)
            assertArrayEquals(binUuid, decodeBase64(bins.single().child("UUID")!!.text!!))
            val binned = bins.single().childrenNamed("Entry").map { decodeBase64(it.child("UUID")!!.text!!) }
            assertEquals(2, binned.size)
            assertTrue(binned.any { it.contentEquals(first.uuid) } && binned.any { it.contentEquals(second.uuid) })
            // Binned entries keep their values, so they can be restored.
            val password = bins.single().childrenNamed("Entry").first { decodeBase64(it.child("UUID")!!.text!!).contentEquals(first.uuid) }
                .childrenNamed("String").single { it.child("Key")?.text == "Password" }.child("Value")!!.secret!!
            assertEquals(first.field("Password").text(), password.toString(Charsets.UTF_8))
            assertNull(document.root.child("Root")!!.child("DeletedObjects")?.children?.firstOrNull())
            document.clear()
        }
    }

    @Test
    fun deletingUsesTheRecycleBinTheDatabaseHas() {
        // Made by KeePassXC, with a recycle bin that already holds entries and a group.
        val original = bytes("recycle-bin.kdbx")
        open("recycle-bin.kdbx").use { database ->
            assertEquals(listOf("Passwords", "Passwords / Work"), database.groups.map { it.path })
            val live = database.entries.single { it.title == "Live entry" }
            val deleted = saver.delete(original, database.key!!, live.uuid!!)
            saver.open(deleted, database.key).use { reopened ->
                assertEquals(listOf("Work entry"), reopened.entries.map { it.title })
                assertEquals(database.groups.map { it.path }, reopened.groups.map { it.path })
            }
            val before = document(original, database.key)
            val after = document(deleted, database.key)
            val bins = groups(after.root).filter { it.child("Name")?.text == "Recycle Bin" }
            assertEquals(1, bins.size)
            assertEquals(groups(before.root).size, groups(after.root).size)
            assertTrue(bins.single().childrenNamed("Entry").any { decodeBase64(it.child("UUID")!!.text!!).contentEquals(live.uuid) })
            assertTrue(same(before.root.child("Meta")!!, after.root.child("Meta")!!))
            before.clear()
            after.clear()
        }
    }

    @Test
    fun deletingForGoodWithTheRecycleBinOff() {
        val original = bytes("modern-argon2id.kdbx")
        open("modern-argon2id.kdbx").use { database ->
            val off = rewrite(original, database.key!!) { document ->
                val meta = document.root.child("Meta")!!
                (meta.child("RecycleBinEnabled") ?: meta.add("RecycleBinEnabled")).text = "False"
            }
            val uuid = database.entries.first().uuid!!
            reader.open(off.inputStream(), password()).use { assertFalse(it.recycleBin) }
            val deleted = saver.delete(off, database.key, uuid)
            val document = document(deleted, database.key)
            assertEquals(1, entries(document.root).size)
            assertTrue(groups(document.root).none { it.child("Name")?.text == "Recycle Bin" })
            val record = document.root.child("Root")!!.child("DeletedObjects")!!.childrenNamed("DeletedObject").single()
            assertArrayEquals(uuid, decodeBase64(record.child("UUID")!!.text!!))
            assertEquals(kdbxTime(1_700_000_000_000L), record.child("DeletionTime")!!.text)
            document.clear()
            assertThrows(EntryMissingException::class.java) { saver.delete(deleted, database.key, uuid) }
        }
    }

    @Test
    fun restoresAnEntryToTheGroupItWasDeletedFrom() {
        val original = bytes("recycle-bin.kdbx")
        open("recycle-bin.kdbx").use { database ->
            val work = database.entries.single { it.title == "Work entry" }
            val deleted = saver.delete(original, database.key!!, work.uuid!!)
            saver.open(deleted, database.key).use { binned ->
                assertEquals("Passwords / Work", binned.binned.single { it.title == "Work entry" }.group)
            }
            val before = document(deleted, database.key)
            val binUuid = decodeBase64(before.root.child("Meta")!!.child("RecycleBinUUID")!!.text!!)
            before.clear()
            val restored = saver.restore(deleted, database.key, work.uuid)
            saver.open(restored, database.key).use { reopened ->
                val entry = reopened.entries.single { it.uuid.contentEquals(work.uuid) }
                assertEquals("Passwords / Work", entry.group)
                assertEquals("work-user", entry.field("UserName").text())
                assertTrue(reopened.binned.none { it.uuid.contentEquals(work.uuid) })
                assertEquals(database.binned.size, reopened.binned.size)
            }
            // As KeePass moves entries, the restored one notes it came from the bin.
            val document = document(restored, database.key)
            val node = entries(document.root).single { decodeBase64(it.child("UUID")!!.text!!).contentEquals(work.uuid) }
            assertArrayEquals(binUuid, decodeBase64(node.child("PreviousParentGroup")!!.text!!))
            assertEquals(kdbxTime(1_700_000_000_000L), node.child("Times")!!.child("LocationChanged")!!.text)
            document.clear()
        }
    }

    @Test
    fun restoresAnEntryOfADeletedGroupToTheRootGroup() {
        val original = bytes("recycle-bin.kdbx")
        open("recycle-bin.kdbx").use { database ->
            val old = database.binned.single { it.title == "Old entry" }
            val restored = saver.restore(original, database.key!!, old.uuid!!)
            saver.open(restored, database.key).use { reopened ->
                assertEquals("Passwords", reopened.entries.single { it.uuid.contentEquals(old.uuid) }.group)
                assertEquals(listOf("Deleted entry"), reopened.binned.map { it.title })
                // The deleted group stays in the bin.
                assertEquals(database.groups.map { it.path }, reopened.groups.map { it.path })
            }
        }
    }

    @Test
    fun restoringAnEntryOutsideTheBinReportsItMissing() {
        val original = bytes("recycle-bin.kdbx")
        open("recycle-bin.kdbx").use { database ->
            val live = database.entries.single { it.title == "Live entry" }
            assertThrows(EntryMissingException::class.java) { saver.restore(original, database.key!!, live.uuid!!) }
        }
        open("modern-argon2id.kdbx").use { database ->
            // No recycle bin at all.
            assertThrows(EntryMissingException::class.java) {
                saver.restore(bytes("modern-argon2id.kdbx"), database.key!!, database.entries.first().uuid!!)
            }
        }
    }

    @Test
    fun kdbx40RestoresToTheRootGroupWithoutRecordingWhere() {
        val original = withSubgroup(bytes("modern-aes-kdf.kdbx"), "modern-aes-kdf.kdbx")
        reader.open(original.inputStream(), password()).use { database ->
            assertEquals(0, header(original).minor)
            val banking = database.groups.single { it.depth == 1 }
            val added = saver.save(original, database.key!!, null, fields("Bank", "me", "pin"), banking.uuid)
            val deleted = saver.delete(added.bytes, database.key, added.uuid)
            val document = document(deleted, database.key)
            // PreviousParentGroup is new in KDBX 4.1, so a 4.0 file doesn't get it.
            assertTrue(entries(document.root).none { it.child("PreviousParentGroup") != null })
            document.clear()
            val restored = saver.restore(deleted, database.key, added.uuid)
            saver.open(restored, database.key).use { reopened ->
                assertEquals("Fixture group", reopened.entries.single { it.uuid.contentEquals(added.uuid) }.group)
                assertTrue(reopened.binned.isEmpty())
            }
        }
    }

    @Test
    fun deletingFromTheBinDeletesForGood() {
        val original = bytes("recycle-bin.kdbx")
        open("recycle-bin.kdbx").use { database ->
            val binned = database.binned.single { it.title == "Deleted entry" }
            val deleted = saver.delete(original, database.key!!, binned.uuid!!)
            saver.open(deleted, database.key).use { reopened ->
                assertEquals(listOf("Old entry"), reopened.binned.map { it.title })
                assertEquals(database.entries.size, reopened.entries.size)
            }
            val document = document(deleted, database.key)
            val record = document.root.child("Root")!!.child("DeletedObjects")!!.childrenNamed("DeletedObject").last()
            assertArrayEquals(binned.uuid, decodeBase64(record.child("UUID")!!.text!!))
            document.clear()
        }
    }

    @Test
    fun emptiesTheRecycleBinForGood() {
        val original = bytes("recycle-bin.kdbx")
        open("recycle-bin.kdbx").use { database ->
            val expected = database.binned.map { it.uuid!!.hexString() }
            val emptied = saver.emptyBin(original, database.key!!, expected)
            saver.open(emptied, database.key).use { reopened ->
                assertTrue(reopened.binned.isEmpty())
                assertEquals(database.entries.map { it.title }, reopened.entries.map { it.title })
                assertEquals(database.groups.map { it.path }, reopened.groups.map { it.path })
            }
            val before = document(original, database.key)
            val after = document(emptied, database.key)
            // The bin itself stays, empty, and Meta still names it.
            val bin = groups(after.root).single { it.child("Name")?.text == "Recycle Bin" }
            assertTrue(bin.children.none { it.name == "Entry" || it.name == "Group" })
            assertTrue(same(before.root.child("Meta")!!, after.root.child("Meta")!!))
            // Both entries and the deleted "Old" group are listed, so a sync doesn't bring them back.
            val oldGroup = groups(before.root).single { it.child("Name")?.text == "Old" }.child("UUID")!!.text!!
            val records = after.root.child("Root")!!.child("DeletedObjects")!!.childrenNamed("DeletedObject")
                .map { it.child("UUID")!!.text!! }
            val listed = records.map { decodeBase64(it).hexString() }
            assertTrue(listed.containsAll(expected))
            assertTrue(oldGroup in records)
            assertEquals(groups(before.root).size - 1, groups(after.root).size)
            before.clear()
            after.clear()
        }
    }

    @Test
    fun refusesToEmptyABinThatChangedSinceTheUnlock() {
        val original = bytes("recycle-bin.kdbx")
        open("recycle-bin.kdbx").use { database ->
            val expected = database.binned.map { it.uuid!!.hexString() }
            // Another app deleted an entry the user hasn't seen in the bin.
            val elsewhere = saver.delete(original, database.key!!, database.entries.first().uuid!!)
            assertThrows(BinChangedException::class.java) { saver.emptyBin(elsewhere, database.key, expected) }
            // Emptied elsewhere already counts as changed too.
            val emptied = saver.emptyBin(original, database.key, expected)
            assertThrows(BinChangedException::class.java) { saver.emptyBin(emptied, database.key, expected) }
        }
    }

    /** Adds a group named Banking inside the root group of [file]. */
    @Test
    fun changesTheMasterKeyAcrossCiphersAndKdfsAndKeepsEverythingElse() {
        val keyFile = ByteArray(32) { (it + 7).toByte() }
        for (file in modern) {
            val original = bytes(file)
            open(file).use { database ->
                val rekeyed = saver.changeKey(
                    original, database.key!!, "new-password".toCharArray(), { keyFile.inputStream() }, ::modernYubiKey,
                )
                val before = header(original)
                val after = header(rekeyed.bytes)
                assertArrayEquals(before.cipherId, after.cipherId)
                assertEquals(before.compression, after.compression)
                assertFalse(before.masterSeed.contentEquals(after.masterSeed))
                // The KDF settings stay, apart from a new salt.
                val salt = saltRange(before.kdfParameters)
                assertEquals(salt, saltRange(after.kdfParameters))
                assertEquals(32, salt.last - salt.first + 1)
                for (index in before.kdfParameters.indices) {
                    if (index !in salt) assertEquals(file, before.kdfParameters[index], after.kdfParameters[index])
                }
                assertFalse(before.kdfParameters.sliceArray(salt).contentEquals(after.kdfParameters.sliceArray(salt)))

                // The new credentials open it with a full key derivation, as another KeePass app would.
                reader.open(rekeyed.bytes.inputStream(), "new-password".toCharArray(), keyFile.inputStream(), ::modernYubiKey)
                    .use { reopened ->
                        assertEquals(file, listOf("Sample entry", "Second entry"), reopened.entries.map { it.title })
                        assertEquals("second-password", reopened.entries.last().field("Password").text())
                    }
                val old = assertThrows(KdbxException::class.java) { reader.open(rekeyed.bytes.inputStream(), password()).close() }
                assertEquals(KdbxFailure.CREDENTIALS, old.failure)

                // Apart from when the master key changed, the document is as it was.
                val was = document(original, database.key!!)
                val now = document(rekeyed.bytes, rekeyed.key)
                val changed = now.root.child("Meta")!!.child("MasterKeyChanged")!!
                assertEquals(kdbxTime(1_700_000_000_000L), changed.text)
                // A document without the time gets it added.
                val previous = was.root.child("Meta")!!.child("MasterKeyChanged")
                if (previous != null) changed.text = previous.text else now.root.child("Meta")!!.children.remove(changed)
                assertTrue(file, same(was.root, now.root))
                assertEquals(was.binaries.map { it.toList() }, now.binaries.map { it.toList() })
            }
        }
    }

    @Test
    fun changesToAKeyWithoutAPasswordAndBack() {
        val keyFile = ByteArray(32) { (it + 7).toByte() }
        open("modern-argon2id.kdbx").use { database ->
            val keyOnly = saver.changeKey(bytes("modern-argon2id.kdbx"), database.key!!, null, { keyFile.inputStream() })
            reader.open(keyOnly.bytes.inputStream(), null, keyFile.inputStream()).use { assertEquals(2, it.entries.size) }
            val back = saver.changeKey(keyOnly.bytes, keyOnly.key, password())
            reader.open(back.bytes.inputStream(), password()).use { assertEquals(2, it.entries.size) }
            // The key the file was opened with has to still fit it.
            val stale = assertThrows(KdbxException::class.java) { saver.changeKey(back.bytes, keyOnly.key, password()) }
            assertEquals(KdbxFailure.CREDENTIALS, stale.failure)
            assertThrows(IllegalArgumentException::class.java) { saver.changeKey(back.bytes, back.key, null) }
        }
    }

    /** Where the salt "S" sits in encoded KDF parameters. */
    private fun saltRange(parameters: ByteArray): IntRange {
        val marker = byteArrayOf(0x42, 1, 0, 0, 0, 'S'.code.toByte())
        val start = (0..parameters.size - marker.size).single { at ->
            marker.indices.all { parameters[at + it] == marker[it] }
        } + marker.size
        val length = java.nio.ByteBuffer.wrap(parameters, start, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        return start + 4 until start + 4 + length
    }

    private fun withSubgroup(original: ByteArray, file: String = "modern-argon2id.kdbx"): ByteArray = open(file).use { database ->
        rewrite(original, database.key!!) { document ->
            val top = document.root.child("Root")!!.child("Group")!!
            top.add("Group").apply {
                add("UUID", Base64.getEncoder().encodeToString(ByteArray(16) { 3 }))
                add("Name", "Banking")
            }
        }
    }

    private fun rewrite(bytes: ByteArray, key: KdbxKey, edit: (KdbxDocument) -> Unit): ByteArray {
        val document = document(bytes, key)
        edit(document)
        val output = ByteArrayOutputStream()
        val transformed = key.transformed()
        KdbxWriter(chaCha20 = jvmChaCha).save(output, document.header, transformed, document.binaries, document::write)
        document.clear()
        return output.toByteArray()
    }

    private fun groups(node: XmlNode): List<XmlNode> = node.children.flatMap { child ->
        when (child.name) {
            "Group" -> listOf(child) + groups(child)
            "Root" -> groups(child)
            else -> emptyList()
        }
    }

    private fun fields(title: String, user: String, password: String) = listOf(
        EntryField("Title", title.toByteArray(), false),
        EntryField("UserName", user.toByteArray(), false),
        EntryField("Password", password.toByteArray(), true),
        EntryField("URL", ByteArray(0), false),
        EntryField("Notes", ByteArray(0), false),
    )

    private fun document(bytes: ByteArray, key: KdbxKey): KdbxDocument =
        reader.decryptWithKey(ByteArrayInputStream(bytes), key) { header, payload ->
            KdbxDocument.read(header, payload, jvmChaCha) {}
        }

    private fun entries(node: XmlNode): List<XmlNode> = node.children.flatMap { child ->
        when (child.name) {
            "Entry" -> listOf(child)
            "Group", "Root" -> entries(child)
            else -> emptyList()
        }
    }

    private fun same(a: XmlNode, b: XmlNode): Boolean =
        a.name == b.name && a.attributes == b.attributes && a.text == b.text &&
            (a.secret?.contentEquals(b.secret) ?: (b.secret == null)) &&
            a.children.size == b.children.size && a.children.indices.all { same(a.children[it], b.children[it]) }

    private fun header(bytes: ByteArray): KdbxHeader = KdbxHeader.read(DataInputStream(bytes.inputStream()))

    private fun open(file: String): Database = reader.open(bytes(file).inputStream(), password())

    private fun bytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/kdbx/$name")).use { it.readBytes() }

    private fun password(): CharArray = "fixture-only".toCharArray()

    private fun modernKeyFile() = ByteArray(32) { (it + 0x60).toByte() }.inputStream()

    private fun modernYubiKey(challenge: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(ByteArray(20) { (it + 1).toByte() }, "HmacSHA1"))
        // A variable-length slot calculates HMAC over the original KDF seed.
        doFinal(challenge.copyOf(32))
    }

    private fun DatabaseEntry.field(name: String): DatabaseField = fields.single { it.name == name }
}
