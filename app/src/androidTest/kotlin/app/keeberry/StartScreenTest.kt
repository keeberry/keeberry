package app.keeberry

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keeberry.ui.TestTags
import java.io.File
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
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        AppSettings(context).theme = AppSettings.Theme.SYSTEM
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
    fun namesADeletedRecentDatabaseAndOffersToRemoveIt() {
        val uri = Fixtures.uri("password.kdbx")
        File(context.cacheDir, "fixtures/password.kdbx").delete()
        recents.add(uri, "Deleted database")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Deleted database").performClick()
            // The check reads the file off the main thread, which Compose's idling doesn't track.
            val message = context.getString(R.string.recent_unavailable_message, "Deleted database")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.close_dialog)).performClick()
            compose.onNodeWithText(message).assertDoesNotExist()
            // Closing keeps it, since its storage may only be offline.
            compose.onNodeWithText("Deleted database").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.recent_unavailable_remove)).performClick()
            compose.onNodeWithText(message).assertDoesNotExist()
            compose.onNodeWithText("Deleted database").assertDoesNotExist()
            assertEquals(emptyList<RecentDatabases.Recent>(), recents.list())
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

    @Test
    fun opensSettingsBeforeAnyDatabase() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
            compose.onNodeWithText(context.getString(R.string.settings_recent_limit)).assertIsDisplayed()
            compose.onNodeWithTag(TestTags.RECENT_LIMIT).assert(hasStateDescription("${AppSettings.DEFAULT_RECENT_LIMIT}"))
            Espresso.pressBack()
            compose.onNodeWithText(context.getString(R.string.open_database)).assertIsDisplayed()
        }
    }

    @Test
    fun loweringTheLimitForgetsTheOldestDatabases() {
        recents.add(Fixtures.uri("password.kdbx"), "Oldest")
        recents.add(Fixtures.uri("modern-aes-kdf.kdbx"), "Middle")
        recents.add(Fixtures.uri("modern-argon2id.kdbx"), "Newest")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
            compose.onNodeWithTag(TestTags.RECENT_LIMIT).performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
            compose.onNodeWithTag(TestTags.RECENT_LIMIT).assert(hasStateDescription("2"))
            Espresso.pressBack()
            compose.onNodeWithText("Newest").assertIsDisplayed()
            compose.onNodeWithText("Middle").assertIsDisplayed()
            compose.onNodeWithText("Oldest").assertDoesNotExist()
        }
        assertEquals(2, AppSettings(context).recentLimit)
        assertEquals(listOf("Newest", "Middle"), recents.list().map { it.name })
        // New databases now push out the oldest past the new limit.
        recents.add(Fixtures.uri("password.kdbx"), "Oldest")
        assertEquals(listOf("Oldest", "Newest"), recents.list().map { it.name })
    }

    @Test
    fun choosingAThemeShowsItRightAway() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val device = scenario.nightMode()
            val deviceDark = device == Configuration.UI_MODE_NIGHT_YES
            compose.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
            // Matching the device, the switch shows the device's theme.
            compose.onNodeWithTag(TestTags.DARK_THEME_SWITCH).assertIs(deviceDark)

            compose.onNodeWithTag(TestTags.DARK_THEME_SWITCH).performClick()
            assertEquals(if (deviceDark) AppSettings.Theme.LIGHT else AppSettings.Theme.DARK, AppSettings(context).theme)
            compose.waitUntil(10_000) { scenario.nightMode() != device }
            // Recreated in the other theme, still in Settings.
            compose.onNodeWithTag(TestTags.DARK_THEME_SWITCH).assertIs(!deviceDark)

            compose.onNodeWithTag(TestTags.DARK_THEME).performClick()
            val chosen = if (deviceDark) "LIGHT" else "DARK"
            compose.onNodeWithTag(TestTags.themeOption(chosen)).assertIsSelected()
            compose.onNodeWithTag(TestTags.themeOption("SYSTEM")).assertIsNotSelected().performClick()
            assertEquals(AppSettings.Theme.SYSTEM, AppSettings(context).theme)
            compose.waitUntil(10_000) { scenario.nightMode() == device }
            // Still on the dark theme page.
            compose.onNodeWithTag(TestTags.themeOption("SYSTEM")).assertIsSelected()
            compose.onNodeWithTag(TestTags.themeOption(chosen)).assertIsNotSelected()
        }
    }

    @Test
    fun choosingAFontKeepsIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
            compose.onNodeWithTag(TestTags.FONT).assert(hasText(context.getString(R.string.font_google_sans_flex))).performClick()
            compose.onNodeWithTag(TestTags.fontOption("GOOGLE_SANS_FLEX")).assertIsSelected()
            compose.onNodeWithTag(TestTags.fontOption("ROBOTO")).assertIsNotSelected().performClick()
            assertEquals(AppSettings.Font.ROBOTO, AppSettings(context).font)
            compose.onNodeWithTag(TestTags.fontOption("ROBOTO")).assertIsSelected()
            compose.onNodeWithTag(TestTags.fontOption("GOOGLE_SANS_FLEX")).assertIsNotSelected()
            Espresso.pressBack()
            compose.onNodeWithTag(TestTags.FONT).assert(hasText(context.getString(R.string.font_roboto)))
        }
    }

    @Test
    fun choosingALockTimeoutKeepsIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
            compose.onNodeWithTag(TestTags.LOCK_TIMEOUT)
                .assert(hasText(context.getString(R.string.lock_timeout_immediately)))
                .performClick()
            compose.onNodeWithTag(TestTags.lockTimeoutOption(0)).assertIsSelected()
            compose.onNodeWithTag(TestTags.lockTimeoutOption(5 * 60)).assertIsNotSelected().performClick()
            assertEquals(5 * 60, AppSettings(context).lockTimeout)
            compose.onNodeWithTag(TestTags.lockTimeoutOption(5 * 60)).assertIsSelected()
            Espresso.pressBack()
            compose.onNodeWithTag(TestTags.LOCK_TIMEOUT)
                .assert(hasText(context.resources.getQuantityString(R.plurals.lock_timeout_minutes, 5, 5)))
        }
    }

    private fun SemanticsNodeInteraction.assertIs(on: Boolean) = if (on) assertIsOn() else assertIsOff()

    private fun ActivityScenario<MainActivity>.nightMode(): Int {
        var mode = 0
        onActivity { mode = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK }
        return mode
    }

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
}
