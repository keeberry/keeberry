package app.keeberry

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keeberry.kdbx.KdbxException
import app.keeberry.kdbx.KdbxFailure
import app.keeberry.kdbx.KdbxReader
import app.keeberry.kdbx.KdbxStage
import app.keeberry.kdbx.NativeAesKdf
import app.keeberry.kdbx.NativeArgon2
import app.keeberry.kdbx.Database
import app.keeberry.kdbx.DatabaseGroup
import app.keeberry.kdbx.EntryField
import app.keeberry.kdbx.EntryMissingException
import app.keeberry.kdbx.GroupMissingException
import app.keeberry.kdbx.KdbxKey
import app.keeberry.kdbx.KdbxSaver
import app.keeberry.kdbx.hexString
import com.yubico.yubikit.yubiotp.Slot
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The unlock form, the open database and any unlock in progress. It outlives [DatabaseActivity] across
 * configuration changes. Its state is Compose state, read and written on the main thread.
 */
internal class DatabaseViewModel(application: Application) : AndroidViewModel(application) {
    /** What an unlock is doing, with how far it got when [percent] is known. */
    data class Progress(val status: Int, val percent: Int?)

    val password = TextFieldState()
    var usePassword by mutableStateOf(false)
        private set
    var useYubiKey by mutableStateOf(false)
        private set
    var slotOne by mutableStateOf(false)
    var keyUri by mutableStateOf<Uri?>(null)
        private set
    var keyName by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<Int?>(null)
    var database by mutableStateOf<Database?>(null)
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    val query = TextFieldState()
    /** Whether the file can be written, which an editable database also needs to be changed. */
    var writable by mutableStateOf(false)
        private set
    /** The open entry form, if any. */
    var editor by mutableStateOf<EntryEditor?>(null)
        private set
    /** The entry a save just stored, which the screen opens once and then clears. */
    var savedEntry by mutableStateOf<String?>(null)
    /** Whether the recycle bin is shown in place of the entries. */
    var showingBin by mutableStateOf(false)
    /** Whether an entry is being deleted or restored, and why the last attempt failed. */
    var changing by mutableStateOf(false)
        private set
    var changeError by mutableStateOf<Int?>(null)

    val canEdit: Boolean get() = database?.editable == true && writable

    private val main = Handler(Looper.getMainLooper())
    private var unlockJob: Job? = null
    private var signal: CancellationSignal? = null
    private var hardware: YubiKeyUnlock? = null
    private var databaseUri: Uri? = null
    private var saveJob: Job? = null

    val isUnlocking: Boolean get() = unlockJob != null

    /** Whether the form shows "Use master password", which only matters next to another credential. */
    val hasOtherCredential: Boolean get() = keyUri != null || useYubiKey

    val showsPassword: Boolean get() = !hasOtherCredential || usePassword

    fun onUsePasswordChanged(checked: Boolean) {
        usePassword = checked
        if (!checked && hasOtherCredential) password.clearText()
    }

    fun onUseYubiKeyChanged(checked: Boolean) {
        useYubiKey = checked
        // A YubiKey is usually paired with a password, so offer the field by default.
        if (checked && keyUri == null) usePassword = true
        error = null
    }

    fun setKeyFile(uri: Uri, name: String?) {
        // Leave the field out when nothing was typed: a key file alone is the common case.
        if (keyUri == null && !useYubiKey) usePassword = password.text.isNotEmpty()
        keyUri = uri
        keyName = name
        error = null
    }

    fun removeKeyFile() {
        keyUri = null
        keyName = null
        error = null
    }

    /** Moves a pending YubiKey request to the recreated screen, which NFC discovery needs. */
    fun attach(activity: ComponentActivity) {
        hardware?.attach(activity)
    }

    /** Starts unlocking with what the form holds, and clears the typed password. */
    fun unlock(databaseUri: Uri, activity: ComponentActivity) {
        if (unlockJob != null) return
        val key = keyUri
        val withYubiKey = useYubiKey
        val pass = if (showsPassword) password.text.let { text -> CharArray(text.length) { text[it] } } else null
        password.clearText()
        val slot = if (!withYubiKey) null else if (slotOne) Slot.ONE else Slot.TWO
        val signal = CancellationSignal()
        lateinit var job: Job
        val hardware = slot?.let {
            YubiKeyUnlock(getApplication(), it) { prompt -> if (job.isActive) progress = Progress(prompt, null) }
        }
        hardware?.attach(activity)
        // One unlock runs at a time across screens, so a new one waits for a cancelled one to stop
        // instead of allocating a second large KDF at once.
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                unlockOnWorker(job, databaseUri, pass, key, signal, hardware, withYubiKey)
            } finally {
                // Also covers a cancel while queued, before the worker block ran at all.
                pass?.fill('\u0000')
            }
        }
        unlockJob = job
        this.databaseUri = databaseUri
        this.signal = signal
        this.hardware = hardware
        error = null
        progress = Progress(R.string.database_unlock_opening, null)
        job.start()
    }

    private suspend fun unlockOnWorker(
        job: Job,
        databaseUri: Uri,
        pass: CharArray?,
        key: Uri?,
        signal: CancellationSignal,
        hardware: YubiKeyUnlock?,
        withYubiKey: Boolean,
    ) {
        withContext(kdfDispatcher) {
            val result = try {
                Result.success(readDatabase(job, databaseUri, pass, key, signal, hardware))
            } catch (error: Exception) {
                Result.failure(error)
            } catch (_: OutOfMemoryError) {
                // A large KDF or database can exhaust the heap. What it allocated is unreachable now,
                // so report it as over the limits rather than letting it end the process.
                Result.failure(KdbxException(KdbxFailure.LIMIT))
            } finally {
                pass?.fill('\u0000')
                hardware?.close()
            }
            // Runs even after a cancel, so a database that arrives too late is still closed.
            withContext(NonCancellable + Dispatchers.Main.immediate) { finish(job, result, withYubiKey) }
        }
    }

    private fun finish(job: Job, result: Result<Database>, withYubiKey: Boolean) {
        if (!job.isActive || unlockJob !== job) {
            result.getOrNull()?.close()
            return
        }
        clearRequest()
        result.fold({
            database = it
            writable = databaseUri?.let(::canWrite) == true
        }, { error = errorMessage(it, withYubiKey) })
    }

    /**
     * Whether the picker granted write access, now or kept from an earlier pick. A document from a
     * provider of KeeBerry's own, as in debug builds, needs no grant.
     */
    private fun canWrite(uri: Uri): Boolean {
        val app = getApplication<Application>()
        val granted = app.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (granted == PackageManager.PERMISSION_GRANTED) return true
        if (app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) return true
        val authority = uri.authority ?: return false
        return app.packageManager.resolveContentProvider(authority, 0)?.packageName == app.packageName
    }

    /** Opens the form for a new entry, or for changing [position] in the list. */
    fun startEditing(position: Int?) {
        val database = database ?: return
        if (!canEdit || editor != null) return
        val entry = position?.let { database.entries.getOrNull(it) ?: return }
        if (entry != null && entry.uuid == null) return
        editor = EntryEditor(entry, database.groups)
    }

    fun discardEditing() {
        val form = editor ?: return
        if (form.saving) return
        form.clear()
        editor = null
    }

    /**
     * Saves the form to the file. The file is read again first, so changes another app made since the
     * unlock are kept, and the result is checked before it replaces the file.
     */
    fun saveEditing() {
        val form = editor ?: return
        val database = database ?: return
        val uri = databaseUri ?: return
        if (form.saving || saveJob != null) return
        form.error = form.problem()
        if (form.error != null) return
        val key = database.key?.copy() ?: return
        val fields = form.fields()
        val group = if (form.isNew) form.group else null
        form.saving = true
        // The save always runs to the end, so a lock or a closed screen can't leave the file half written.
        saveJob = viewModelScope.launch {
            val result = onWorker {
                try {
                    saveOnWorker(uri, key, form.uuid, fields, group)
                } finally {
                    fields.forEach { it.value.fill(0) }
                    key.close()
                }
            }
            saveJob = null
            finishSave(database, form, result)
        }
    }

    /** Runs [work] on a worker to the end, whatever happens to the screen, and returns how it went. */
    private suspend fun <T> onWorker(work: () -> T): Result<T> = withContext(NonCancellable + Dispatchers.IO) {
        try {
            Result.success(work())
        } catch (error: Exception) {
            Result.failure(error)
        } catch (_: OutOfMemoryError) {
            Result.failure(KdbxException(KdbxFailure.LIMIT))
        }
    }

    private class Saved(val database: Database, val uuid: ByteArray)

    private fun saveOnWorker(
        uri: Uri,
        key: KdbxKey,
        uuid: ByteArray?,
        fields: List<EntryField>,
        group: DatabaseGroup?,
    ): Saved {
        val saver = KdbxSaver()
        val current = readFile(uri)
        val saved = saver.save(current, key, uuid, fields, group?.uuid)
        val reopened = saver.open(saved.bytes, key)
        try {
            val entry = reopened.entries.firstOrNull { it.uuid.contentEquals(saved.uuid) }
            val stored = entry != null && entry.fields.size == fields.size && fields.all { field ->
                entry.fields.any {
                    it.name == field.name && it.protected == field.protected && it.text() == field.value.toString(Charsets.UTF_8)
                }
            } && (group == null || entry.group == group.path)
            if (!stored) throw KdbxException(KdbxFailure.CORRUPT)
            replaceFile(uri, saved.bytes, current)
            return Saved(reopened, saved.uuid)
        } catch (error: Throwable) {
            reopened.close()
            throw error
        }
    }

    /**
     * Deletes the entry at [position] in the list, into the recycle bin when the database uses one, or
     * the one at [position] in the recycle bin when [fromBin], for good. Like a save, it reads the file
     * again, checks the result and then replaces the file.
     */
    fun deleteEntry(position: Int, fromBin: Boolean = false) =
        changeEntry(position, fromBin, R.string.entry_save_missing_error, KdbxSaver::delete) { reopened, uuid ->
            reopened.entries.none { it.uuid.contentEquals(uuid) } && (!fromBin || reopened.binned.none { it.uuid.contentEquals(uuid) })
        }

    /** Moves the entry at [position] in the recycle bin back to its group, checked like a delete. */
    fun restoreEntry(position: Int) =
        changeEntry(position, fromBin = true, R.string.entry_restore_missing_error, KdbxSaver::restore) { reopened, uuid ->
            reopened.entries.any { it.uuid.contentEquals(uuid) } && reopened.binned.none { it.uuid.contentEquals(uuid) }
        }

    /**
     * Applies [change] to the entry at [position], in the recycle bin when [fromBin], and replaces the
     * file once [changed] confirms the result. [missing] explains an entry that is no longer there.
     */
    private fun changeEntry(
        position: Int,
        fromBin: Boolean,
        missing: Int,
        change: KdbxSaver.(ByteArray, KdbxKey, ByteArray) -> ByteArray,
        changed: (Database, ByteArray) -> Boolean,
    ) {
        val database = database ?: return
        val uri = databaseUri ?: return
        if (!canEdit || editor != null || saveJob != null) return
        val entries = if (fromBin) database.binned else database.entries
        val uuid = entries.getOrNull(position)?.uuid?.copyOf() ?: return
        val key = database.key?.copy() ?: return
        changing = true
        changeError = null
        saveJob = viewModelScope.launch {
            val result = onWorker {
                try {
                    changeOnWorker(uri, key, uuid, change, changed)
                } finally {
                    key.close()
                }
            }
            saveJob = null
            finishChange(database, result, missing)
        }
    }

    private fun changeOnWorker(
        uri: Uri,
        key: KdbxKey,
        uuid: ByteArray,
        change: KdbxSaver.(ByteArray, KdbxKey, ByteArray) -> ByteArray,
        changed: (Database, ByteArray) -> Boolean,
    ): Database {
        val saver = KdbxSaver()
        val current = readFile(uri)
        val bytes = saver.change(current, key, uuid)
        val reopened = saver.open(bytes, key)
        try {
            if (!changed(reopened, uuid)) throw KdbxException(KdbxFailure.CORRUPT)
            replaceFile(uri, bytes, current)
            return reopened
        } catch (error: Throwable) {
            reopened.close()
            throw error
        }
    }

    private fun finishChange(previous: Database, result: Result<Database>, missing: Int) {
        changing = false
        val changed = result.getOrNull()
        // Locked while saving: the file is saved, but nothing is shown any more.
        if (database !== previous) {
            changed?.close()
            return
        }
        if (changed == null) {
            changeError = saveErrorMessage(result.exceptionOrNull(), missing)
            return
        }
        previous.close()
        database = changed
    }

    /** Writes [bytes] over the file and reads it back. If that fails, puts [original] back. */
    private fun replaceFile(uri: Uri, bytes: ByteArray, original: ByteArray) {
        var touched = false
        try {
            writeFile(uri, bytes) { touched = true }
            if (!readFile(uri).contentEquals(bytes)) throw IOException()
        } catch (error: Exception) {
            if (!touched) throw error
            try {
                writeFile(uri, original) {}
            } catch (_: Exception) {
                throw SaveException(R.string.entry_save_damaged_error)
            }
            throw error
        }
    }

    private fun writeFile(uri: Uri, bytes: ByteArray, onOpened: () -> Unit) {
        val resolver = getApplication<Application>().contentResolver
        // Not every provider supports truncating. The read back catches one that leaves old bytes.
        val output: OutputStream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } catch (_: UnsupportedOperationException) {
            resolver.openOutputStream(uri, "w")
        } ?: throw FileNotFoundException()
        onOpened()
        output.use { it.write(bytes) }
    }

    private fun readFile(uri: Uri): ByteArray {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
        return input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_FILE_BYTES) throw KdbxException(KdbxFailure.LIMIT)
            }
            output.toByteArray()
        }
    }

    private fun finishSave(previous: Database, form: EntryEditor, result: Result<Saved>) {
        val saved = result.getOrNull()
        // Locked while saving: the file is saved, but nothing is shown any more.
        if (database !== previous || editor !== form) {
            saved?.database?.close()
            return
        }
        if (saved == null) {
            form.saving = false
            form.error = saveErrorMessage(result.exceptionOrNull())
            return
        }
        previous.close()
        database = saved.database
        form.clear()
        editor = null
        savedEntry = saved.uuid.hexString()
    }

    private fun saveErrorMessage(error: Throwable?, missing: Int = R.string.entry_save_missing_error): Int = when (error) {
        is SaveException -> error.messageId
        is SecurityException -> R.string.entry_save_permission_error
        is EntryMissingException -> missing
        is GroupMissingException -> R.string.entry_save_group_error
        is KdbxException -> when (error.failure) {
            KdbxFailure.CREDENTIALS -> R.string.entry_save_credentials_error
            KdbxFailure.LIMIT, KdbxFailure.MEMORY -> R.string.entry_save_limit_error
            else -> R.string.entry_save_error
        }
        else -> R.string.entry_save_error
    }

    private class SaveException(val messageId: Int) : IOException()

    // The worker sees the cancelled job within a quarter Argon2 pass or one AES-KDF slice, and
    // closing the YubiKey request ends any wait for a key.
    fun cancel() {
        val job = unlockJob ?: return
        job.cancel()
        signal?.cancel()
        hardware?.close()
        clearRequest()
    }

    fun lock() {
        cancel()
        editor?.clear()
        editor = null
        savedEntry = null
        showingBin = false
        changeError = null
        database?.close()
        database = null
        query.clearText()
        password.clearText()
    }

    override fun onCleared() = lock()

    private fun clearRequest() {
        unlockJob = null
        signal = null
        hardware = null
        progress = null
    }

    private fun readDatabase(
        job: Job,
        databaseUri: Uri,
        pass: CharArray?,
        key: Uri?,
        signal: CancellationSignal,
        hardware: YubiKeyUnlock?,
    ): Database {
        val argon2 = getApplication<Application>().nativeArgon2()
        val decoder = KdbxReader(argon2, { job.ensureActive() }, aesKdf = NativeAesKdf()) { stage ->
            val update = when (stage) {
                is KdbxStage.DerivingKey -> Progress(R.string.database_unlock_deriving, stage.percent)
                KdbxStage.Decrypting -> Progress(R.string.database_unlock_decrypting, null)
            }
            main.post { if (job.isActive && unlockJob === job) progress = update }
        }
        val keyFile = key?.let {
            {
                try {
                    openDocument(it, signal)
                } catch (_: IOException) {
                    throw KdbxException(KdbxFailure.KEY_FILE)
                } catch (_: SecurityException) {
                    throw KdbxException(KdbxFailure.KEY_FILE)
                }
            }
        }
        return decoder.unlock({ openDocument(databaseUri, signal) }, pass, keyFile, hardware?.let { it::respond })
    }

    private fun openDocument(uri: Uri, signal: CancellationSignal): InputStream {
        val descriptor = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r", signal)
            ?: throw IOException()
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    private fun errorMessage(error: Throwable, withYubiKey: Boolean): Int {
        if (error is YubiKeyException) return error.messageId
        return when ((error as? KdbxException)?.failure) {
            KdbxFailure.FORMAT -> R.string.file_not_kdbx
            KdbxFailure.UNSUPPORTED -> R.string.database_unsupported_error
            KdbxFailure.CORRUPT -> R.string.database_corrupt_error
            KdbxFailure.CREDENTIALS -> if (withYubiKey) {
                R.string.database_credentials_yubikey_error
            } else R.string.database_credentials_error
            KdbxFailure.KEY_FILE -> R.string.database_key_file_error
            KdbxFailure.LIMIT -> R.string.database_limit_error
            KdbxFailure.MEMORY -> R.string.database_memory_error
            null -> R.string.file_unreadable
        }
    }
}

/**
 * Runs key derivation for unlocking and creating databases. One runs at a time across screens, so a
 * new one waits for a cancelled one to stop instead of allocating a second large KDF at once.
 */
internal val kdfDispatcher = Dispatchers.IO.limitedParallelism(1)

/** The reader's limit, so a save can read back any file that unlocked. */
private const val MAX_FILE_BYTES = 256L * 1024 * 1024

/**
 * Native Argon2 memory sits outside the Java heap, so its budget comes from device RAM: 1 GiB on
 * phones with 8 GB or more, and proportionally less on smaller ones. Each run also checks the memory
 * the system can hand out before it starts treating the device as low on memory.
 */
internal fun Context.nativeArgon2(): NativeArgon2 {
    val activities = getSystemService(ActivityManager::class.java)
    fun memoryInfo() = ActivityManager.MemoryInfo().also(activities::getMemoryInfo)
    val budget = minOf(memoryInfo().totalMem / 8, 1L shl 30)
    return NativeArgon2(budget, freeMemory = { memoryInfo().let { maxOf(0, it.availMem - it.threshold) } })
}
