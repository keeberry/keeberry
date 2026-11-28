package app.keeberry

import android.content.Context
import java.security.SecureRandom
import kotlin.math.log2

/** How a random password is made: [length] characters from the kinds that are on. */
internal data class PasswordOptions(
    val length: Int = 20,
    val upper: Boolean = true,
    val lower: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = false,
    /** Leaves out characters that are easily mistaken for others, such as l, I, 1, O and 0. */
    val avoidLookAlike: Boolean = true,
    /** Uses at least one character of every kind that is on. */
    val everyKind: Boolean = true,
) {
    /** The kinds of character that are on, each as the characters it offers. */
    val kinds: List<String>
        get() = listOfNotNull(
            UPPER.takeIf { upper },
            LOWER.takeIf { lower },
            DIGITS.takeIf { digits },
            SYMBOLS.takeIf { symbols },
        ).map { kind -> if (avoidLookAlike) kind.filterNot { it in LOOK_ALIKE } else kind }

    /** Whether a password can be made: some kind is on, and it's long enough to hold one of each. */
    val valid: Boolean get() = kinds.isNotEmpty() && length in LENGTHS && (!everyKind || length >= kinds.size)

    companion object {
        val LENGTHS = 4..64
        const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val LOWER = "abcdefghijklmnopqrstuvwxyz"
        const val DIGITS = "0123456789"
        /** Every printable ASCII symbol. */
        const val SYMBOLS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        /** As KeePassXC leaves them out. */
        const val LOOK_ALIKE = "lBGIO0168|"
    }
}

/** How a passphrase is made: [words] random words, joined by [separator]. */
internal data class PassphraseOptions(
    val words: Int = 7,
    val separator: String = " ",
    val capitalize: Boolean = false,
) {
    val valid: Boolean get() = words in WORD_COUNTS

    companion object {
        val WORD_COUNTS = 3..12
        /** Separators offered, in order. */
        val SEPARATORS = listOf(" ", "-", ".", "_", "")
    }
}

/**
 * Makes passwords and passphrases with a [SecureRandom]. Every character or word is picked
 * uniformly, so the strength is exactly what [passwordBits] and [passphraseBits] report. A password
 * that must use every kind is drawn again until it does, which keeps that true among the passwords
 * that qualify.
 */
internal class PasswordGenerator(private val random: SecureRandom = SecureRandom()) {
    fun password(options: PasswordOptions): String {
        require(options.valid)
        val kinds = options.kinds
        val pool = kinds.joinToString("")
        while (true) {
            val chars = CharArray(options.length) { pool[random.nextInt(pool.length)] }
            if (!options.everyKind || kinds.all { kind -> chars.any { it in kind } }) return String(chars)
        }
    }

    /** A passphrase of words from [wordList], which should hold distinct words. */
    fun passphrase(options: PassphraseOptions, wordList: List<String>): String {
        require(options.valid && wordList.isNotEmpty())
        return List(options.words) {
            val word = wordList[random.nextInt(wordList.size)]
            if (options.capitalize) word.replaceFirstChar(Char::uppercaseChar) else word
        }.joinToString(options.separator)
    }

    companion object {
        /**
         * The password's strength in bits. Requiring every kind removes a few possibilities, which
         * matters only for the shortest passwords, so it's left out.
         */
        fun passwordBits(options: PasswordOptions): Double =
            if (!options.valid) 0.0 else options.length * log2(options.kinds.sumOf { it.length }.toDouble())

        fun passphraseBits(options: PassphraseOptions, words: Int): Double =
            if (!options.valid || words < 2) 0.0 else options.words * log2(words.toDouble())
    }
}

/** How strong a password of some number of bits is, with KeePassXC's thresholds. */
internal enum class Strength {
    POOR, WEAK, GOOD, EXCELLENT;

    companion object {
        fun of(bits: Double): Strength = when {
            bits < 40 -> POOR
            bits < 75 -> WEAK
            bits < 100 -> GOOD
            else -> EXCELLENT
        }
    }
}

/** The EFF's large wordlist, read from the app's assets once and kept, since it holds nothing secret. */
internal object WordList {
    @Volatile private var words: List<String>? = null

    fun load(context: Context): List<String> = words ?: synchronized(this) {
        words ?: context.assets.open("wordlists/eff_large.txt").bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }.also { words = it }
    }
}
