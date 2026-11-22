package app.keyberry

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModelProvider
import app.keyberry.ui.BrowserState
import app.keyberry.ui.KeyberryTheme
import app.keyberry.ui.DatabaseScreen
import app.keyberry.ui.UnlockState

class DatabaseActivity : ComponentActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri?.scheme != ContentResolver.SCHEME_CONTENT) {
            finish()
            return
        }
        databaseUri = uri
        model = ViewModelProvider(this)[DatabaseViewModel::class.java]
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // After a configuration change the model still holds the database or the unlock in progress.
        model.attach(this)
        val fileName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        setContent {
            KeyberryTheme {
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
                        BrowserState(it, model.canEdit, model.query, model.savedEntry, model.deleting, model.deleteError)
                    },
                    editor = model.editor,
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
                    onDismissDelete = { model.deleteError = null },
                    onSaveEntry = model::saveEditing,
                    onDiscardEntry = model::discardEditing,
                )
            }
        }
    }

    private fun chooseKeyFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            pickingKey = true
            pickKeyDocument.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pickingKey = false
            model.error = R.string.file_picker_unavailable
        }
    }

    override fun onStop() {
        // A configuration change recreates this screen around the same model, so only leaving
        // the app clears the password and locks the database.
        if (!isChangingConfigurations) {
            if (!pickingKey) model.password.clearText()
            if (model.database != null || model.isUnlocking) {
                model.lock()
                finish()
            }
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "app.keyberry.database.display_name"
    }
}
