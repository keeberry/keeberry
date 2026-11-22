package app.keeberry

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keeberry.kdbx.KdbxException
import app.keeberry.kdbx.KdbxFailure
import app.keeberry.kdbx.KdbxStage
import app.keeberry.kdbx.KdbxWriter
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The new-database form and the write in progress, which outlive [CreateDatabaseActivity] across
 * configuration changes. Its state is Compose state, read and written on the main thread.
 */
internal class CreateDatabaseViewModel(application: Application) : AndroidViewModel(application) {
    /** A database written and, when the provider allows, kept in the recent list. */
    class Created(val uri: Uri, val name: String)

    val name = TextFieldState()
    val credentials = NewCredentials(onChange = { error = null })
    var error by mutableStateOf<Int?>(null)
    var progress by mutableStateOf<DatabaseViewModel.Progress?>(null)
        private set
    var created by mutableStateOf<Created?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var createJob: Job? = null
    private var hardware: YubiKeyChallenge? = null
    private var keyFileJob: Job? = null

    val isCreating: Boolean get() = createJob != null

    /** Whether a database or a new key file is being written, so the form waits for it. */
    val isBusy: Boolean get() = createJob != null || keyFileJob != null

    /** The name stored in the database, which also suggests its file name. */
    val databaseName: String
        get() = name.text.toString().trim().ifEmpty { getApplication<Application>().getString(R.string.create_database_default_name) }

    /**
     * Writes a new random key to the empty document [uri] that the picker just created, then uses
     * it as the key file. [suggestedName] stands in if the provider can't say what it named it.
     */
    fun createKeyFile(uri: Uri, suggestedName: String) {
        if (isBusy) {
            viewModelScope.launch(Dispatchers.IO) { delete(uri) }
            return
        }
        val app = getApplication<Application>()
        error = null
        keyFileJob = viewModelScope.launch {
            val name = withContext(NonCancellable + Dispatchers.IO) { app.writeNewKeyFile(uri, suggestedName) }
            keyFileJob = null
            if (name != null) credentials.setKeyFile(uri, name) else error = R.string.create_database_key_file_save_error
        }
    }

    /** Checks the form before a location is chosen, and says what is missing. */
    fun validate(): Boolean {
        error = credentials.problem()
        return error == null
    }

    /** Moves a pending YubiKey request to the recreated screen, which NFC discovery needs. */
    fun attach(activity: ComponentActivity) {
        hardware?.attach(activity)
    }

    /** Writes the database to the empty document [uri] that the picker just created. */
    fun create(uri: Uri, pickFlags: Int, activity: ComponentActivity) {
        if (createJob != null) return
        // The form can be empty again if the process was restarted while the picker was open.
        if (keyFileJob != null || !validate()) {
            viewModelScope.launch(Dispatchers.IO) { delete(uri) }
            return
        }
        val title = databaseName
        val pass = credentials.passwordCopy()
        val key = credentials.keyUri
        val slot = credentials.slot
        credentials.clearPasswords()
        lateinit var job: Job
        val hardware = slot?.let {
            YubiKeyChallenge(getApplication(), it, R.string.create_database_creating) { prompt ->
                if (job.isActive && createJob === job) progress = DatabaseViewModel.Progress(prompt, null)
            }
        }
        hardware?.attach(activity)
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                createOnWorker(job, uri, pickFlags, title, pass, key, hardware)
            } finally {
                pass?.fill('\u0000')
                hardware?.close()
            }
        }
        createJob = job
        this.hardware = hardware
        error = null
        progress = DatabaseViewModel.Progress(R.string.create_database_creating, null)
        job.start()
    }

    // The worker ignores coroutine cancellation so it always gets to clean up. The KDF still stops
    // early, since it checks the job itself, and closing the YubiKey request ends any wait for a key.
    private suspend fun createOnWorker(
        job: Job,
        uri: Uri,
        pickFlags: Int,
        title: String,
        pass: CharArray?,
        key: Uri?,
        hardware: YubiKeyChallenge?,
    ) {
        val result = withContext(NonCancellable + kdfDispatcher) {
            try {
                write(job, uri, title, pass, key, hardware)
                Result.success(keep(uri, pickFlags, title))
            } catch (error: Exception) {
                // A half-written or empty file is no use to anyone, including after a cancel.
                delete(uri)
                Result.failure(error)
            } catch (_: OutOfMemoryError) {
                delete(uri)
                Result.failure(KdbxException(KdbxFailure.MEMORY))
            } finally {
                pass?.fill('\u0000')
                hardware?.close()
            }
        }
        withContext(NonCancellable + Dispatchers.Main.immediate) { finish(job, result) }
    }

    private fun write(job: Job, uri: Uri, title: String, pass: CharArray?, key: Uri?, hardware: YubiKeyChallenge?) {
        job.ensureActive()
        val app = getApplication<Application>()
        val writer = KdbxWriter(app.nativeArgon2(), { job.ensureActive() }, onStage = { stage ->
            if (stage is KdbxStage.DerivingKey) {
                val update = DatabaseViewModel.Progress(R.string.database_unlock_deriving, stage.percent)
                main.post { if (job.isActive && createJob === job) progress = update }
            }
        })
        val keyFile = key?.let { { openKeyFile(it) } }
        // The document is new and empty, so plain "w" is enough, and providers that can't
        // truncate accept it.
        val output = app.contentResolver.openOutputStream(uri, "w") ?: throw IOException()
        output.use { writer.create(it, title, pass, keyFile, hardware?.let { request -> request::respond }) }
    }

    private fun openKeyFile(uri: Uri): InputStream = try {
        getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IOException()
    } catch (_: IOException) {
        throw KdbxException(KdbxFailure.KEY_FILE)
    } catch (_: SecurityException) {
        throw KdbxException(KdbxFailure.KEY_FILE)
    }

    private fun keep(uri: Uri, pickFlags: Int, title: String): Created {
        val app = getApplication<Application>()
        // The provider may have changed the suggested file name, for example to avoid a clash.
        val name = app.documentName(uri) ?: "$title.kdbx"
        val recents = RecentDatabases(app)
        if (recents.keepAccess(uri, pickFlags)) recents.add(uri, name)
        return Created(uri, name)
    }

    private fun delete(uri: Uri) = getApplication<Application>().deleteDocument(uri)

    private fun finish(job: Job, result: Result<Created>) {
        val current = createJob === job
        if (current) {
            createJob = null
            hardware = null
            progress = null
        }
        result.fold(
            // A cancel that came too late still leaves a complete database, so say so.
            onSuccess = { created = it },
            onFailure = {
                if (!current) return
                error = errorMessage(it)
            },
        )
    }

    private fun errorMessage(error: Throwable): Int {
        if (error is YubiKeyException) return error.messageId
        return when ((error as? KdbxException)?.failure) {
            KdbxFailure.MEMORY -> R.string.create_database_memory_error
            KdbxFailure.KEY_FILE -> R.string.database_key_file_error
            // The key answered, but not with an HMAC-SHA1 response.
            KdbxFailure.CREDENTIALS -> R.string.database_yubikey_failed
            else -> R.string.create_database_error
        }
    }

    fun cancel() {
        val job = createJob ?: return
        createJob = null
        hardware?.close()
        hardware = null
        progress = null
        job.cancel()
    }

    override fun onCleared() {
        cancel()
        credentials.clearPasswords()
    }
}
