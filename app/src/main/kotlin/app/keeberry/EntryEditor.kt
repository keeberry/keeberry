package app.keeberry

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.keeberry.kdbx.DatabaseEntry
import app.keeberry.kdbx.DatabaseGroup
import app.keeberry.kdbx.EntryField
import app.keeberry.kdbx.utf8

/**
 * The form for a new entry, or for changing [original]. A new entry goes in one of [groups], the root
 * group unless another is chosen. Its state is Compose state, read and written on the main thread,
 * and it lives in [DatabaseViewModel] so a configuration change keeps it.
 */
internal class EntryEditor(private val original: DatabaseEntry?, val groups: List<DatabaseGroup> = emptyList()) {
    /** A field of the entry's own, beyond the standard ones. */
    class CustomField(name: String = "", value: String = "", protected: Boolean = false) {
        val name = TextFieldState(name)
        val value = TextFieldState(value)
        var protected by mutableStateOf(protected)
        internal val initialName = name
        internal val initialValue = value
        internal val initialProtected = protected

        fun clear() {
            name.clearText()
            value.clearText()
        }
    }

    val uuid: ByteArray? = original?.uuid
    val isNew: Boolean get() = original == null

    /** Where a new entry goes; null puts it in the root group. */
    var group by mutableStateOf(if (original == null) groups.firstOrNull() else null)
    private val initialGroup = group

    val title = TextFieldState(text("Title"))
    val userName = TextFieldState(text("UserName"))
    val password = TextFieldState(text("Password"))
    val url = TextFieldState(text("URL"))
    val notes = TextFieldState(text("Notes"))
    val custom = mutableStateListOf<CustomField>().apply {
        original?.fields?.filter { it.name !in STANDARD }?.forEach { add(CustomField(it.name, it.text(), it.protected)) }
    }
    private val initialCustom = custom.toList()

    var saving by mutableStateOf(false)
    var error by mutableStateOf<Int?>(null)

    private val standard: List<Pair<String, TextFieldState>>
        get() = listOf("Title" to title, "UserName" to userName, "Password" to password, "URL" to url, "Notes" to notes)

    /** Whether anything differs from what the form started with. */
    val changed: Boolean
        get() = group !== initialGroup ||
            standard.any { (name, state) -> !state.text.contentEquals(text(name)) } ||
            custom.toList() != initialCustom ||
            custom.any {
                !it.name.text.contentEquals(it.initialName) || !it.value.text.contentEquals(it.initialValue) ||
                    it.protected != it.initialProtected
            }

    fun addField() {
        custom.add(CustomField())
    }

    fun removeField(field: CustomField) {
        custom.remove(field)
        field.clear()
    }

    /** What is wrong with the form, or null if it can be saved. */
    fun problem(): Int? {
        val names = custom.map { it.name.text.toString() }
        return when {
            names.any(String::isBlank) -> R.string.entry_field_name_required
            names.any { it in STANDARD } || names.toSet().size != names.size -> R.string.entry_field_name_taken
            else -> null
        }
    }

    /**
     * The values to store, standard fields first as KeePass writes them. A field keeps the protection
     * it had; new standard fields protect only the password, as KeePass does by default.
     */
    fun fields(): List<EntryField> {
        val values = standard.map { (name, state) ->
            val protected = original?.fields?.firstOrNull { it.name == name }?.protected ?: (name == "Password")
            EntryField(name, utf8(state.text), protected)
        }
        return values + custom.map { EntryField(it.name.text.toString(), utf8(it.value.text), it.protected) }
    }

    fun clear() {
        standard.forEach { (_, state) -> state.clearText() }
        custom.forEach(CustomField::clear)
        initialCustom.forEach(CustomField::clear)
    }

    private fun text(name: String): String = original?.fields?.firstOrNull { it.name == name }?.text().orEmpty()

    companion object {
        val STANDARD = setOf("Title", "UserName", "Password", "URL", "Notes")
    }
}
