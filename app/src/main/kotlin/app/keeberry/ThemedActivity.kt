package app.keeberry

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity

/**
 * A screen in the theme chosen in Settings. A light or dark choice overrides the night mode of the
 * screen's configuration, so resources, the window and Compose all follow it as they would the
 * system's. A screen recreates itself when the choice changed since it was made, which keeps what
 * its view model holds, an open database included.
 */
abstract class ThemedActivity : ComponentActivity() {
    private var appliedTheme: AppSettings.Theme? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val theme = AppSettings(newBase).theme
        appliedTheme = theme
        val night = when (theme) {
            AppSettings.Theme.SYSTEM -> return
            AppSettings.Theme.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            AppSettings.Theme.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        val type = newBase.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()
        // A new configuration overrides only the fields it defines, but it defines a font scale of 1.
        applyOverrideConfiguration(Configuration().apply {
            fontScale = 0f
            uiMode = type or night
        })
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    /** Recreates this screen if the theme it was made in is no longer the chosen one. */
    fun applyTheme() {
        if (AppSettings(this).theme != appliedTheme) recreate()
    }
}
