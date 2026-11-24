package app.keeberry

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import app.keeberry.ui.SettingsState

/**
 * The settings screen, which the start screen and an open database both show. Whether it's open
 * outlives a configuration change.
 */
internal class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val recents = RecentDatabases(application)

    var showing by mutableStateOf(false)
    var recentLimit by mutableStateOf(settings.recentLimit)
        private set
    var theme by mutableStateOf(settings.theme)
        private set

    val state get() = SettingsState(theme, recentLimit)

    /** Saves [theme]; [ThemedActivity.applyTheme] then shows it. */
    fun changeTheme(theme: AppSettings.Theme) {
        settings.theme = theme
        this.theme = theme
    }

    fun changeRecentLimit(limit: Int) {
        recents.setLimit(limit)
        recentLimit = settings.recentLimit
    }
}
