package app.keeberry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yubico.yubikit.yubiotp.Slot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentDatabasesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val recents = RecentDatabases(context)
    private val personal = Fixtures.uri("password.kdbx")
    private val work = Fixtures.uri("modern-argon2id.kdbx")

    @Before
    @After
    fun forgetRecents() {
        context.getSharedPreferences("recent_vaults", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun remembersTheYubiKeyThatAnsweredForEachDatabase() {
        recents.add(personal, "Personal")
        recents.add(work, "Work")
        assertNull(recents.yubiKey(personal))

        recents.rememberYubiKey(personal, YubiKeyChoice(12345678, Slot.ONE))
        recents.rememberYubiKey(work, YubiKeyChoice(null, Slot.TWO))

        assertEquals(YubiKeyChoice(12345678, Slot.ONE), RecentDatabases(context).yubiKey(personal))
        assertEquals(YubiKeyChoice(null, Slot.TWO), RecentDatabases(context).yubiKey(work))
        // Remembering doesn't reorder the list.
        assertEquals(listOf("Work", "Personal"), recents.list().map { it.name })
    }

    @Test
    fun openingADatabaseAgainKeepsItsYubiKey() {
        recents.add(personal, "Personal", YubiKeyChoice(7, Slot.TWO))
        recents.add(work, "Work")
        recents.add(personal, "Personal")
        assertEquals(YubiKeyChoice(7, Slot.TWO), recents.yubiKey(personal))
        assertEquals(listOf("Personal", "Work"), recents.list().map { it.name })
    }

    @Test
    fun remembersNoYubiKeyForADatabaseThatIsNotRecent() {
        recents.rememberYubiKey(personal, YubiKeyChoice(7, Slot.TWO))
        assertEquals(emptyList<RecentDatabases.Recent>(), recents.list())
        assertNull(recents.yubiKey(personal))
    }
}
