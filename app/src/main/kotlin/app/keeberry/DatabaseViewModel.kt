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
import app.keeberry.kdbx.BinChangedException
import app.keeberry.kdbx.Database
import app.keeberry.kdbx.DatabaseGroup
import app.keeberry.kdbx.EntryField
import app.keeberry.kdbx.EntryMissingException
import app.keeberry.kdbx.GroupMissingException
import app.keeberry.kdbx.KdbxKey
import app.keeberry.kdbx.KdbxSaver
import app.keeberry.kdbx.hexString
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException
import javax.crypto.Cipher
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
    /** The open change-master-key form, if any. */
    var keyChange by mutableStateOf<MasterKeyChange?>(null)
        private set
    /** Whether the master key just changed, which the screen confirms once. */
    var keyChanged by mutableStateOf(false)
    /** Whether that change turned biometric unlock off, which the confirmation then says. */
    var keyChangeEndedDeviceUnlock by mutableStateOf(false)
        private set

    /** Whether the form offers biometric unlock: the device has a strong biometric and the database is a recent one. */
    var deviceUnlockOffered by mutableStateOf(false)
        private set
    /** Whether biometric unlock is on for this database, so the form offers to unlock with it. */
    var deviceUnlockSaved by mutableStateOf(false)
        private set
    /** Whether the form's "Unlock with biometrics" box is ticked: on, or to be turned on by the next unlock. */
    var rememberDevice by mutableStateOf(false)
        private set
    /** A biometric prompt for the screen to show, which it takes with [takePrompt]. */
    var prompt by mutableStateOf<DevicePrompt?>(null)
        private set
    /** Why biometric unlock couldn't be turned on after an unlock, which the screen shows once. */
    var deviceUnlockError by mutableStateOf<Int?>(null)

    /** What a biometric prompt is for. */
    sealed interface DevicePrompt {
        val cipher: Cipher

        /** Turns biometric unlock on with the credentials that just unlocked the database. */
        class Enable(override val cipher: Cipher) : DevicePrompt

        /** Unlocks with what [saved] holds. */
        class Unlock(override val cipher: Cipher, val saved: DeviceUnlock.Saved) : DevicePrompt
    }

    /** The credentials an unlock used, kept until biometric unlock is turned on with them or not. */
    private class Enrollment(val password: CharArray?, val keyFile: Uri?, val keyName: String?, val useYubiKey: Boolean) {
        fun clear() = password?.fill('\u0000')
    }

    /** What the unlock under way is: one with saved biometric credentials, or one that may turn them on. */
    private class Attempt(val withDevice: Boolean, val enrollment: Enrollment?)

    val canEdit: Boolean get() = database?.editable == true && writable

    private val main = Handler(Looper.getMainLooper())
    private var unlockJob: Job? = null
    private var signal: CancellationSignal? = null
    private var hardware: YubiKeyChallenge? = null
    private var databaseUri: Uri? = null
    private var saveJob: Job? = null
    private var attempt: Attempt? = null
    private var enrollment: Enrollment? = null
    private var deviceUnlockPrepared = false
    /** The screen showing now, which a YubiKey asked for after a biometric prompt listens on. */
    private var host: WeakReference<ComponentActivity>? = null

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
        host = WeakReference(activity)
        hardware?.attach(activity)
        keyChange?.hardware?.attach(activity)
    }

    /**
     * Reads whether biometric unlock is on for [databaseUri]. The first time this screen opens, with
     * biometric unlock on, it asks for the biometric right away.
     */
    fun prepareDeviceUnlock(databaseUri: Uri) {
        this.databaseUri = databaseUri
        refreshDeviceUnlock()
        if (deviceUnlockPrepared) return
        deviceUnlockPrepared = true
        rememberDevice = deviceUnlockSaved
        if (deviceUnlockSaved && database == null) startDeviceUnlock()
    }

    /** Picks up biometric unlock being turned off elsewhere, as from Settings. */
    fun refreshDeviceUnlock() {
        val uri = databaseUri ?: return
        val app = getApplication<Application>()
        val store = DeviceUnlock(app)
        deviceUnlockOffered = store.available() && RecentDatabases(app).contains(uri)
        deviceUnlockSaved = deviceUnlockOffered && store.saved(uri) != null
        if (!deviceUnlockOffered) rememberDevice = false
    }

    /** Ticking the box turns biometric unlock on with the next unlock; unticking it turns it off now. */
    fun onRememberDeviceChanged(checked: Boolean) {
        rememberDevice = checked
        val uri = databaseUri
        if (!checked && deviceUnlockSaved && uri != null) {
            DeviceUnlock(getApplication()).forget(uri)
            deviceUnlockSaved = false
        }
    }

    /** Asks for the biometric that unlocks the database with its saved credentials. */
    fun startDeviceUnlock() {
        val uri = databaseUri ?: return
        if (unlockJob != null || database != null || prompt != null) return
        val store = DeviceUnlock(getApplication())
        val saved = store.saved(uri)
        if (saved == null) {
            deviceUnlockSaved = false
            return
        }
        val cipher = try {
            store.decrypter(saved)
        } catch (_: GeneralSecurityException) {
            error = R.string.device_unlock_failed
            return
        }
        if (cipher == null) {
            // A biometric enrolled since then deleted the key.
            deviceUnlockSaved = false
            error = R.string.device_unlock_invalidated
            return
        }
        error = null
        prompt = DevicePrompt.Unlock(cipher, saved)
    }

    /** Hands the waiting prompt to the screen, which shows it. */
    fun takePrompt(): DevicePrompt? = prompt.also { prompt = null }

    /** Acts on how the prompt for [request] ended. */
    fun onPromptResult(request: DevicePrompt, result: PromptResult) {
        when (request) {
            is DevicePrompt.Unlock -> when (result) {
                is PromptResult.Succeeded -> host?.get()?.takeUnless { it.isDestroyed }?.let { unlockWithDevice(request.saved, result.cipher, it) }
                is PromptResult.Failed -> error = result.message
                PromptResult.Cancelled -> {}
            }
            is DevicePrompt.Enable -> {
                val pending = enrollment ?: return
                enrollment = null
                try {
                    when (result) {
                        is PromptResult.Succeeded -> enableDeviceUnlock(pending, result.cipher)
                        is PromptResult.Failed -> deviceUnlockError = result.message
                        // Not now: the box is cleared, so the next unlock doesn't ask again.
                        PromptResult.Cancelled -> rememberDevice = false
                    }
                } finally {
                    pending.clear()
                }
            }
        }
    }

    private fun enableDeviceUnlock(pending: Enrollment, cipher: Cipher) {
        val uri = databaseUri ?: return
        val saved = try {
            DeviceUnlock(getApplication()).save(uri, cipher, pending.password, pending.keyFile, pending.keyName, pending.useYubiKey)
        } catch (_: GeneralSecurityException) {
            deviceUnlockError = R.string.device_unlock_enable_error
            return
        }
        if (saved) deviceUnlockSaved = true else deviceUnlockError = R.string.device_unlock_key_file_error
    }

    private fun unlockWithDevice(saved: DeviceUnlock.Saved, cipher: Cipher, activity: ComponentActivity) {
        val uri = databaseUri ?: return
        if (unlockJob != null || database != null) return
        val pass = try {
            DeviceUnlock(getApplication()).password(saved, cipher)
        } catch (_: GeneralSecurityException) {
            error = R.string.device_unlock_failed
            return
        }
        // The form shows what is used, so a failure such as a missing key file can be put right by hand.
        keyUri = saved.keyFile
        keyName = saved.keyName
        useYubiKey = saved.useYubiKey
        usePassword = saved.password != null
        password.clearText()
        startUnlock(uri, activity, pass, saved.keyFile, saved.useYubiKey, Attempt(withDevice = true, enrollment = null))
    }

    /** Starts unlocking with what the form holds, and clears the typed password. */
    fun unlock(databaseUri: Uri, activity: ComponentActivity) {
        if (unlockJob != null) return
        val key = keyUri
        val withYubiKey = useYubiKey
        val pass = if (showsPassword) password.text.let { text -> CharArray(text.length) { text[it] } } else null
        password.clearText()
        this.databaseUri = databaseUri
        val enroll = deviceUnlockOffered && rememberDevice && !deviceUnlockSaved
        val pending = if (enroll) Enrollment(pass?.copyOf(), key, keyName, withYubiKey) else null
        startUnlock(databaseUri, activity, pass, key, withYubiKey, Attempt(withDevice = false, enrollment = pending))
    }

    /** Unlocks with [pass], which this wipes, the key file [key] and a YubiKey if [withYubiKey]. */
    private fun startUnlock(
        databaseUri: Uri,
        activity: ComponentActivity,
        pass: CharArray?,
        key: Uri?,
        withYubiKey: Boolean,
        attempt: Attempt,
    ) {
        val signal = CancellationSignal()
        lateinit var job: Job
        val hardware = if (!withYubiKey) null else {
            val preferred = RecentDatabases(getApplication()).yubiKey(databaseUri)
            // Until a slot is known to work, both are asked, so either can unlock.
            YubiKeyChallenge(getApplication(), preferred, R.string.database_unlocking, keepAlternate = preferred == null) { prompt ->
                if (job.isActive) progress = Progress(prompt, null)
            }
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
        this.attempt = attempt
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
        hardware: YubiKeyChallenge?,
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
            withContext(NonCancellable + Dispatchers.Main.immediate) { finish(job, result, withYubiKey, hardware?.used) }
        }
    }

    private fun finish(job: Job, result: Result<Database>, withYubiKey: Boolean, yubiKey: YubiKeyChoice?) {
        if (!job.isActive || unlockJob !== job) {
            result.getOrNull()?.close()
            return
        }
        val attempt = attempt
        clearRequest()
        val uri = databaseUri
        result.fold({
            database = it
            writable = uri?.let(::canWrite) == true
            if (uri != null && yubiKey != null) RecentDatabases(getApplication()).rememberYubiKey(uri, yubiKey)
            attempt?.enrollment?.let(::askToEnable)
        }, { failure ->
            attempt?.enrollment?.clear()
            val stale = attempt?.withDevice == true && (failure as? KdbxException)?.failure == KdbxFailure.CREDENTIALS
            if (stale && uri != null) {
                // The password was changed elsewhere since biometric unlock was turned on.
                DeviceUnlock(getApplication()).forget(uri)
                deviceUnlockSaved = false
                error = R.string.device_unlock_stale
            } else error = errorMessage(failure, withYubiKey)
        })
    }

    /** Asks for the biometric that turns biometric unlock on with the credentials in [pending]. */
    private fun askToEnable(pending: Enrollment) {
        val uri = databaseUri
        val cipher = try {
            uri?.let { DeviceUnlock(getApplication()).encrypter(it) }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalStateException) {
            // Android 9 with no fingerprint enrolled.
            null
        }
        if (cipher == null) {
            pending.clear()
            deviceUnlockError = R.string.device_unlock_enable_error
            return
        }
        enrollment?.clear()
        enrollment = pending
        prompt = DevicePrompt.Enable(cipher)
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

    /** Opens the form that changes the master key, if nothing else is changing the database. */
    fun startKeyChange() {
        if (!canEdit || editor != null || saveJob != null || keyChange != null) return
        keyChange = MasterKeyChange()
    }

    /** Closes the change-master-key form, unless a change is under way. */
    fun discardKeyChange() {
        val form = keyChange ?: return
        if (form.busy) return
        form.credentials.clearPasswords()
        keyChange = null
    }

    /**
     * Writes a new random key file to the empty document [uri] that the picker just created, for the
     * change-master-key form. [suggestedName] stands in if the provider can't say what it named it.
     */
    fun createKeyFile(uri: Uri, suggestedName: String) {
        val app = getApplication<Application>()
        val form = keyChange
        if (form == null || form.busy || form.savingKeyFile) {
            viewModelScope.launch(Dispatchers.IO) { app.deleteDocument(uri) }
            return
        }
        form.savingKeyFile = true
        form.error = null
        viewModelScope.launch {
            val name = withContext(NonCancellable + Dispatchers.IO) { app.writeNewKeyFile(uri, suggestedName) }
            form.savingKeyFile = false
            if (name != null) form.credentials.setKeyFile(uri, name) else form.error = R.string.create_database_key_file_save_error
        }
    }

    /**
     * Encrypts the database under the credentials the form holds, then replaces the file the way a
     * save does: it reads the file again, checks the result and keeps the original if writing fails.
     * Until the key derivation is done, cancelling or locking stops it with the file untouched; after
     * that, it runs to the end.
     */
    fun changeMasterKey(activity: ComponentActivity) {
        val form = keyChange ?: return
        val database = database ?: return
        val uri = databaseUri ?: return
        if (form.busy || form.savingKeyFile || saveJob != null) return
        form.error = form.credentials.problem()
        if (form.error != null) return
        val key = database.key?.copy() ?: return
        val pass = form.credentials.passwordCopy()
        val keyFile = form.credentials.keyUri
        val withYubiKey = form.credentials.useYubiKey
        form.credentials.clearPasswords()
        lateinit var job: Job
        val hardware = if (!withYubiKey) null else {
            val preferred = RecentDatabases(getApplication()).yubiKey(uri)
            YubiKeyChallenge(getApplication(), preferred, R.string.master_key_changing) { prompt ->
                if (form.job === job) form.progress = Progress(prompt, null)
            }
        }
        hardware?.attach(activity)
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = withContext(NonCancellable + kdfDispatcher) {
                try {
                    Result.success(changeKeyOnWorker(job, form, uri, key, pass, keyFile, hardware))
                } catch (error: Exception) {
                    Result.failure(error)
                } catch (_: OutOfMemoryError) {
                    Result.failure(KdbxException(KdbxFailure.MEMORY))
                } finally {
                    pass?.fill('\u0000')
                    key.close()
                    hardware?.close()
                }
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                finishKeyChange(database, form, job, result, uri, hardware?.used)
            }
        }
        form.job = job
        form.hardware = hardware
        form.cancelled = false
        form.progress = Progress(R.string.master_key_changing, null)
        saveJob = job
        job.start()
    }

    private fun changeKeyOnWorker(
        job: Job,
        form: MasterKeyChange,
        uri: Uri,
        key: KdbxKey,
        pass: CharArray?,
        keyFile: Uri?,
        hardware: YubiKeyChallenge?,
    ): Database {
        val app = getApplication<Application>()
        val saver = KdbxSaver(
            checkCancelled = { job.ensureActive() },
            argon2 = app.nativeArgon2(),
            aesKdf = NativeAesKdf(),
            onStage = { stage ->
                if (stage is KdbxStage.DerivingKey) {
                    val update = Progress(R.string.database_unlock_deriving, stage.percent)
                    main.post { if (form.job === job) form.progress = update }
                }
            },
        )
        val current = readFile(uri)
        val rekeyed = saver.changeKey(current, key, pass, keyFile?.let { { openKeyFile(it) } }, hardware?.let { it::respond })
        // From here nothing checks for a cancel, so the change runs to the end.
        val check = KdbxSaver()
        try {
            val reopened = check.open(rekeyed.bytes, rekeyed.key)
            try {
                val same = check.open(current, key).use { before ->
                    before.name == reopened.name &&
                        before.entries.map { it.key } == reopened.entries.map { it.key } &&
                        before.binned.map { it.key } == reopened.binned.map { it.key } &&
                        before.groups.map { it.key } == reopened.groups.map { it.key }
                }
                if (!same) throw KdbxException(KdbxFailure.CORRUPT)
                replaceFile(uri, rekeyed.bytes, current)
                return reopened
            } catch (error: Throwable) {
                reopened.close()
                throw error
            }
        } finally {
            rekeyed.key.close()
        }
    }

    private fun finishKeyChange(
        previous: Database,
        form: MasterKeyChange,
        job: Job,
        result: Result<Database>,
        uri: Uri,
        yubiKey: YubiKeyChoice?,
    ) {
        if (saveJob === job) saveJob = null
        form.job = null
        form.hardware = null
        form.progress = null
        val changed = result.getOrNull()
        // The file has the new key even if the database was locked meanwhile.
        if (changed != null && yubiKey != null) RecentDatabases(getApplication()).rememberYubiKey(uri, yubiKey)
        // The saved password is the old one now. The box stays ticked, so the next unlock offers it again.
        val endedDeviceUnlock = changed != null && DeviceUnlock(getApplication()).saved(uri) != null
        if (endedDeviceUnlock) {
            DeviceUnlock(getApplication()).forget(uri)
            deviceUnlockSaved = false
        }
        // Locked while changing: if the file changed, nothing is shown any more.
        if (database !== previous) {
            changed?.close()
            return
        }
        if (changed == null) {
            if (!form.cancelled) form.error = keyChangeErrorMessage(result.exceptionOrNull())
            return
        }
        previous.close()
        database = changed
        if (keyChange === form) keyChange = null
        keyChanged = true
        keyChangeEndedDeviceUnlock = endedDeviceUnlock
    }

    private fun keyChangeErrorMessage(error: Throwable?): Int = when {
        error is YubiKeyException -> error.messageId
        error is KdbxException && error.failure == KdbxFailure.KEY_FILE -> R.string.database_key_file_error
        error is KdbxException && error.failure == KdbxFailure.MEMORY -> R.string.create_database_memory_error
        else -> saveErrorMessage(error, R.string.entry_save_error)
    }

    /** Stops the change under way while it is still deriving the new key, or waiting for a YubiKey. */
    fun cancelKeyChange() {
        val form = keyChange ?: return
        val job = form.job ?: return
        form.cancelled = true
        job.cancel()
        form.hardware?.close()
    }

    private fun openKeyFile(uri: Uri): InputStream = try {
        getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IOException()
    } catch (_: IOException) {
        throw KdbxException(KdbxFailure.KEY_FILE)
    } catch (_: SecurityException) {
        throw KdbxException(KdbxFailure.KEY_FILE)
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
        val entries = if (fromBin) database.binned else database.entries
        val uuid = entries.getOrNull(position)?.uuid?.copyOf() ?: return
        changeFile(missing, { current, key -> change(current, key, uuid) }) { changed(it, uuid) }
    }

    /**
     * Deletes everything in the recycle bin for good. If the bin holds entries the user hasn't seen,
     * added elsewhere since the unlock, the file is left as it was.
     */
    fun emptyBin() {
        val database = database ?: return
        if (database.binned.isEmpty()) return
        val expected = database.binned.map { it.uuid?.hexString().orEmpty() }
        changeFile(R.string.recycle_bin_changed_error, { current, key -> emptyBin(current, key, expected) }) { reopened ->
            reopened.binned.isEmpty() && reopened.entries.size == database.entries.size
        }
    }

    /**
     * Like a save, reads the file again, applies [change], checks the result with [changed] and then
     * replaces the file. [missing] explains a change whose subject is no longer as it was shown.
     */
    private fun changeFile(
        missing: Int,
        change: KdbxSaver.(ByteArray, KdbxKey) -> ByteArray,
        changed: (Database) -> Boolean,
    ) {
        val database = database ?: return
        val uri = databaseUri ?: return
        if (!canEdit || editor != null || saveJob != null) return
        val key = database.key?.copy() ?: return
        changing = true
        changeError = null
        saveJob = viewModelScope.launch {
            val result = onWorker {
                try {
                    changeOnWorker(uri, key, change, changed)
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
        change: KdbxSaver.(ByteArray, KdbxKey) -> ByteArray,
        changed: (Database) -> Boolean,
    ): Database {
        val saver = KdbxSaver()
        val current = readFile(uri)
        val bytes = saver.change(current, key)
        val reopened = saver.open(bytes, key)
        try {
            if (!changed(reopened)) throw KdbxException(KdbxFailure.CORRUPT)
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
        is EntryMissingException, is BinChangedException -> missing
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
        attempt?.enrollment?.clear()
        job.cancel()
        signal?.cancel()
        hardware?.close()
        clearRequest()
    }

    fun lock() {
        cancel()
        keyChange?.let {
            cancelKeyChange()
            it.credentials.clearPasswords()
        }
        keyChange = null
        keyChanged = false
        keyChangeEndedDeviceUnlock = false
        enrollment?.clear()
        enrollment = null
        prompt = null
        deviceUnlockError = null
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
        attempt = null
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
        hardware: YubiKeyChallenge?,
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
        val database = { openDocument(databaseUri, signal) }
        return try {
            decoder.unlock(database, pass, keyFile, hardware?.let { it::respond })
        } catch (error: KdbxException) {
            // With both slots set up for challenge-response, the one asked first may hold another secret.
            if (error.failure != KdbxFailure.CREDENTIALS || hardware?.hasAlternate != true) throw error
            decoder.unlock(database, pass, keyFile) { challenge -> hardware.alternate(challenge) ?: throw error }
        }
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
