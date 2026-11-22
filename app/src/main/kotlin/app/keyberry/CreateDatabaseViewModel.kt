package app.keyberry

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keyberry.kdbx.KdbxException
import app.keyberry.kdbx.KdbxFailure
import app.keyberry.kdbx.KdbxStage
import app.keyberry.kdbx.KdbxWriter
import java.io.IOException
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
    val password = TextFieldState()
    val confirmation = TextFieldState()
    var error by mutableStateOf<Int?>(null)
    var progress by mutableStateOf<DatabaseViewModel.Progress?>(null)
        private set
    var created by mutableStateOf<Created?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var createJob: Job? = null

    val isCreating: Boolean get() = createJob != null

    /** The name stored in the database, which also suggests its file name. */
    val databaseName: String
        get() = name.text.toString().trim().ifEmpty { getApplication<Application>().getString(R.string.create_database_default_name) }

    /** Checks the form before a location is chosen, and says what is missing. */
    fun validate(): Boolean {
        error = when {
            password.text.isEmpty() -> R.string.create_database_password_required
            !password.text.contentEquals(confirmation.text) -> R.string.create_database_password_mismatch
            else -> null
        }
        return error == null
    }

    /** Writes the database to the empty document [uri] that the picker just created. */
    fun create(uri: Uri, pickFlags: Int) {
        if (createJob != null) return
        // The form can be empty again if the process was restarted while the picker was open.
        if (!validate()) {
            viewModelScope.launch(Dispatchers.IO) { delete(uri) }
            return
        }
        val title = databaseName
        val pass = password.text.let { text -> CharArray(text.length) { text[it] } }
        clearPasswords()
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                createOnWorker(job, uri, pickFlags, title, pass)
            } finally {
                pass.fill('\u0000')
            }
        }
        createJob = job
        error = null
        progress = DatabaseViewModel.Progress(R.string.create_database_creating, null)
        job.start()
    }

    // The worker ignores coroutine cancellation so it always gets to clean up. The KDF still stops
    // early, since it checks the job itself.
    private suspend fun createOnWorker(job: Job, uri: Uri, pickFlags: Int, title: String, pass: CharArray) {
        val result = withContext(NonCancellable + kdfDispatcher) {
            try {
                write(job, uri, title, pass)
                Result.success(keep(uri, pickFlags, title))
            } catch (error: Exception) {
                // A half-written or empty file is no use to anyone, including after a cancel.
                delete(uri)
                Result.failure(error)
            } catch (_: OutOfMemoryError) {
                delete(uri)
                Result.failure(KdbxException(KdbxFailure.MEMORY))
            } finally {
                pass.fill('\u0000')
            }
        }
        withContext(NonCancellable + Dispatchers.Main.immediate) { finish(job, result) }
    }

    private fun write(job: Job, uri: Uri, title: String, pass: CharArray) {
        job.ensureActive()
        val app = getApplication<Application>()
        val writer = KdbxWriter(app.nativeArgon2(), { job.ensureActive() }, onStage = { stage ->
            if (stage is KdbxStage.DerivingKey) {
                val update = DatabaseViewModel.Progress(R.string.database_unlock_deriving, stage.percent)
                main.post { if (job.isActive && createJob === job) progress = update }
            }
        })
        // The document is new and empty, so plain "w" is enough, and providers that can't
        // truncate accept it.
        val output = app.contentResolver.openOutputStream(uri, "w") ?: throw IOException()
        output.use { writer.create(it, title, pass) }
    }

    private fun keep(uri: Uri, pickFlags: Int, title: String): Created {
        val app = getApplication<Application>()
        // The provider may have changed the suggested file name, for example to avoid a clash.
        val name = app.documentName(uri) ?: "$title.kdbx"
        val recents = RecentDatabases(app)
        if (recents.keepAccess(uri, pickFlags)) recents.add(uri, name)
        return Created(uri, name)
    }

    private fun delete(uri: Uri) {
        // Each failure means the document is already gone or the provider can't delete it, and
        // either way nothing more can be done.
        try {
            DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri)
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: UnsupportedOperationException) {
        }
    }

    private fun finish(job: Job, result: Result<Created>) {
        val current = createJob === job
        if (current) {
            createJob = null
            progress = null
        }
        result.fold(
            // A cancel that came too late still leaves a complete database, so say so.
            onSuccess = { created = it },
            onFailure = {
                if (!current) return
                error = if ((it as? KdbxException)?.failure == KdbxFailure.MEMORY) {
                    R.string.create_database_memory_error
                } else R.string.create_database_error
            },
        )
    }

    fun cancel() {
        val job = createJob ?: return
        createJob = null
        progress = null
        job.cancel()
    }

    fun clearPasswords() {
        password.clearText()
        confirmation.clearText()
    }

    override fun onCleared() {
        cancel()
        clearPasswords()
    }
}
