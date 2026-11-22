package app.keyberry

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import app.keyberry.ui.CreateDatabaseScreen
import app.keyberry.ui.CreateDatabaseState
import app.keyberry.ui.KeyberryTheme

/**
 * Asks for a name and master password, then where to save, and writes an empty database there.
 * Finishes with the new document as its result data and [EXTRA_DISPLAY_NAME].
 */
class CreateDatabaseActivity : ComponentActivity() {
    private lateinit var model: CreateDatabaseViewModel
    private var pickingLocation = false

    private val createDocument = registerForActivityResult(StartActivityForResult()) { result ->
        pickingLocation = false
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) model.create(uri, data.flags)
        else model.error = R.string.create_database_error
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[CreateDatabaseViewModel::class.java]
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            KeyberryTheme {
                val created = model.created
                LaunchedEffect(created) {
                    if (created == null) return@LaunchedEffect
                    setResult(RESULT_OK, Intent().setData(created.uri).putExtra(EXTRA_DISPLAY_NAME, created.name))
                    finish()
                }
                CreateDatabaseScreen(
                    CreateDatabaseState(model.name, model.password, model.confirmation, model.error, model.progress),
                    onBack = ::finish,
                    onCreate = ::chooseLocation,
                    onCancel = model::cancel,
                )
            }
        }
    }

    private fun chooseLocation() {
        if (model.isCreating || !model.validate()) return
        // Octet-stream keeps the .kdbx extension: providers add their own for known types only.
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, "${model.databaseName.toFileName()}.kdbx")
        try {
            pickingLocation = true
            createDocument.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pickingLocation = false
            model.error = R.string.file_picker_unavailable
        }
    }

    override fun onStop() {
        // The passwords wait in the form while the picker is open. Leaving the app clears them;
        // a write already under way has its own copy and carries on.
        if (!isChangingConfigurations && !pickingLocation) model.clearPasswords()
        super.onStop()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "app.keyberry.create.display_name"
    }
}

/** [this] as a file name most providers accept: no path separators or reserved characters. */
private fun String.toFileName(): String =
    replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').take(120).ifEmpty { "Database" }
