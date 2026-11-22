package app.keeberry

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.keeberry.ui.KeeBerryTheme
import app.keeberry.ui.StartScreen
import app.keeberry.ui.StartState

class MainActivity : ComponentActivity() {
    private lateinit var model: MainViewModel

    private val pickDocument = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) model.onPicked(uri, data.flags)
        else model.message = R.string.file_unreadable
    }

    private val createDatabase = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(CreateDatabaseActivity.EXTRA_DISPLAY_NAME)
        model.onCreated(name ?: data.data?.lastPathSegment.orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[MainViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            KeeBerryTheme {
                // The database screen can't change the list, but a provider can revoke access meanwhile.
                LifecycleResumeEffect(Unit) {
                    model.refreshRecents()
                    onPauseOrDispose {}
                }
                val request = model.openRequest
                LaunchedEffect(request) {
                    if (request == null) return@LaunchedEffect
                    model.onOpened()
                    startActivity(
                        Intent(this@MainActivity, DatabaseActivity::class.java)
                            .setData(request.uri)
                            .putExtra(DatabaseActivity.EXTRA_DISPLAY_NAME, request.name),
                    )
                }
                StartScreen(
                    state = StartState(model.recentDatabases, model.checking, model.message, model.created),
                    onOpenDatabase = ::pickDatabase,
                    onCreateDatabase = ::startCreating,
                    onRecent = model::onRecentChosen,
                    onRemoveRecent = model::removeRecent,
                    onDismissMessage = { model.message = null },
                    onDismissCreated = { model.created = null },
                )
            }
        }
    }

    private fun startCreating() {
        createDatabase.launch(Intent(this, CreateDatabaseActivity::class.java))
    }

    private fun pickDatabase() {
        // Providers don't consistently assign a MIME type to KDBX files. Cloud providers are
        // offered too: the database is read through the provider, which downloads it when needed.
        // Write access is asked for so entries can be saved. Providers that can't write grant read only.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            pickDocument.launch(intent)
        } catch (_: ActivityNotFoundException) {
            model.message = R.string.file_picker_unavailable
        }
    }
}
