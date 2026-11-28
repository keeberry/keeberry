package app.keeberry

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import app.keeberry.ui.BrowserState
import app.keeberry.ui.DeviceUnlockState
import app.keeberry.ui.KeeBerryTheme
import app.keeberry.ui.DatabaseScreen
import app.keeberry.ui.MasterKeyActions
import app.keeberry.ui.SettingsActions
import app.keeberry.ui.UnlockState

class DatabaseActivity : ThemedActivity() {
    private lateinit var model: DatabaseViewModel
    private lateinit var databaseUri: Uri
    private var pickingKey = false
    /** Dismisses the biometric prompt on screen, which leaving the app does. */
    private var promptSignal: CancellationSignal? = null
    private val main = Handler(Looper.getMainLooper())
    private val lockLater = Runnable(::lockAndFinish)
    /** When, in [SystemClock.elapsedRealtime], a database left open in the background locks. */
    private var lockAt: Long? = null
    private var listeningForScreenOff = false

    /** Turning the screen off locks an open database, whatever the lock timeout. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (model.database != null || model.isUnlocking) lockAndFinish()
        }
    }

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
        model.prepareDeviceUnlock(uri)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        listeningForScreenOff = true
        val fileName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        setContent {
            KeeBerryTheme(settings.font) {
                val prompt = model.prompt
                LaunchedEffect(prompt) { if (prompt != null) showPrompt(fileName) }
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
                        error = model.error,
                        progress = model.progress,
                        device = DeviceUnlockState(
                            offered = model.deviceUnlockOffered,
                            saved = model.deviceUnlockSaved,
                            remember = model.rememberDevice,
                        ),
                    ),
                    browser = model.database?.let {
                        BrowserState(
                            it, model.canEdit, model.query, model.savedEntry, model.changing, model.changeError, model.showingBin,
                        )
                    },
                    editor = model.editor,
                    keyChange = model.keyChange,
                    keyChanged = model.keyChanged,
                    keyChangeEndedDeviceUnlock = model.keyChangeEndedDeviceUnlock,
                    onBack = ::finish,
                    onLock = ::finish,
                    onUnlock = { model.unlock(databaseUri, this) },
                    onCancelUnlock = model::cancel,
                    onPickKeyFile = ::chooseKeyFile,
                    onRemoveKeyFile = model::removeKeyFile,
                    onUsePasswordChange = model::onUsePasswordChanged,
                    onUseYubiKeyChange = model::onUseYubiKeyChanged,
                    onRememberDeviceChange = model::onRememberDeviceChanged,
                    onDeviceUnlock = model::startDeviceUnlock,
                    deviceUnlockError = model.deviceUnlockError,
                    onDeviceUnlockErrorShown = { model.deviceUnlockError = null },
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
                        onFontChange = settings::changeFont,
                        onRecentLimitChange = {
                            settings.changeRecentLimit(it)
                            model.refreshDeviceUnlock()
                        },
                        onLockTimeoutChange = settings::changeLockTimeout,
                        onForgetDeviceUnlock = {
                            settings.forgetDeviceUnlock()
                            model.refreshDeviceUnlock()
                        },
                    ),
                )
            }
        }
    }

    private fun chooseKeyFile() = launchPicker(pickKeyDocument, openKeyDocument())

    // Access can be kept across restarts, which biometric unlock needs to read the key file later.
    private fun openKeyDocument() = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    /** Shows the biometric prompt the model asks for, if it still does. */
    private fun showPrompt(fileName: String?) {
        val request = model.takePrompt() ?: return
        val signal = CancellationSignal()
        promptSignal = signal
        val enable = request is DatabaseViewModel.DevicePrompt.Enable
        val title = getString(if (enable) R.string.device_unlock_enable_title else R.string.device_unlock_title)
        val dismiss = getString(if (enable) R.string.device_unlock_not_now else R.string.device_unlock_use_password)
        showBiometricPrompt(title, fileName, dismiss, request.cipher, signal) { result ->
            if (promptSignal === signal) promptSignal = null
            // The model hands the result to the screen showing now, which a rotation may have replaced.
            model.onPromptResult(request, result)
        }
    }

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

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(lockLater)
        val deadline = lockAt
        lockAt = null
        // The timer stops while the device sleeps, so a deadline that passed meanwhile locks now.
        if (deadline != null && SystemClock.elapsedRealtime() >= deadline) lockAndFinish()
    }

    override fun onStop() {
        // A configuration change recreates this screen around the same model, so only leaving
        // the app clears the password and locks the database. A key file picker the user opened
        // keeps both, so the forms still hold what was typed when it returns. A biometric prompt,
        // which sits over this screen without stopping it, is dismissed.
        if (!isChangingConfigurations && !pickingKey) {
            promptSignal?.cancel()
            leave()
        }
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(lockLater)
        if (listeningForScreenOff) unregisterReceiver(screenOff)
        super.onDestroy()
    }

    /**
     * Clears the typed password and locks the database, right away or, with a lock timeout set in
     * Settings, once it passes. An unlock under way stops either way.
     */
    private fun leave() {
        model.password.clearText()
        if (model.database == null && !model.isUnlocking) return
        val timeout = AppSettings(this).lockTimeout
        if (timeout == 0 || model.database == null) return lockAndFinish()
        lockAt = SystemClock.elapsedRealtime() + timeout * 1000L
        main.removeCallbacks(lockLater)
        main.postDelayed(lockLater, timeout * 1000L)
    }

    private fun lockAndFinish() {
        main.removeCallbacks(lockLater)
        lockAt = null
        promptSignal?.cancel()
        model.lock()
        finish()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "app.keeberry.database.display_name"
    }
}
