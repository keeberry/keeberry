package app.keeberry

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keeberry.kdbx.Database
import app.keeberry.kdbx.KdbxException
import app.keeberry.kdbx.KdbxFailure
import app.keeberry.kdbx.KdbxReader
import app.keeberry.kdbx.NativeArgon2
import app.keeberry.ui.TestTags
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Settings left by another test, or by trying the app on this device, would change what leaving it does. */
    @Before
    @After
    fun resetSettings() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        AppSettings(context).theme = AppSettings.Theme.SYSTEM
    }

    @Test
    fun unlocksAndListsEntries() = withDatabase {
        unlock()
        compose.onNodeWithText("Second entry").assertIsDisplayed()
        compose.onNodeWithText("Fixture vault").assertIsDisplayed()
        // A writable KDBX 4 file can be changed.
        compose.onNodeWithText(context.getString(R.string.database_read_only)).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.NEW_ENTRY).assertIsDisplayed()
    }

    @Test
    fun kdbx3DatabasesStayReadOnly() = withDatabase("password.kdbx") {
        unlock()
        compose.onNodeWithText(context.getString(R.string.database_read_only)).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.NEW_ENTRY).assertDoesNotExist()
        compose.onNodeWithText("Sample entry").performClick()
        compose.onNodeWithTag(TestTags.EDIT_ENTRY).assertDoesNotExist()
    }

    @Test
    fun addsAnEntryAndSavesItToTheFile() = withDatabase {
        unlock()
        compose.onNodeWithTag(TestTags.NEW_ENTRY).performClick()
        compose.onNodeWithTag(TestTags.ENTRY_TITLE).performTextInput("Added entry")
        compose.onNodeWithTag(TestTags.ENTRY_USERNAME).performTextInput("added-user")
        compose.onNodeWithTag(TestTags.ENTRY_PASSWORD).performTextInput("added-password")
        compose.onNodeWithTag(TestTags.SAVE_ENTRY).performClick()
        waitForSaved()
        // The saved entry opens.
        waitForText("added-user")
        compose.onNodeWithText("added-password").assertDoesNotExist()
        savedFile().use { database ->
            val added = database.entries.single { it.title == "Added entry" }
            assertEquals("added-password", added.fields.single { it.name == "Password" }.text())
            assertEquals(3, database.entries.size)
        }
    }

    @Test
    fun generatesAPasswordAndAPassphraseForAnEntry() = withDatabase {
        val settings = AppSettings(context)
        try {
            settings.generatorMode = AppSettings.GeneratorMode.PASSWORD
            settings.passwordOptions = PasswordOptions()
            settings.passphraseOptions = PassphraseOptions()
            unlock()
            compose.onNodeWithTag(TestTags.NEW_ENTRY).performClick()
            compose.onNodeWithTag(TestTags.ENTRY_TITLE).performTextInput("Generated")
            compose.onNodeWithTag(TestTags.GENERATE_PASSWORD).performClick()
            compose.onNodeWithTag(TestTags.USE_GENERATED).performScrollTo().performClickWhenSettled(compose)
            compose.onAllNodes(hasTestTag(TestTags.GENERATOR)).assertCountEquals(0)

            // A passphrase replaces it, and the choice of a passphrase is kept for next time.
            compose.onNodeWithTag(TestTags.GENERATE_PASSWORD).performClick()
            compose.onNodeWithTag(TestTags.generatorMode("PASSPHRASE")).performClickWhenSettled(compose)
            compose.waitUntil(5_000) { generated().contains(' ') }
            val passphrase = generated()
            compose.onNodeWithTag(TestTags.USE_GENERATED).performScrollTo().performClickWhenSettled(compose)
            assertEquals(AppSettings.GeneratorMode.PASSPHRASE, settings.generatorMode)

            compose.onNodeWithTag(TestTags.SAVE_ENTRY).performClick()
            waitForSaved()
            savedFile().use { database ->
                val saved = database.entries.single { it.title == "Generated" }.fields.single { it.name == "Password" }.text()
                assertEquals(passphrase, saved)
                assertEquals(7, saved.split(' ').size)
            }
        } finally {
            settings.generatorMode = AppSettings.GeneratorMode.PASSWORD
        }
    }

    private fun generated(): String =
        compose.onNodeWithTag(TestTags.GENERATED_VALUE).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    @Test
    fun addsAnEntryToTheChosenGroup() = withDatabase("recycle-bin.kdbx") {
        unlock(waitFor = "Live entry")
        compose.onNodeWithTag(TestTags.NEW_ENTRY).performClick()
        compose.onNodeWithTag(TestTags.ENTRY_GROUP).performClick()
        compose.onNodeWithText("Passwords / Work").performClick()
        compose.onNodeWithTag(TestTags.ENTRY_TITLE).performTextInput("Grouped entry")
        compose.onNodeWithTag(TestTags.SAVE_ENTRY).performClick()
        waitForSaved()
        savedFile().use { database ->
            assertEquals("Passwords / Work", database.entries.single { it.title == "Grouped entry" }.group)
        }
    }

    @Test
    fun deletesAnEntryIntoTheRecycleBin() = withDatabase {
        unlock()
        compose.onNodeWithText("Sample entry").performClick()
        compose.onNodeWithTag(TestTags.DELETE_ENTRY).performClick()
        compose.onNodeWithText(context.getString(R.string.entry_delete_to_bin, "Sample entry")).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.CONFIRM_DELETE).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Sample entry")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Second entry").assertIsDisplayed()
        savedFile().use { database ->
            assertEquals(listOf("Second entry"), database.entries.map { it.title })
            assertEquals(listOf("Sample entry"), database.binned.map { it.title })
        }
        // It waits in the recycle bin.
        compose.onNodeWithTag(TestTags.RECYCLE_BIN).performClick()
        compose.onNodeWithText("Sample entry").assertIsDisplayed()
    }

    @Test
    fun restoresAnEntryFromTheRecycleBin() = withDatabase("recycle-bin.kdbx") {
        unlock(waitFor = "Live entry")
        compose.onNodeWithText("Deleted entry").assertDoesNotExist()
        compose.onNodeWithTag(TestTags.RECYCLE_BIN).performClick()
        compose.onNodeWithText("Deleted entry").performClick()
        // Entries in the bin can't be edited, only restored or deleted.
        compose.onNodeWithTag(TestTags.EDIT_ENTRY).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.RESTORE_ENTRY).performClick()
        compose.onNodeWithText(context.getString(R.string.entry_restore_to_group, "Deleted entry", "Passwords")).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.CONFIRM_RESTORE).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Deleted entry")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Old entry").assertIsDisplayed()
        // Back from the bin, the entry is in the list again.
        Espresso.pressBack()
        compose.onNodeWithText("Deleted entry").assertIsDisplayed()
        savedFile().use { database ->
            assertEquals("Passwords", database.entries.single { it.title == "Deleted entry" }.group)
            assertEquals(listOf("Old entry"), database.binned.map { it.title })
        }
    }

    @Test
    fun deletesAnEntryInTheRecycleBinForGood() = withDatabase("recycle-bin.kdbx") {
        unlock(waitFor = "Live entry")
        compose.onNodeWithTag(TestTags.RECYCLE_BIN).performClick()
        compose.onNodeWithText("Old entry").performClick()
        compose.onNodeWithTag(TestTags.DELETE_ENTRY).performClick()
        compose.onNodeWithText(context.getString(R.string.entry_delete_for_good, "Old entry")).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.CONFIRM_DELETE).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Old entry")).fetchSemanticsNodes().isEmpty() }
        savedFile().use { database ->
            assertEquals(listOf("Deleted entry"), database.binned.map { it.title })
            assertTrue(database.entries.none { it.title == "Old entry" })
        }
    }

    @Test
    fun emptiesTheRecycleBin() = withDatabase("recycle-bin.kdbx") {
        unlock(waitFor = "Live entry")
        compose.onNodeWithTag(TestTags.RECYCLE_BIN).performClick()
        compose.onNodeWithTag(TestTags.EMPTY_BIN).performClick()
        val message = context.resources.getQuantityString(R.plurals.recycle_bin_empty_message, 2, 2)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.CONFIRM_EMPTY_BIN).performClick()
        waitForText(context.getString(R.string.recycle_bin_empty))
        compose.onNodeWithText("Deleted entry").assertDoesNotExist()
        compose.onNodeWithText("Old entry").assertDoesNotExist()
        // Nothing left to empty.
        compose.onNodeWithTag(TestTags.EMPTY_BIN).assertDoesNotExist()
        savedFile().use { database ->
            assertTrue(database.binned.isEmpty())
            assertEquals(listOf("Live entry", "Work entry"), database.entries.map { it.title }.sorted())
        }
    }

    @Test
    fun editsAnEntryAndSavesItToTheFile() = withDatabase {
        unlock()
        compose.onNodeWithText("Sample entry").performClick()
        compose.onNodeWithTag(TestTags.EDIT_ENTRY).performClick()
        compose.onNodeWithTag(TestTags.ENTRY_USERNAME).performTextClearance()
        compose.onNodeWithTag(TestTags.ENTRY_USERNAME).performTextInput("changed-user")
        compose.onNodeWithTag(TestTags.SAVE_ENTRY).performClick()
        waitForSaved()
        waitForText("changed-user")
        compose.onNodeWithText("fixture-user").assertDoesNotExist()
        savedFile().use { database ->
            val entry = database.entries.single { it.title == "Sample entry" }
            assertEquals("changed-user", entry.fields.single { it.name == "UserName" }.text())
            assertEquals("sample-password", entry.fields.single { it.name == "Password" }.text())
        }
    }

    @Test
    fun closingTheFormWithChangesAsksFirst() = withDatabase {
        unlock()
        compose.onNodeWithText("Sample entry").performClick()
        compose.onNodeWithTag(TestTags.EDIT_ENTRY).performClick()
        compose.onNodeWithTag(TestTags.ENTRY_USERNAME).performTextInput("-more")
        // With the keyboard up, back would only close it.
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.entry_keep_editing)).performClick()
        compose.onNodeWithTag(TestTags.ENTRY_USERNAME).assertIsDisplayed()
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.entry_discard)).performClick()
        // Back on the entry, unchanged.
        compose.onNodeWithText("fixture-user").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.ENTRY_USERNAME).assertDoesNotExist()
    }

    @Test
    fun showsAnErrorForAWrongPassword() = withDatabase {
        compose.onNodeWithTag(TestTags.PASSWORD).performTextInput("wrong")
        compose.onNodeWithTag(TestTags.UNLOCK).performClickWhenSettled(compose)
        waitForText(context.getString(R.string.database_credentials_error))
        compose.onNodeWithTag(TestTags.UNLOCK).assertIsEnabled()
    }

    @Test
    fun searchMatchesVisibleFieldsButNotPasswords() = withDatabase {
        unlock()
        compose.onNodeWithTag(TestTags.SEARCH).performClick()
        compose.onNodeWithTag(TestTags.SEARCH_INPUT).performTextInput("fixture-user")
        result("Sample entry").assertIsDisplayed()
        result("Second entry").assertDoesNotExist()
        compose.onNodeWithTag(TestTags.SEARCH_INPUT).performTextClearance()
        compose.onNodeWithTag(TestTags.SEARCH_INPUT).performTextInput("sample-password")
        compose.onNodeWithText(context.getString(R.string.database_no_matches)).assertIsDisplayed()
    }

    @Test
    fun choosingAResultOpensItAndClosesTheSearch() = withDatabase {
        unlock()
        compose.onNodeWithTag(TestTags.SEARCH).performClick()
        compose.onNodeWithTag(TestTags.SEARCH_INPUT).performTextInput("second")
        result("Second entry").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.SEARCH_INPUT).assertDoesNotExist()
        compose.onNodeWithContentDescription(showPassword()).assertIsDisplayed()
        // Back from the entry returns to the whole list, with the search cleared.
        Espresso.pressBack()
        compose.onNodeWithText("Sample entry").assertIsDisplayed()
        compose.onNodeWithText("Second entry").assertIsDisplayed()
    }

    @Test
    fun showsStandardFieldsInOrderAndRevealsHiddenOnes() = withDatabase {
        unlock()
        compose.onNodeWithText("Sample entry").performClick()
        val tops = listOf(R.string.field_username, R.string.field_password, R.string.field_url).map {
            compose.onNodeWithText(context.getString(it)).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithContentDescription(context.getString(R.string.database_hidden)).assertIsDisplayed()
        compose.onNodeWithText("sample-password").assertDoesNotExist()
        compose.onNodeWithContentDescription(showPassword()).performClick()
        compose.onNodeWithText("sample-password").assertIsDisplayed()
    }

    @Test
    fun anotherEntryOpensWithItsFieldsHidden() = withDatabase {
        unlock()
        compose.onNodeWithText("Sample entry").performClick()
        compose.onNodeWithContentDescription(showPassword()).performClick()
        compose.onNodeWithText("sample-password").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Second entry").performClick()
        compose.onNodeWithContentDescription(showPassword()).assertIsDisplayed()
        compose.onNodeWithText("second-password").assertDoesNotExist()
    }

    @Test
    fun copiesAPasswordFlaggedAsSensitive() = withDatabase {
        unlock()
        compose.onNodeWithText("Sample entry").performClick()
        val password = context.getString(R.string.field_password)
        compose.onNodeWithContentDescription(context.getString(R.string.database_copy_field, password)).performClick()
        var copied: String? = null
        var sensitive = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            copied = clip?.getItemAt(0)?.text?.toString()
            sensitive = clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        }
        assertEquals("sample-password", copied)
        assertTrue(sensitive)
        // Copying doesn't reveal the field.
        compose.onNodeWithText("sample-password").assertDoesNotExist()
    }

    @Test
    fun keepsTheDatabaseAndOpenEntryAcrossRecreation() = withDatabase { scenario ->
        unlock()
        compose.onNodeWithText("Sample entry").performClick()
        compose.onNodeWithContentDescription(showPassword()).performClick()
        scenario.recreate()
        // Still open, with the field the user revealed still revealed.
        compose.onNodeWithText("sample-password").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Second entry").assertIsDisplayed()
    }

    @Test
    fun keepsTheTypedPasswordAcrossRecreation() = withDatabase { scenario ->
        compose.onNodeWithTag(TestTags.PASSWORD).performTextInput("fixture-only")
        scenario.recreate()
        compose.onNodeWithTag(TestTags.UNLOCK).performClickWhenSettled(compose)
        waitForText("Sample entry")
    }

    @Test
    fun leavingTheAppLocksTheDatabase() = withDatabase { scenario ->
        unlock()
        scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun aLockTimeoutKeepsTheDatabaseOpenInTheBackground() = withDatabase { scenario ->
        val settings = AppSettings(context)
        try {
            settings.lockTimeout = 60
            unlock()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(Lifecycle.State.CREATED, scenario.state)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Second entry").assertIsDisplayed()
        } finally {
            settings.lockTimeout = 0
        }
    }

    @Test
    fun offersBiometricUnlockOnlyForRecentDatabases() = withDatabase {
        // The fixture isn't a recent database, so there's nothing to keep biometric unlock with.
        compose.onNodeWithTag(TestTags.PASSWORD).assertIsDisplayed()
        compose.onAllNodes(hasTestTag(TestTags.REMEMBER_DEVICE)).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(TestTags.DEVICE_UNLOCK)).assertCountEquals(0)
    }

    @Test
    fun changesTheMasterKeyToANewPassword() = withDatabase {
        unlock()
        compose.onNodeWithTag(TestTags.MORE_OPTIONS).performClick()
        compose.onNodeWithTag(TestTags.CHANGE_MASTER_KEY).performClick()
        compose.onNodeWithTag(TestTags.NEW_PASSWORD).performTextInput("changed")
        compose.onNodeWithTag(TestTags.NEW_CONFIRMATION).performTextInput("chagned")
        compose.onNodeWithTag(TestTags.SAVE_MASTER_KEY).performScrollTo().performClickWhenSettled(compose)
        compose.onNodeWithText(context.getString(R.string.create_database_password_mismatch)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag(TestTags.NEW_CONFIRMATION).performTextClearance()
        compose.onNodeWithTag(TestTags.NEW_CONFIRMATION).performTextInput("changed")
        compose.onNodeWithTag(TestTags.SAVE_MASTER_KEY).performScrollTo().performClickWhenSettled(compose)
        waitForText(context.getString(R.string.master_key_changed_title), 30_000)
        compose.onNodeWithText(context.getString(R.string.close_dialog)).performClick()
        compose.onNodeWithText("Second entry").assertIsDisplayed()

        savedFile("changed").use { assertEquals(listOf("Sample entry", "Second entry"), it.entries.map { entry -> entry.title }) }
        val old = assertThrows(KdbxException::class.java) { savedFile().close() }
        assertEquals(KdbxFailure.CREDENTIALS, old.failure)

        // The open database carries the new key, so changes still save.
        compose.onNodeWithText("Sample entry").performClick()
        compose.onNodeWithTag(TestTags.DELETE_ENTRY).performClick()
        compose.onNodeWithTag(TestTags.CONFIRM_DELETE).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Sample entry")).fetchSemanticsNodes().isEmpty() }
        savedFile("changed").use { assertEquals(listOf("Second entry"), it.entries.map { entry -> entry.title }) }
    }

    @Test
    fun closingTheMasterKeyFormLeavesTheKeyAsItWas() = withDatabase {
        unlock()
        compose.onNodeWithTag(TestTags.MORE_OPTIONS).performClick()
        compose.onNodeWithTag(TestTags.CHANGE_MASTER_KEY).performClick()
        compose.onNodeWithTag(TestTags.NEW_PASSWORD).performTextInput("changed")
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onNodeWithText("Second entry").assertIsDisplayed()
        compose.onAllNodes(hasTestTag(TestTags.SAVE_MASTER_KEY)).assertCountEquals(0)
        savedFile().close()
    }

    @Test
    fun opensSettingsFromAnOpenDatabase() = withDatabase {
        unlock()
        compose.onNodeWithTag(TestTags.MORE_OPTIONS).performClick()
        compose.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_recent_limit)).assertIsDisplayed()
        Espresso.pressBack()
        // Still unlocked, where it was.
        compose.onNodeWithText("Second entry").assertIsDisplayed()
    }

    @Test
    fun changingTheThemeKeepsTheDatabaseOpen() = withDatabase { scenario ->
        val settings = AppSettings(context)
        fun nightMode(): Int {
            var mode = 0
            scenario.onActivity { mode = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK }
            return mode
        }
        try {
            unlock()
            val device = nightMode()
            compose.onNodeWithTag(TestTags.MORE_OPTIONS).performClick()
            compose.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
            compose.onNodeWithTag(TestTags.DARK_THEME_SWITCH).performClick()
            assertNotEquals(AppSettings.Theme.SYSTEM, settings.theme)
            compose.waitUntil(10_000) { nightMode() != device }
            compose.onNodeWithTag(TestTags.DARK_THEME_SWITCH).assertExists()
            Espresso.pressBack()
            compose.onNodeWithText("Second entry").assertIsDisplayed()
        } finally {
            settings.theme = AppSettings.Theme.SYSTEM
        }
    }

    @Test
    fun leavingTheAppFromSettingsLocksTheDatabase() = withDatabase { scenario ->
        unlock()
        compose.onNodeWithTag(TestTags.MORE_OPTIONS).performClick()
        compose.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
        scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    private var fixture = ""

    /** The fixture file as the app left it. */
    private fun savedFile(password: String = "fixture-only"): Database = File(context.cacheDir, "fixtures/$fixture").inputStream().use {
        KdbxReader(NativeArgon2(1L shl 30)).open(it, password.toCharArray())
    }

    private fun withDatabase(fixture: String = "modern-argon2id.kdbx", test: (ActivityScenario<DatabaseActivity>) -> Unit) {
        this.fixture = fixture
        val intent = Intent(context, DatabaseActivity::class.java)
            .setData(Fixtures.uri(fixture))
            .putExtra(DatabaseActivity.EXTRA_DISPLAY_NAME, fixture)
        ActivityScenario.launch<DatabaseActivity>(intent).use(test)
    }

    private fun unlock(password: String = "fixture-only", waitFor: String = "Sample entry") {
        compose.onNodeWithTag(TestTags.PASSWORD).performTextInput(password)
        compose.onNodeWithTag(TestTags.UNLOCK).performClickWhenSettled(compose)
        waitForText(waitFor)
    }

    private fun result(title: String) =
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag(TestTags.SEARCH_RESULTS)))

    private fun showPassword() =
        context.getString(R.string.database_show_field, context.getString(R.string.field_password))

    /** The form stays open until the file is written, and the typed text matches while it is open. */
    private fun waitForSaved() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(TestTags.SAVE_ENTRY)).fetchSemanticsNodes().isEmpty() }
    }

    private fun waitForText(text: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }
}
