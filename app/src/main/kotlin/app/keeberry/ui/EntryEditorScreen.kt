package app.keeberry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.keeberry.EntryEditor
import app.keeberry.R

/**
 * The form for a new entry or for changing one. Closing it with changes asks first, and while it
 * saves, nothing can be changed or closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryEditorScreen(form: EntryEditor, onSave: () -> Unit, onDiscard: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var generating by rememberSaveable { mutableStateOf(false) }
    val enabled = !form.saving
    val close = { if (form.changed) confirming = true else onDiscard() }
    BackHandler(enabled = enabled) { close() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (form.isNew) R.string.entry_new else R.string.entry_edit)) },
                navigationIcon = {
                    IconButton(close, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.entry_close))
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = enabled,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.testTag(TestTags.SAVE_ENTRY),
                    ) { Text(stringResource(if (form.saving) R.string.entry_saving else R.string.entry_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                if (form.saving) {
                    val status = stringResource(R.string.entry_saving)
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = status })
                }
                form.error?.let {
                    Text(
                        stringResource(it),
                        Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (form.isNew && form.groups.size > 1) GroupField(form, enabled)
                PlainField(form.title, R.string.field_title, enabled, Modifier.testTag(TestTags.ENTRY_TITLE))
                PlainField(form.userName, R.string.field_username, enabled, Modifier.testTag(TestTags.ENTRY_USERNAME),
                    keyboardType = KeyboardType.Email)
                SecretField(form.password, stringResource(R.string.field_password), enabled,
                    Modifier.testTag(TestTags.ENTRY_PASSWORD), onGenerate = { generating = true })
                PlainField(form.url, R.string.field_url, enabled, keyboardType = KeyboardType.Uri)
                PlainField(form.notes, R.string.field_notes, enabled, multiLine = true)
                if (form.custom.isNotEmpty()) {
                    Text(
                        stringResource(R.string.entry_custom_fields),
                        Modifier.padding(top = 24.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                form.custom.forEachIndexed { index, field ->
                    if (index > 0) HorizontalDivider(Modifier.padding(top = 16.dp))
                    CustomFieldEditor(field, enabled, onRemove = { form.removeField(field) })
                }
                OutlinedButton(
                    onClick = form::addField,
                    enabled = enabled,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.entry_add_field))
                }
            }
        }
    }
    if (generating && enabled) {
        PasswordGeneratorSheet(
            onUse = {
                form.password.setTextAndPlaceCursorAtEnd(it)
                generating = false
            },
            onDismiss = { generating = false },
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.entry_discard_title)) },
            confirmButton = {
                TextButton({
                    confirming = false
                    onDiscard()
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.entry_discard)) }
            },
            dismissButton = {
                TextButton({ confirming = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.entry_keep_editing))
                }
            },
            // A dialog has a window of its own, which needs the screen's screenshot protection.
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        )
    }
}

/** Where a new entry goes. Each group shows with its parents, since names can repeat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupField(form: EntryEditor, enabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it && enabled }) {
        OutlinedTextField(
            value = form.group?.path.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.entry_group)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag(TestTags.ENTRY_GROUP),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            form.groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        form.group = group
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun PlainField(
    state: TextFieldState,
    label: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    multiLine: Boolean = false,
) {
    OutlinedTextField(
        state = state,
        enabled = enabled,
        label = { Text(stringResource(label)) },
        lineLimits = if (multiLine) TextFieldLineLimits.MultiLine(minHeightInLines = 3) else TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            autoCorrectEnabled = multiLine || keyboardType == KeyboardType.Text,
        ),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun SecretField(
    state: TextFieldState,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** Opens the password generator, when the field offers it. */
    onGenerate: (() -> Unit)? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = state,
        enabled = enabled,
        label = { Text(label) },
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        trailingIcon = {
            Row {
                if (onGenerate != null) GenerateButton(enabled, onGenerate)
                val toggle = stringResource(if (visible) R.string.database_hide_field else R.string.database_show_field, label)
                IconToggleButton(
                    checked = visible,
                    onCheckedChange = { visible = it },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    enabled = enabled,
                ) {
                    Icon(painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility), toggle)
                }
            }
        },
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun CustomFieldEditor(field: EntryEditor.CustomField, enabled: Boolean, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                state = field.name,
                enabled = enabled,
                label = { Text(stringResource(R.string.entry_field_name)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
            )
            IconButton(onRemove, enabled = enabled, shapes = IconButtonDefaults.shapes(), modifier = Modifier.padding(top = 8.dp)) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.entry_remove_field))
            }
        }
        val label = field.name.text.toString().ifBlank { stringResource(R.string.entry_field_value) }
        if (field.protected) SecretField(field.value, label, enabled)
        else {
            OutlinedTextField(
                state = field.value,
                enabled = enabled,
                label = { Text(label) },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .heightIn(min = 48.dp)
                .toggleable(field.protected, enabled = enabled, role = Role.Checkbox) { field.protected = it },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Checkbox(field.protected, onCheckedChange = null, enabled = enabled)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.entry_field_protect), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun EntryEditorPreview(position: Int?, setup: EntryEditor.() -> Unit = {}) {
    val form = remember {
        val database = previewDatabase()
        EntryEditor(position?.let(database.entries::get), database.groups).apply(setup)
    }
    KeeBerryTheme { EntryEditorScreen(form, onSave = {}, onDiscard = {}) }
}

@ScreenPreviews
@Composable
private fun EditEntryPreview() = EntryEditorPreview(position = 1)

@Preview(name = "New entry", showSystemUi = true)
@Composable
private fun NewEntryPreview() = EntryEditorPreview(position = null)

@Preview(name = "Field without a name", showSystemUi = true)
@Composable
private fun EntryErrorPreview() = EntryEditorPreview(position = 1) {
    addField()
    error = R.string.entry_field_name_required
}

@Preview(name = "Saving", showSystemUi = true)
@Composable
private fun EntrySavingPreview() = EntryEditorPreview(position = 1) { saving = true }
