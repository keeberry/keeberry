package app.keeberry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keeberry.DatabaseViewModel
import app.keeberry.NewCredentials
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

/**
 * A full-screen form that asks for new credentials: [title] in the bar, [description] above the
 * fields, anything [header] adds below it, and a button labelled [action] that runs [onAction].
 * While [progress] is under way, the button reads [busyAction] and the task can be cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewCredentialsScreen(
    title: Int,
    description: Int,
    credentials: NewCredentials,
    error: Int?,
    progress: DatabaseViewModel.Progress?,
    action: Int,
    busyAction: Int,
    actionTag: String,
    onBack: () -> Unit,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    onPickKeyFile: () -> Unit,
    onNewKeyFile: () -> Unit,
    header: @Composable (enabled: Boolean) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.database_back))
                    }
                },
            )
        },
    ) { padding ->
        val busy = progress != null
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 464.dp).fillMaxWidth()) {
                    Text(
                        stringResource(description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    header(!busy)
                    NewCredentialsFields(credentials, enabled = !busy, onAction, onPickKeyFile, onNewKeyFile)
                    error?.let {
                        Text(
                            stringResource(it),
                            Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    MediumButton(
                        onClick = onAction,
                        text = stringResource(if (busy) busyAction else action),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(actionTag),
                        enabled = !busy,
                    )
                    // Below the button, so nothing the user just tapped moves when it appears.
                    if (progress != null) TaskProgress(progress, onCancel)
                }
            }
        }
    }
}

/**
 * The fields for new credentials: the master password twice, unless another credential lets it
 * be left out, a key file that can be chosen or created, and a YubiKey slot.
 */
@Composable
private fun NewCredentialsFields(
    credentials: NewCredentials,
    enabled: Boolean,
    onDone: () -> Unit,
    onPickKeyFile: () -> Unit,
    onNewKeyFile: () -> Unit,
) {
    if (credentials.hasOtherCredential) {
        CheckboxRow(R.string.database_use_password, credentials.usePassword, enabled, credentials::onUsePasswordChanged)
    }
    if (credentials.showsPassword) NewPasswordFields(credentials, enabled, onDone)
    KeyFileChoice(
        credentials.keyName,
        credentials.keyUri != null,
        enabled = enabled,
        onPick = onPickKeyFile,
        onRemove = credentials::removeKeyFile,
        onNew = onNewKeyFile,
        note = R.string.create_database_key_file_warning,
    )
    CheckboxRow(
        R.string.database_use_yubikey,
        credentials.useYubiKey,
        enabled,
        credentials::onUseYubiKeyChanged,
        Modifier.testTag(TestTags.USE_YUBIKEY),
    )
    if (credentials.useYubiKey) {
        SlotChoice(credentials.slotOne, enabled = enabled) { credentials.slotOne = it }
        Text(
            stringResource(R.string.create_database_yubikey_hint),
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The master password and its confirmation, which share one show/hide toggle. */
@Composable
private fun NewPasswordFields(credentials: NewCredentials, enabled: Boolean, onDone: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    NewPasswordField(
        credentials.password,
        R.string.database_password,
        visible,
        enabled = enabled,
        onDone = null,
        modifier = Modifier.testTag(TestTags.NEW_PASSWORD),
        trailingIcon = {
            IconToggleButton(
                checked = visible,
                onCheckedChange = { visible = it },
                shapes = IconButtonDefaults.toggleableShapes(),
                enabled = enabled,
            ) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    stringResource(if (visible) R.string.database_hide_password else R.string.database_show_password),
                )
            }
        },
    )
    NewPasswordField(
        credentials.confirmation,
        R.string.create_database_confirm_password,
        visible,
        enabled = enabled,
        onDone = onDone,
        modifier = Modifier.testTag(TestTags.NEW_CONFIRMATION),
    )
}

@Composable
private fun NewPasswordField(
    state: TextFieldState,
    label: Int,
    visible: Boolean,
    enabled: Boolean,
    /** What the keyboard's Done key does, or null for a Next key that moves to the following field. */
    onDone: (() -> Unit)?,
    modifier: Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedSecureTextField(
        state = state,
        enabled = enabled,
        label = { Text(stringResource(label)) },
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done),
        onKeyboardAction = onDone?.let { done -> KeyboardActionHandler { done() } },
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
    )
}
