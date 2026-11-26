package app.keeberry

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.keeberry.kdbx.writeKeyFile
import java.io.IOException

/**
 * The credentials a new database or a new master key is made of, as a form holds them: a master
 * password typed twice, a key file, a YubiKey, or several of them. [onChange] runs when one is
 * added, removed or switched, so the form can clear an error about the old choice. Compose state,
 * read and written on the main thread.
 */
@Stable
internal class NewCredentials(private val onChange: () -> Unit = {}) {
    val password = TextFieldState()
    val confirmation = TextFieldState()
    /** Whether the master password is part of the key, which can only be turned off next to another credential. */
    var usePassword by mutableStateOf(true)
        private set
    var useYubiKey by mutableStateOf(false)
        private set
    var keyUri by mutableStateOf<Uri?>(null)
        private set
    var keyName by mutableStateOf<String?>(null)
        private set

    /** Whether the form offers "Use master password", which only matters next to another credential. */
    val hasOtherCredential: Boolean get() = keyUri != null || useYubiKey

    val showsPassword: Boolean get() = !hasOtherCredential || usePassword

    fun onUsePasswordChanged(checked: Boolean) {
        usePassword = checked
        if (!checked) clearPasswords()
        onChange()
    }

    fun onUseYubiKeyChanged(checked: Boolean) {
        useYubiKey = checked
        // With nothing else left, the password is the credential again.
        if (!hasOtherCredential) usePassword = true
        onChange()
    }

    fun setKeyFile(uri: Uri, name: String?) {
        keyUri = uri
        keyName = name
        onChange()
    }

    fun removeKeyFile() {
        keyUri = null
        keyName = null
        if (!hasOtherCredential) usePassword = true
        onChange()
    }

    /** What is missing before the credentials can be used, as a message, or null when nothing is. */
    fun problem(): Int? = when {
        !showsPassword -> null
        password.text.isEmpty() -> R.string.create_database_password_required
        !password.text.contentEquals(confirmation.text) -> R.string.create_database_password_mismatch
        else -> null
    }

    /** A copy of the password to use, which the caller wipes, or null when the key has none. */
    fun passwordCopy(): CharArray? = if (showsPassword) password.text.let { text -> CharArray(text.length) { text[it] } } else null

    fun clearPasswords() {
        password.clearText()
        confirmation.clearText()
    }
}

/**
 * Writes a new random key file to the empty document [uri] that the picker just created, and
 * returns its name, or [suggestedName] if the provider can't say. Returns null, with the document
 * deleted, if it can't be written. Blocks, so it runs on a worker.
 */
internal fun Context.writeNewKeyFile(uri: Uri, suggestedName: String): String? = try {
    writeKeyFile(contentResolver.openOutputStream(uri, "w") ?: throw IOException())
    documentName(uri) ?: suggestedName
} catch (_: IOException) {
    deleteDocument(uri)
    null
} catch (_: SecurityException) {
    deleteDocument(uri)
    null
}

/** Deletes [uri], if its provider lets it. Blocks, so it runs on a worker. */
internal fun Context.deleteDocument(uri: Uri) {
    // Each failure means the document is already gone or the provider can't delete it, and
    // either way nothing more can be done.
    try {
        DocumentsContract.deleteDocument(contentResolver, uri)
    } catch (_: IOException) {
    } catch (_: SecurityException) {
    } catch (_: IllegalArgumentException) {
    } catch (_: UnsupportedOperationException) {
    }
}
