package app.keeberry

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit

/** App-wide preferences, which apply whichever database is open, or none. */
internal class AppSettings(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How many databases the start screen remembers, within [RECENT_LIMITS]. */
    var recentLimit: Int
        get() = preferences.getInt(RECENT_LIMIT, DEFAULT_RECENT_LIMIT).coerceIn(RECENT_LIMITS)
        set(value) = preferences.edit { putInt(RECENT_LIMIT, value.coerceIn(RECENT_LIMITS)) }

    /** Whether screens are light or dark, or follow the system. */
    var theme: Theme
        get() = Theme.entries.firstOrNull { it.name == preferences.getString(THEME, null) } ?: Theme.SYSTEM
        set(value) {
            preferences.edit { putString(THEME, value.name) }
            applyTheme()
        }

    /**
     * From Android 12, hands [theme] to the system, which keeps it for KeeBerry alone. The system
     * then shows it from the splash screen on and recreates open screens in it. Doing it again when
     * nothing changed costs nothing, and puts the two back in step if they ever drift apart.
     */
    fun applyTheme() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = when (theme) {
            Theme.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            Theme.LIGHT -> UiModeManager.MODE_NIGHT_NO
            Theme.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        app.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
    }

    enum class Theme { SYSTEM, LIGHT, DARK }

    companion object {
        val RECENT_LIMITS = 1..10
        const val DEFAULT_RECENT_LIMIT = 5
        private const val RECENT_LIMIT = "recent_limit"
        private const val THEME = "theme"
    }
}
