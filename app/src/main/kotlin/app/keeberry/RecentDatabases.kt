package app.keeberry

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Databases opened before, newest first. Only the document link and its display name are stored, and
 * only for documents whose access the provider let KeeBerry keep across restarts. Write access is
 * kept too when the provider granted it, so changes can be saved later.
 */
internal class RecentDatabases(context: Context) {
    class Recent(val uri: Uri, val name: String)

    private val app = context.applicationContext
    // The file keeps the app's earlier name, so existing recents and their kept access carry over.
    private val preferences = app.getSharedPreferences("recent_vaults", Context.MODE_PRIVATE)

    fun list(): List<Recent> {
        val stored = try {
            JSONArray(preferences.getString(KEY, null) ?: return emptyList())
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until stored.length()).mapNotNull { index ->
            val item = stored.optJSONObject(index) ?: return@mapNotNull null
            val uri = item.optString("uri").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            Recent(uri.toUri(), item.optString("name"))
        }
    }

    /**
     * Keeps read access to a picked document across restarts, and write access if it was granted.
     * Returns false if the provider doesn't allow that, in which case the document can't be offered
     * again later.
     */
    fun keepAccess(uri: Uri, pickFlags: Int): Boolean {
        if (pickFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return false
        val resolver = app.contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (pickFlags and write != 0) {
            try {
                resolver.takePersistableUriPermission(uri, read or write)
                return true
            } catch (_: SecurityException) {
                // Fall back to reading only.
            }
        }
        return try {
            resolver.takePersistableUriPermission(uri, read)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun contains(uri: Uri): Boolean = list().any { it.uri == uri }

    /** Puts a database with kept access first, and forgets the oldest past the limit. */
    fun add(uri: Uri, name: String) {
        val others = list().filter { it.uri != uri }
        others.drop(MAX - 1).forEach { release(it.uri) }
        save(listOf(Recent(uri, name)) + others.take(MAX - 1))
    }

    /** Forgets [uri] and gives up access to it. The file itself is untouched. */
    fun remove(uri: Uri) {
        release(uri)
        save(list().filter { it.uri != uri })
    }

    fun release(uri: Uri) {
        val resolver = app.contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            resolver.releasePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            try {
                // Only read access was kept.
                resolver.releasePersistableUriPermission(uri, read)
            } catch (_: SecurityException) {
                // Already gone, for example after the file was deleted.
            }
        }
    }

    private fun save(recents: List<Recent>) {
        val array = JSONArray()
        recents.forEach { array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name)) }
        preferences.edit { putString(KEY, array.toString()) }
    }

    private companion object {
        const val KEY = "recents"
        const val MAX = 5
    }
}
