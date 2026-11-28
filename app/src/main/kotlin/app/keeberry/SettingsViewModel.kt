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
    private val deviceUnlock = DeviceUnlock(application)

    var showing by mutableStateOf(false)
    var recentLimit by mutableStateOf(settings.recentLimit)
        private set
    var theme by mutableStateOf(settings.theme)
        private set
    /** The font the screen is set in, which changes as soon as another is chosen. */
    var font by mutableStateOf(settings.font)
        private set
    var lockTimeout by mutableStateOf(settings.lockTimeout)
        private set
    var deviceUnlockCount by mutableStateOf(deviceUnlock.count())
        private set

    val state get() = SettingsState(theme, font, recentLimit, lockTimeout, deviceUnlockCount)

    /** Picks up changes made from another screen's settings, such as an open database's. */
    fun reload() {
        theme = settings.theme
        font = settings.font
        recentLimit = settings.recentLimit
        lockTimeout = settings.lockTimeout
        deviceUnlockCount = deviceUnlock.count()
    }

    fun changeFont(font: AppSettings.Font) {
        settings.font = font
        this.font = font
    }

    /** Saves [theme]; [ThemedActivity.applyTheme] then shows it. */
    fun changeTheme(theme: AppSettings.Theme) {
        settings.theme = theme
        this.theme = theme
    }

    fun changeRecentLimit(limit: Int) {
        recents.setLimit(limit)
        recentLimit = settings.recentLimit
        // Forgetting a database turns its biometric unlock off.
        deviceUnlockCount = deviceUnlock.count()
    }

    fun changeLockTimeout(seconds: Int) {
        settings.lockTimeout = seconds
        lockTimeout = settings.lockTimeout
    }

    /** Turns biometric unlock off for every database. */
    fun forgetDeviceUnlock() {
        deviceUnlock.forgetAll()
        deviceUnlockCount = deviceUnlock.count()
    }
}
