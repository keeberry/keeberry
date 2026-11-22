package app.keeberry

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import app.keeberry.ui.CreateDatabaseScreen
import app.keeberry.ui.CreateDatabaseState
import app.keeberry.ui.KeeBerryTheme

/**
 * Asks for a name and the credentials (a master password, a key file, a YubiKey or several of
 * them), then where to save, and writes an empty database there. Finishes with the new document
 * as its result data and [EXTRA_DISPLAY_NAME].
 */
class CreateDatabaseActivity : ComponentActivity() {
    private lateinit var model: CreateDatabaseViewModel
    private var picking = false

    private val createDocument = registerForActivityResult(StartActivityForResult()) { result ->
        picking = false
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) model.create(uri, data.flags, this)
        else model.error = R.string.create_database_error
    }

    private val pickKeyDocument = registerForActivityResult(StartActivityForResult()) { result ->
        picking = false
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data
        if (uri?.scheme == ContentResolver.SCHEME_CONTENT) model.setKeyFile(uri, documentName(uri))
        else model.error = R.string.database_key_file_error
    }

    private val createKeyDocument = registerForActivityResult(StartActivityForResult()) { result ->
        picking = false
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) model.createKeyFile(uri, keyFileName())
        else model.error = R.string.create_database_key_file_save_error
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[CreateDatabaseViewModel::class.java]
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // After a configuration change the model may still be waiting for a YubiKey.
        model.attach(this)
        setContent {
            KeeBerryTheme {
                val created = model.created
                LaunchedEffect(created) {
                    if (created == null) return@LaunchedEffect
                    setResult(RESULT_OK, Intent().setData(created.uri).putExtra(EXTRA_DISPLAY_NAME, created.name))
                    finish()
                }
                CreateDatabaseScreen(
                    CreateDatabaseState(
                        name = model.name,
                        password = model.password,
                        confirmation = model.confirmation,
                        offersPassword = model.hasOtherCredential,
                        usePassword = model.usePassword,
                        showsPassword = model.showsPassword,
                        hasKeyFile = model.keyUri != null,
                        keyName = model.keyName,
                        useYubiKey = model.useYubiKey,
                        slotOne = model.slotOne,
                        error = model.error,
                        progress = model.progress,
                    ),
                    onBack = ::finish,
                    onCreate = ::chooseLocation,
                    onCancel = model::cancel,
                    onPickKeyFile = ::chooseKeyFile,
                    onNewKeyFile = ::chooseKeyFileLocation,
                    onRemoveKeyFile = model::removeKeyFile,
                    onUsePasswordChange = model::onUsePasswordChanged,
                    onUseYubiKeyChange = model::onUseYubiKeyChanged,
                    onSlotOneChange = { model.slotOne = it },
                )
            }
        }
    }

    private fun chooseLocation() {
        if (model.isBusy || !model.validate()) return
        launchPicker(createDocument, newDocument("${model.databaseName.toFileName()}.kdbx"))
    }

    private fun chooseKeyFile() {
        if (model.isBusy) return
        launchPicker(pickKeyDocument, Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
    }

    private fun chooseKeyFileLocation() {
        if (model.isBusy) return
        launchPicker(createKeyDocument, newDocument(keyFileName()))
    }

    private fun keyFileName() = "${model.databaseName.toFileName()}.keyx"

    // Octet-stream keeps the .kdbx and .keyx extensions: providers add their own for known types only.
    private fun newDocument(title: String) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_TITLE, title)

    private fun launchPicker(launcher: ActivityResultLauncher<Intent>, intent: Intent) {
        try {
            picking = true
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            picking = false
            model.error = R.string.file_picker_unavailable
        }
    }

    override fun onStop() {
        // The passwords wait in the form while a picker is open. Leaving the app clears them;
        // a write already under way has its own copy and carries on.
        if (!isChangingConfigurations && !picking) model.clearPasswords()
        super.onStop()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "app.keeberry.create.display_name"
    }
}

/** [this] as a file name most providers accept: no path separators or reserved characters. */
private fun String.toFileName(): String =
    replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').take(120).ifEmpty { "Database" }
