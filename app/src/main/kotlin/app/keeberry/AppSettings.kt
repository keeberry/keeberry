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

    /** The typeface every screen sets its text in. */
    var font: Font
        get() = Font.entries.firstOrNull { it.name == preferences.getString(FONT, null) } ?: DEFAULT_FONT
        set(value) = preferences.edit { putString(FONT, value.name) }

    /**
     * How many seconds an open database stays unlocked after KeeBerry leaves the screen, one of
     * [LOCK_TIMEOUTS]. At 0 it locks right away. Turning the screen off locks it whatever this is.
     */
    var lockTimeout: Int
        get() = preferences.getInt(LOCK_TIMEOUT, 0).takeIf { it in LOCK_TIMEOUTS } ?: 0
        set(value) = preferences.edit { putInt(LOCK_TIMEOUT, value.takeIf { it in LOCK_TIMEOUTS } ?: 0) }

    /** Whether the generator makes passwords or passphrases, as it last did. */
    var generatorMode: GeneratorMode
        get() = GeneratorMode.entries.firstOrNull { it.name == preferences.getString(GENERATOR_MODE, null) } ?: GeneratorMode.PASSWORD
        set(value) = preferences.edit { putString(GENERATOR_MODE, value.name) }

    /** The password generator's options, as last chosen. */
    var passwordOptions: PasswordOptions
        get() {
            val defaults = PasswordOptions()
            val options = PasswordOptions(
                length = preferences.getInt(PASSWORD_LENGTH, defaults.length),
                upper = preferences.getBoolean(PASSWORD_UPPER, defaults.upper),
                lower = preferences.getBoolean(PASSWORD_LOWER, defaults.lower),
                digits = preferences.getBoolean(PASSWORD_DIGITS, defaults.digits),
                symbols = preferences.getBoolean(PASSWORD_SYMBOLS, defaults.symbols),
                avoidLookAlike = preferences.getBoolean(PASSWORD_LOOK_ALIKE, defaults.avoidLookAlike),
                everyKind = preferences.getBoolean(PASSWORD_EVERY_KIND, defaults.everyKind),
            )
            return if (options.valid) options else defaults
        }
        set(value) = preferences.edit {
            putInt(PASSWORD_LENGTH, value.length)
            putBoolean(PASSWORD_UPPER, value.upper)
            putBoolean(PASSWORD_LOWER, value.lower)
            putBoolean(PASSWORD_DIGITS, value.digits)
            putBoolean(PASSWORD_SYMBOLS, value.symbols)
            putBoolean(PASSWORD_LOOK_ALIKE, value.avoidLookAlike)
            putBoolean(PASSWORD_EVERY_KIND, value.everyKind)
        }

    /** The passphrase generator's options, as last chosen. */
    var passphraseOptions: PassphraseOptions
        get() {
            val defaults = PassphraseOptions()
            val separator = preferences.getString(PASSPHRASE_SEPARATOR, null)?.takeIf { it in PassphraseOptions.SEPARATORS }
            val options = PassphraseOptions(
                words = preferences.getInt(PASSPHRASE_WORDS, defaults.words),
                separator = separator ?: defaults.separator,
                capitalize = preferences.getBoolean(PASSPHRASE_CAPITALIZE, defaults.capitalize),
            )
            return if (options.valid) options else defaults
        }
        set(value) = preferences.edit {
            putInt(PASSPHRASE_WORDS, value.words)
            putString(PASSPHRASE_SEPARATOR, value.separator)
            putBoolean(PASSPHRASE_CAPITALIZE, value.capitalize)
        }

    enum class GeneratorMode { PASSWORD, PASSPHRASE }

    enum class Theme { SYSTEM, LIGHT, DARK }

    /** The fonts KeeBerry bundles, in the order Settings lists them. */
    enum class Font { GOOGLE_SANS_FLEX, INTER, ROBOTO }

    companion object {
        val DEFAULT_FONT = Font.GOOGLE_SANS_FLEX
        private const val FONT = "font"
        val LOCK_TIMEOUTS = listOf(0, 30, 60, 5 * 60, 15 * 60)
        private const val LOCK_TIMEOUT = "lock_timeout"
        private const val GENERATOR_MODE = "generator_mode"
        private const val PASSWORD_LENGTH = "password_length"
        private const val PASSWORD_UPPER = "password_upper"
        private const val PASSWORD_LOWER = "password_lower"
        private const val PASSWORD_DIGITS = "password_digits"
        private const val PASSWORD_SYMBOLS = "password_symbols"
        private const val PASSWORD_LOOK_ALIKE = "password_avoid_look_alike"
        private const val PASSWORD_EVERY_KIND = "password_every_kind"
        private const val PASSPHRASE_WORDS = "passphrase_words"
        private const val PASSPHRASE_SEPARATOR = "passphrase_separator"
        private const val PASSPHRASE_CAPITALIZE = "passphrase_capitalize"
        val RECENT_LIMITS = 1..10
        const val DEFAULT_RECENT_LIMIT = 5
        private const val RECENT_LIMIT = "recent_limit"
        private const val THEME = "theme"
    }
}
