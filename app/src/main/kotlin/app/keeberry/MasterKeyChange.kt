package app.keeberry

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job

/**
 * The form that changes an open database's master key, and the change under way. Compose state,
 * read and written on the main thread.
 */
@Stable
internal class MasterKeyChange {
    var error by mutableStateOf<Int?>(null)
    val credentials = NewCredentials(onChange = { error = null })
    var progress by mutableStateOf<DatabaseViewModel.Progress?>(null)
        internal set

    internal var job: Job? = null
    internal var hardware: YubiKeyChallenge? = null
    /** Whether the user cancelled the change under way, which then ends without an error. */
    internal var cancelled = false
    /** Whether a new key file is being written, which the change waits for. */
    internal var savingKeyFile = false

    val busy: Boolean get() = job != null
}
