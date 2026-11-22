package app.keeberry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keeberry.R

/**
 * The key file part of the unlock and new-database forms: a button to choose one, the chosen
 * file's name and a way to remove it. [onNew], when given, offers to create a new key file too, and
 * [note] says something about the chosen file.
 */
@Composable
internal fun KeyFileChoice(
    keyName: String?,
    hasKeyFile: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onNew: (() -> Unit)? = null,
    note: Int? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onPick,
            shapes = ButtonDefaults.shapes(),
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag(TestTags.PICK_KEY_FILE),
        ) {
            Text(stringResource(if (hasKeyFile) R.string.database_change_key_file else R.string.database_pick_key_file))
        }
        if (onNew != null) {
            OutlinedButton(
                onClick = onNew,
                shapes = ButtonDefaults.shapes(),
                enabled = enabled,
                modifier = Modifier.weight(1f).testTag(TestTags.NEW_KEY_FILE),
            ) { Text(stringResource(R.string.create_database_new_key_file)) }
        }
    }
    keyName?.let {
        Text(
            stringResource(R.string.database_key_file_chosen, it),
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
    if (hasKeyFile && note != null) {
        Text(
            stringResource(note),
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (hasKeyFile) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = onRemove, shapes = ButtonDefaults.shapes(), enabled = enabled) {
                Text(stringResource(R.string.database_remove_key_file))
            }
        }
    }
}

@Composable
internal fun CheckboxRow(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Which challenge-response slot of the YubiKey to use: slot 1 when [slotOne], else slot 2. */
@Composable
internal fun SlotChoice(slotOne: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val slots = listOf(R.string.database_yubikey_slot_one to true, R.string.database_yubikey_slot_two to false)
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        slots.forEachIndexed { index, (label, one) ->
            ToggleButton(
                checked = slotOne == one,
                // Tapping the chosen slot keeps it chosen, like a radio button.
                onCheckedChange = { onChange(one) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                enabled = enabled,
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                else ButtonGroupDefaults.connectedTrailingButtonShapes(),
            ) {
                Text(stringResource(label))
            }
        }
    }
}
