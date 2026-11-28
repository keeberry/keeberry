package app.keeberry

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keeberry.ui.TestTags
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateDatabaseScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun asksForAPasswordBeforeChoosingWhereToSave() {
        ActivityScenario.launch(CreateDatabaseActivity::class.java).use {
            compose.onNodeWithTag(TestTags.CREATE).performClick()
            compose.onNodeWithText(context.getString(R.string.create_database_password_required)).assertIsDisplayed()
        }
    }

    @Test
    fun asksForMatchingPasswords() {
        ActivityScenario.launch(CreateDatabaseActivity::class.java).use {
            compose.onNodeWithTag(TestTags.NEW_PASSWORD).performTextInput("secret")
            compose.onNodeWithTag(TestTags.NEW_CONFIRMATION).performTextInput("secrte")
            compose.onNodeWithTag(TestTags.CREATE).performClickWhenSettled(compose)
            compose.onNodeWithText(context.getString(R.string.create_database_password_mismatch))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun aGeneratedMasterPasswordFillsBothFields() {
        val settings = AppSettings(context)
        settings.generatorMode = AppSettings.GeneratorMode.PASSWORD
        settings.passwordOptions = PasswordOptions()
        ActivityScenario.launch(CreateDatabaseActivity::class.java).use {
            compose.onNodeWithTag(TestTags.GENERATE_PASSWORD).performClick()
            compose.onNodeWithTag(TestTags.USE_GENERATED).performScrollTo().performClickWhenSettled(compose)
            fun text(tag: String) =
                compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            val password = text(TestTags.NEW_PASSWORD)
            assertEquals(20, password.length)
            assertEquals(password, text(TestTags.NEW_CONFIRMATION))
        }
    }

    @Test
    fun offersAYubiKeyWithoutASlotAndLetsThePasswordGoNextToIt() {
        ActivityScenario.launch(CreateDatabaseActivity::class.java).use {
            compose.onNodeWithTag(TestTags.USE_YUBIKEY).performScrollTo().performClick()
            compose.onAllNodes(hasText("Slot 2")).assertCountEquals(0)
            compose.onNodeWithText(context.getString(R.string.create_database_yubikey_hint)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.database_use_password)).performScrollTo().performClick()
            compose.onAllNodes(hasTestTag(TestTags.NEW_PASSWORD)).assertCountEquals(0)
        }
    }

    @Test
    fun writesADatabaseThatUnlocksWithAGeneratedKeyFileAlone() {
        val key = emptyDocument("created.keyx")
        val uri = emptyDocument("created-key.kdbx")
        ActivityScenario.launch(CreateDatabaseActivity::class.java).use { scenario ->
            lateinit var model: CreateDatabaseViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[CreateDatabaseViewModel::class.java]
                model.createKeyFile(key.uri, "fallback.keyx")
            }
            compose.waitUntil(10_000) { model.credentials.keyName != null || model.error != null }
            assertEquals("created.keyx", model.credentials.keyName)
            assertTrue(key.file.readText().contains("<Version>2.0</Version>"))
            compose.onNodeWithText(context.getString(R.string.create_database_key_file_warning))
                .performScrollTo()
                .assertIsDisplayed()
            scenario.onActivity {
                model.credentials.onUsePasswordChanged(false)
                model.create(uri.uri, pickFlags = 0, it)
            }
            compose.waitUntil(30_000) { model.created != null || model.error != null }
            assertEquals("created-key.kdbx", model.created?.name)
        }

        val intent = Intent(context, DatabaseActivity::class.java).setData(uri.uri)
        ActivityScenario.launch<DatabaseActivity>(intent).use { scenario ->
            scenario.onActivity { ViewModelProvider(it)[DatabaseViewModel::class.java].setKeyFile(key.uri, "created.keyx") }
            compose.onNodeWithTag(TestTags.UNLOCK).performScrollTo().performClickWhenSettled(compose)
            val empty = context.getString(R.string.database_empty)
            compose.waitUntil(30_000) { compose.onAllNodes(hasText(empty)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun writesADatabaseThatUnlocksWithItsPassword() {
        val (file, uri) = emptyDocument("created.kdbx")
        ActivityScenario.launch(CreateDatabaseActivity::class.java).use { scenario ->
            compose.onNodeWithTag(TestTags.NEW_NAME).performTextInput("Device test")
            compose.onNodeWithTag(TestTags.NEW_PASSWORD).performTextInput("secret")
            compose.onNodeWithTag(TestTags.NEW_CONFIRMATION).performTextInput("secret")
            lateinit var model: CreateDatabaseViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[CreateDatabaseViewModel::class.java]
                model.create(uri, pickFlags = 0, it)
            }
            // The KDF runs off the main thread, which Compose's idling doesn't track.
            compose.waitUntil(30_000) { model.created != null || model.error != null }
            assertEquals("created.kdbx", model.created?.name)
        }
        assertTrue(file.length() > 0)

        val intent = Intent(context, DatabaseActivity::class.java)
            .setData(uri)
            .putExtra(DatabaseActivity.EXTRA_DISPLAY_NAME, "created.kdbx")
        ActivityScenario.launch<DatabaseActivity>(intent).use {
            compose.onNodeWithTag(TestTags.PASSWORD).performTextInput("secret")
            compose.onNodeWithTag(TestTags.UNLOCK).performClickWhenSettled(compose)
            val empty = context.getString(R.string.database_empty)
            compose.waitUntil(30_000) { compose.onAllNodes(hasText(empty)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Device test").assertIsDisplayed()
        }
    }

    private data class Document(val file: File, val uri: Uri)

    /** Stands in for the empty document the system picker creates. */
    private fun emptyDocument(name: String): Document {
        val file = File(context.cacheDir, "fixtures/$name")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        return Document(file, FileProvider.getUriForFile(context, "app.keeberry.fixtures", file))
    }
}
