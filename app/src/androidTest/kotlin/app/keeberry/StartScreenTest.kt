package app.keeberry

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keeberry.ui.TestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val recents = RecentDatabases(context)

    @Before
    @After
    fun forgetRecents() {
        context.getSharedPreferences("recent_vaults", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun offersOpeningAndCreatingADatabase() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.open_database)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.create_database)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.recent_databases)).assertDoesNotExist()
        }
    }

    @Test
    fun createDatabaseOpensTheNewDatabaseForm() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.create_database)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag(TestTags.CREATE)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(TestTags.NEW_PASSWORD).assertIsDisplayed()
        }
    }

    @Test
    fun opensARecentDatabase() {
        recents.add(Fixtures.uri("modern-argon2id.kdbx"), "modern-argon2id.kdbx")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.recent_databases)).assertIsDisplayed()
            compose.onNodeWithText("modern-argon2id.kdbx").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag(TestTags.UNLOCK)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(TestTags.UNLOCK).assertIsDisplayed()
        }
    }

    @Test
    fun explainsWhenARecentFileIsNotADatabase() {
        recents.add(Fixtures.uri("fixture.keyx"), "fixture.keyx")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("fixture.keyx").performClick()
            // The check reads the file off the main thread, which Compose's idling doesn't track.
            val message = context.getString(R.string.file_not_kdbx)
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.close_dialog)).performClick()
            // A recent database that can't be opened right now stays, since its provider may be offline.
            compose.onNodeWithText("fixture.keyx").assertIsDisplayed()
        }
    }

    @Test
    fun removesARecentDatabaseAfterConfirming() {
        recents.add(Fixtures.uri("modern-argon2id.kdbx"), "Old database")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Old database").performTouchInput { longClick() }
            compose.onNodeWithText(context.getString(R.string.recent_remove_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.recent_remove)).performClick()
            compose.onNodeWithText("Old database").assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.recent_databases)).assertDoesNotExist()
            assertEquals(emptyList<RecentDatabases.Recent>(), recents.list())
        }
    }
}
