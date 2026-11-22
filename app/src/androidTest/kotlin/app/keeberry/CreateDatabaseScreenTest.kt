package app.keeberry

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
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
    fun writesADatabaseThatUnlocksWithItsPassword() {
        // Stands in for the document the system picker creates.
        val file = File(context.cacheDir, "fixtures/created.kdbx")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        val uri = FileProvider.getUriForFile(context, "app.keeberry.fixtures", file)
        ActivityScenario.launch(CreateDatabaseActivity::class.java).use { scenario ->
            compose.onNodeWithTag(TestTags.NEW_NAME).performTextInput("Device test")
            compose.onNodeWithTag(TestTags.NEW_PASSWORD).performTextInput("secret")
            compose.onNodeWithTag(TestTags.NEW_CONFIRMATION).performTextInput("secret")
            lateinit var model: CreateDatabaseViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[CreateDatabaseViewModel::class.java]
                model.create(uri, pickFlags = 0)
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
}
