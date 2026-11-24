package app.keeberry

import android.content.Context
import androidx.core.content.edit

/** App-wide preferences, which apply whichever database is open, or none. */
internal class AppSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How many databases the start screen remembers, within [RECENT_LIMITS]. */
    var recentLimit: Int
        get() = preferences.getInt(RECENT_LIMIT, DEFAULT_RECENT_LIMIT).coerceIn(RECENT_LIMITS)
        set(value) = preferences.edit { putInt(RECENT_LIMIT, value.coerceIn(RECENT_LIMITS)) }

    companion object {
        val RECENT_LIMITS = 1..10
        const val DEFAULT_RECENT_LIMIT = 5
        private const val RECENT_LIMIT = "recent_limit"
    }
}
