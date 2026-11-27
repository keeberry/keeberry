package app.keeberry

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keeberry.kdbx.KdbxHeader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The start screen: checks that a picked file is a database before opening it, and keeps the recent
 * databases. A check in progress outlives a configuration change.
 */
internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    /** A checked database, ready for [DatabaseActivity]. */
    class OpenRequest(val uri: Uri, val name: String?)

    private val recents = RecentDatabases(application)

    var recentDatabases by mutableStateOf(recents.list())
        private set
    var checking by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)
    /** A recent database that couldn't be read, for example because it was deleted, until dismissed. */
    var unavailable by mutableStateOf<RecentDatabases.Recent?>(null)
    var openRequest by mutableStateOf<OpenRequest?>(null)
        private set
    /** The file name of a database just created, until its confirmation is dismissed. */
    var created by mutableStateOf<String?>(null)

    fun refreshRecents() {
        recentDatabases = recents.list()
    }

    /** A document from the picker, whose access is kept for the recent list when the provider allows. */
    fun onPicked(uri: Uri, pickFlags: Int) = check(uri, recents.keepAccess(uri, pickFlags))

    fun onRecentChosen(recent: RecentDatabases.Recent) = check(recent.uri, kept = true, recent)

    fun removeRecent(recent: RecentDatabases.Recent) {
        if (unavailable?.uri == recent.uri) unavailable = null
        recents.remove(recent.uri)
        refreshRecents()
    }

    /** A database [CreateDatabaseActivity] wrote, which it already added to the recent list. */
    fun onCreated(name: String) {
        refreshRecents()
        created = name
    }

    fun onOpened() {
        openRequest = null
    }

    private fun check(uri: Uri, kept: Boolean, recent: RecentDatabases.Recent? = null) {
        if (checking) return
        checking = true
        viewModelScope.launch {
            val (error, displayName) = withContext(Dispatchers.IO) {
                val error = inspect(uri)
                error to if (error == null) getApplication<Application>().documentName(uri) else null
            }
            checking = false
            if (error != null) {
                // A picked file that isn't a database doesn't need access kept. A recent database that
                // can't be read right now stays, since its provider may be offline.
                if (kept && !recents.contains(uri)) recents.release(uri)
                // A recent that can't be read at all may be gone for good, so it's named and can be
                // removed. Android doesn't reliably tell a deleted file from one that's offline.
                if (recent != null && error == R.string.file_unreadable) unavailable = recent
                else message = error
                return@launch
            }
            val name = displayName ?: recentDatabases.firstOrNull { it.uri == uri }?.name
            if (kept) {
                recents.add(uri, name ?: uri.lastPathSegment.orEmpty())
                refreshRecents()
            }
            openRequest = OpenRequest(uri, name)
        }
    }

    private fun inspect(uri: Uri): Int? = try {
        val stream = getApplication<Application>().contentResolver.openInputStream(uri)
        when {
            stream == null -> R.string.file_unreadable
            stream.use(KdbxHeader::isRecognized) -> null
            else -> R.string.file_not_kdbx
        }
    } catch (_: IOException) {
        R.string.file_unreadable
    } catch (_: SecurityException) {
        R.string.file_unreadable
    } catch (_: IllegalArgumentException) {
        R.string.file_unreadable
    }
}

/** The name a document provider shows for [uri], if it gives one. */
internal fun Context.documentName(uri: Uri): String? = try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex < 0) return@use null
        cursor.getString(nameIndex)?.take(1024)
    }
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
