package app.keeberry

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.yubico.yubikit.yubiotp.Slot
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Databases opened before, newest first. Only the document link, its display name and the YubiKey
 * that last answered for it are stored, and only for documents whose access the provider let
 * KeeBerry keep across restarts. Write access is kept too when the provider granted it, so changes
 * can be saved later. How many are kept is [AppSettings.recentLimit].
 */
internal class RecentDatabases(context: Context) {
    class Recent(val uri: Uri, val name: String, val yubiKey: YubiKeyChoice? = null)

    private val app = context.applicationContext
    private val settings = AppSettings(app)
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
            Recent(uri.toUri(), item.optString("name"), yubiKeyOf(item))
        }
    }

    private fun yubiKeyOf(item: JSONObject): YubiKeyChoice? {
        val slot = when (item.optInt(SLOT)) {
            1 -> Slot.ONE
            2 -> Slot.TWO
            else -> return null
        }
        return YubiKeyChoice(if (item.has(SERIAL)) item.optInt(SERIAL) else null, slot)
    }

    /** The YubiKey and slot that last answered for [uri], if it is a recent database. */
    fun yubiKey(uri: Uri): YubiKeyChoice? = list().firstOrNull { it.uri == uri }?.yubiKey

    /** Remembers that [choice] answered for [uri], if it is a recent database, without moving it. */
    fun rememberYubiKey(uri: Uri, choice: YubiKeyChoice) {
        val recents = list()
        if (recents.none { it.uri == uri && it.yubiKey != choice }) return
        save(recents.map { if (it.uri == uri) Recent(it.uri, it.name, choice) else it })
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

    /**
     * Puts a database with kept access first, and forgets the oldest past the limit. Without
     * [yubiKey], the one remembered for it stays.
     */
    fun add(uri: Uri, name: String, yubiKey: YubiKeyChoice? = null) {
        val limit = settings.recentLimit
        val recents = list()
        val others = recents.filter { it.uri != uri }
        val remembered = yubiKey ?: recents.firstOrNull { it.uri == uri }?.yubiKey
        others.drop(limit - 1).forEach { release(it.uri) }
        save(listOf(Recent(uri, name, remembered)) + others.take(limit - 1))
    }

    /** Changes how many databases are remembered, forgetting the oldest past the new limit. */
    fun setLimit(limit: Int) {
        settings.recentLimit = limit
        val recents = list()
        val kept = settings.recentLimit
        if (recents.size <= kept) return
        recents.drop(kept).forEach { release(it.uri) }
        save(recents.take(kept))
    }

    /** Forgets [uri] and gives up access to it. The file itself is untouched. */
    fun remove(uri: Uri) {
        release(uri)
        save(list().filter { it.uri != uri })
    }

    /** Gives up access to [uri], and turns its biometric unlock off. */
    fun release(uri: Uri) {
        DeviceUnlock(app).forget(uri)
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
        recents.forEach { recent ->
            val item = JSONObject().put("uri", recent.uri.toString()).put("name", recent.name)
            recent.yubiKey?.let { key ->
                item.put(SLOT, if (key.slot == Slot.ONE) 1 else 2)
                key.serial?.let { item.put(SERIAL, it) }
            }
            array.put(item)
        }
        preferences.edit { putString(KEY, array.toString()) }
    }

    private companion object {
        const val KEY = "recents"
        const val SLOT = "yubikey_slot"
        const val SERIAL = "yubikey_serial"
    }
}
