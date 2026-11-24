package app.keeberry

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModelProvider
import app.keeberry.ui.BrowserState
import app.keeberry.ui.KeeBerryTheme
import app.keeberry.ui.DatabaseScreen
import app.keeberry.ui.MasterKeyActions
import app.keeberry.ui.SettingsActions
import app.keeberry.ui.UnlockState

class DatabaseActivity : ThemedActivity() {
    private lateinit var model: DatabaseViewModel
    private lateinit var databaseUri: Uri
    private var pickingKey = false

    private val pickKeyDocument = registerForActivityResult(StartActivityForResult()) { result ->
        pickingKey = false
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data
        if (uri?.scheme == ContentResolver.SCHEME_CONTENT) model.setKeyFile(uri, documentName(uri))
        else model.error = R.string.database_key_file_error
    }

    private val pickNewKeyDocument = registerForActivityResult(StartActivityForResult()) { result ->
        pickingKey = false
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val form = model.keyChange ?: return@registerForActivityResult
        val uri = result.data?.data
        if (uri?.scheme == ContentResolver.SCHEME_CONTENT) form.credentials.setKeyFile(uri, documentName(uri))
        else form.error = R.string.database_key_file_error
    }

    private val createNewKeyDocument = registerForActivityResult(StartActivityForResult()) { result ->
        pickingKey = false
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) model.createKeyFile(uri, newKeyFileName())
        else model.keyChange?.error = R.string.create_database_key_file_save_error
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri?.scheme != ContentResolver.SCHEME_CONTENT) {
            finish()
            return
        }
        databaseUri = uri
        model = ViewModelProvider(this)[DatabaseViewModel::class.java]
        val settings = ViewModelProvider(this)[SettingsViewModel::class.java]
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // After a configuration change the model still holds the database or the unlock in progress.
        model.attach(this)
        val fileName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        setContent {
            KeeBerryTheme {
                DatabaseScreen(
                    fileName = fileName,
                    unlock = UnlockState(
                        password = model.password,
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
                    browser = model.database?.let {
                        BrowserState(
                            it, model.canEdit, model.query, model.savedEntry, model.changing, model.changeError, model.showingBin,
                        )
                    },
                    editor = model.editor,
                    keyChange = model.keyChange,
                    keyChanged = model.keyChanged,
                    onBack = ::finish,
                    onLock = ::finish,
                    onUnlock = { model.unlock(databaseUri, this) },
                    onCancelUnlock = model::cancel,
                    onPickKeyFile = ::chooseKeyFile,
                    onRemoveKeyFile = model::removeKeyFile,
                    onUsePasswordChange = model::onUsePasswordChanged,
                    onUseYubiKeyChange = model::onUseYubiKeyChanged,
                    onSlotOneChange = { model.slotOne = it },
                    onSavedEntryShown = { model.savedEntry = null },
                    onNewEntry = { model.startEditing(null) },
                    onEditEntry = model::startEditing,
                    onDeleteEntry = model::deleteEntry,
                    onRestoreEntry = model::restoreEntry,
                    onEmptyBin = model::emptyBin,
                    onDismissChange = { model.changeError = null },
                    onShowBin = { model.showingBin = it },
                    onSaveEntry = model::saveEditing,
                    onDiscardEntry = model::discardEditing,
                    keyActions = MasterKeyActions(
                        onStart = model::startKeyChange,
                        onChange = { model.changeMasterKey(this) },
                        onCancel = model::cancelKeyChange,
                        onDiscard = model::discardKeyChange,
                        onPickKeyFile = { launchPicker(pickNewKeyDocument, openKeyDocument()) },
                        onNewKeyFile = {
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                // Octet-stream keeps the .keyx extension: providers add their own for known types only.
                                .setType("application/octet-stream")
                                .putExtra(Intent.EXTRA_TITLE, newKeyFileName())
                            launchPicker(createNewKeyDocument, intent)
                        },
                        onChangedShown = { model.keyChanged = false },
                    ),
                    // Shown in place, so leaving the app from them still locks the database.
                    settings = settings.state.takeIf { settings.showing },
                    settingsActions = SettingsActions(
                        onOpen = { settings.showing = true },
                        onClose = { settings.showing = false },
                        onThemeChange = {
                            settings.changeTheme(it)
                            applyTheme()
                        },
                        onRecentLimitChange = settings::changeRecentLimit,
                    ),
                )
            }
        }
    }

    private fun chooseKeyFile() = launchPicker(pickKeyDocument, openKeyDocument())

    private fun openKeyDocument() = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")

    private fun newKeyFileName(): String {
        val name = model.database?.name?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_DISPLAY_NAME)?.substringBeforeLast('.')
        return "${name.orEmpty().toFileName()}.keyx"
    }

    private fun launchPicker(launcher: ActivityResultLauncher<Intent>, intent: Intent) {
        try {
            pickingKey = true
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pickingKey = false
            val form = model.keyChange
            if (form != null) form.error = R.string.file_picker_unavailable else model.error = R.string.file_picker_unavailable
        }
    }

    override fun onStop() {
        // A configuration change recreates this screen around the same model, so only leaving
        // the app clears the password and locks the database. A key file picker the user opened
        // keeps both, so the forms still hold what was typed when it returns.
        if (!isChangingConfigurations && !pickingKey) {
            model.password.clearText()
            if (model.database != null || model.isUnlocking) {
                model.lock()
                finish()
            }
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "app.keeberry.database.display_name"
    }
}
