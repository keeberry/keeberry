package app.keeberry.ui

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import app.keeberry.kdbx.Database
import app.keeberry.kdbx.DatabaseEntry
import app.keeberry.kdbx.DatabaseField
import app.keeberry.kdbx.DatabaseGroup
import app.keeberry.kdbx.KdbxKey
import app.keeberry.kdbx.hexString

// What the screens' previews share. Each screen's previews are at the end of its own file.

/** A screen's main state, in light and dark. */
@Preview(name = "Light", showSystemUi = true)
@Preview(name = "Dark", showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
internal annotation class ScreenPreviews

/** The key the database screen opens [previewDatabase]'s bank entry by. */
internal val PREVIEW_BANK_ENTRY = ByteArray(16) { 2 }.hexString()

/** An unlocked, editable database of made-up entries, or of none. */
internal fun previewDatabase(entries: Boolean = true) = Database(
    name = "Personal",
    entries = if (!entries) emptyList() else listOf(
        entry(1, "Personal / Email", "Mail", "alex@example.com", "https://mail.example.com"),
        entry(
            2, "Personal / Banking", "Bank", "alex.doe", "https://bank.example.com",
            field("Recovery code", "4821-9930-1174", protected = true),
            field("Customer number", "00731942"),
        ),
        entry(3, "Personal", "Wi-Fi", "home", ""),
        entry(4, "Personal / Shopping", "Online store", "alex@example.com", "https://shop.example.com"),
        entry(5, "Personal / Social", "Photos", "alexdoe", "https://photos.example.com"),
    ),
    // A key makes the database editable, as an unlocked KDBX 4 database is.
    key = KdbxKey(ByteArray(32)),
    groups = listOf(
        DatabaseGroup(ByteArray(16) { 0x10 }, "Personal", 0),
        DatabaseGroup(ByteArray(16) { 0x11 }, "Personal / Banking", 1),
        DatabaseGroup(ByteArray(16) { 0x12 }, "Personal / Email", 1),
        DatabaseGroup(ByteArray(16) { 0x13 }, "Personal / Shopping", 1),
        DatabaseGroup(ByteArray(16) { 0x14 }, "Personal / Social", 1),
    ),
)

private fun field(name: String, value: String, protected: Boolean = false) =
    DatabaseField(name, value.toByteArray(), protected)

private fun entry(id: Int, group: String, title: String, user: String, url: String, vararg extra: DatabaseField) =
    DatabaseEntry(
        group,
        listOf(
            field("Title", title),
            field("UserName", user),
            field("Password", "correct-horse-battery-$id", protected = true),
            field("URL", url),
            field("Notes", ""),
        ) + extra,
        uuid = ByteArray(16) { id.toByte() },
    )
