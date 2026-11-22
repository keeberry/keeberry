package app.keyberry

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast

/**
 * Copies database fields. Sensitive ones are flagged so the system hides them in its clipboard preview
 * and keyboard suggestions, and are cleared after [CLEAR_AFTER_MS] unless something else was copied
 * since. The clear outlives the database screen, which locks as soon as the user switches apps to paste.
 */
internal object DatabaseClipboard {
    const val CLEAR_AFTER_MS = 30_000L

    // ClipDescription.EXTRA_IS_SENSITIVE from API 33. Older versions read the same key.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val EXTRA_COPY_ID = "app.keyberry.clipboard.copy_id"

    private val main = Handler(Looper.getMainLooper())
    private var lastCopy = 0L

    fun copy(context: Context, label: String, text: String, sensitive: Boolean) {
        val app = context.applicationContext
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return
        val copyId = ++lastCopy
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, sensitive)
            putLong(EXTRA_COPY_ID, copyId)
        }
        clipboard.setPrimaryClip(clip)
        main.removeCallbacksAndMessages(this)
        if (sensitive) main.postAtTime({ clear(app, copyId) }, this, uptime() + CLEAR_AFTER_MS)
        // From API 33 the system confirms copies itself, and a second message would repeat it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val message = if (sensitive) R.string.database_copied_clears else R.string.database_copied
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clear(context: Context, copyId: Long) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        // In the background Android hides the clipboard from this app, so the description is null
        // and the clip is cleared. In the foreground, a newer copy from elsewhere is left alone.
        val description: ClipDescription? = clipboard.primaryClipDescription
        if (description != null && description.extras?.getLong(EXTRA_COPY_ID) != copyId) return
        clipboard.clearPrimaryClip()
    }

    private fun uptime() = android.os.SystemClock.uptimeMillis()
}
