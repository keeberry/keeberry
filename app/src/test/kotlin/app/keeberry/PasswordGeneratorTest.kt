package app.keeberry

import java.io.File
import kotlin.math.log2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {
    private val generator = PasswordGenerator()

    @Test
    fun passwordsHaveTheLengthAndOnlyTheCharactersChosen() {
        val options = PasswordOptions(length = 32, upper = false, lower = true, digits = true, symbols = false, avoidLookAlike = false)
        repeat(200) {
            val password = generator.password(options)
            assertEquals(32, password.length)
            assertTrue(password, password.all { it in PasswordOptions.LOWER || it in PasswordOptions.DIGITS })
        }
    }

    @Test
    fun everyKindChosenAppearsEvenInTheShortestPassword() {
        val options = PasswordOptions(length = 4, upper = true, lower = true, digits = true, symbols = true, everyKind = true)
        repeat(200) {
            val password = generator.password(options)
            options.kinds.forEach { kind -> assertTrue(password, password.any { it in kind }) }
        }
    }

    @Test
    fun lookAlikeCharactersCanBeLeftOut() {
        val options = PasswordOptions(length = 64, symbols = true, avoidLookAlike = true)
        repeat(100) {
            val password = generator.password(options)
            assertTrue(password, password.none { it in PasswordOptions.LOOK_ALIKE })
        }
    }

    @Test
    fun aPasswordOutsideTheLengthsOrWithNoKindIsInvalid() {
        // Four characters hold one of each of the four kinds.
        assertTrue(PasswordOptions(length = 4, symbols = true, everyKind = true).valid)
        assertFalse(PasswordOptions(length = 3, everyKind = false).valid)
        assertFalse(PasswordOptions(length = 65).valid)
        assertFalse(PasswordOptions(upper = false, lower = false, digits = false, symbols = false).valid)
    }

    @Test
    fun reportsTheStrengthOfThePool() {
        // The defaults: 22 capitals, 25 small letters and 6 digits once look-alikes are left out.
        assertEquals(20 * log2(53.0), PasswordGenerator.passwordBits(PasswordOptions()), 1e-9)
        assertEquals(Strength.EXCELLENT, Strength.of(PasswordGenerator.passwordBits(PasswordOptions())))
        assertEquals(Strength.POOR, Strength.of(PasswordGenerator.passwordBits(PasswordOptions(length = 6))))
        assertEquals(7 * log2(7772.0), PasswordGenerator.passphraseBits(PassphraseOptions(), 7772), 1e-9)
    }

    @Test
    fun passphrasesJoinTheChosenNumberOfWords() {
        val words = listOf("apple", "banana", "cherry", "damson")
        val passphrase = generator.passphrase(PassphraseOptions(words = 5, separator = "-", capitalize = true), words)
        val parts = passphrase.split("-")
        assertEquals(5, parts.size)
        assertTrue(passphrase, parts.all { part -> part.replaceFirstChar(Char::lowercaseChar) in words && part[0].isUpperCase() })
    }

    @Test
    fun theBundledWordListHoldsTheEffWordsWithoutHyphens() {
        val words = File("src/main/assets/wordlists/eff_large.txt").readLines()
        assertEquals(7772, words.size)
        assertEquals(words.size, words.toSet().size)
        assertTrue(words.all { word -> word.isNotEmpty() && word.all { it in 'a'..'z' } })
    }
}
