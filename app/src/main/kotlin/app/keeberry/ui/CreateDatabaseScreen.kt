package app.keeberry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.keeberry.DatabaseViewModel
import app.keeberry.R

/** What the new-database form shows and holds, kept apart from where it comes from so previews can supply it. */
internal class CreateDatabaseState(
    val name: TextFieldState,
    val password: TextFieldState,
    val confirmation: TextFieldState,
    /** Whether the form offers "Use master password", which only matters next to another credential. */
    val offersPassword: Boolean,
    val usePassword: Boolean,
    val showsPassword: Boolean,
    val hasKeyFile: Boolean,
    val keyName: String?,
    val useYubiKey: Boolean,
    val slotOne: Boolean,
    val error: Int?,
    /** The write under way, if any. */
    val progress: DatabaseViewModel.Progress?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateDatabaseScreen(
    state: CreateDatabaseState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    onPickKeyFile: () -> Unit,
    onNewKeyFile: () -> Unit,
    onRemoveKeyFile: () -> Unit,
    onUsePasswordChange: (Boolean) -> Unit,
    onUseYubiKeyChange: (Boolean) -> Unit,
    onSlotOneChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_database)) },
                navigationIcon = {
                    IconButton(onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.database_back))
                    }
                },
            )
        },
    ) { padding ->
        val progress = state.progress
        val creating = progress != null
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
                        stringResource(R.string.create_database_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        state = state.name,
                        enabled = !creating,
                        label = { Text(stringResource(R.string.create_database_name)) },
                        placeholder = { Text(stringResource(R.string.create_database_default_name)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag(TestTags.NEW_NAME),
                    )
                    if (state.offersPassword) {
                        CheckboxRow(R.string.database_use_password, state.usePassword, !creating, onUsePasswordChange)
                    }
                    if (state.showsPassword) NewPasswordFields(state, enabled = !creating, onCreate)
                    KeyFileChoice(
                        state.keyName,
                        state.hasKeyFile,
                        enabled = !creating,
                        onPick = onPickKeyFile,
                        onRemove = onRemoveKeyFile,
                        onNew = onNewKeyFile,
                        note = R.string.create_database_key_file_warning,
                    )
                    CheckboxRow(
                        R.string.database_use_yubikey,
                        state.useYubiKey,
                        !creating,
                        onUseYubiKeyChange,
                        Modifier.testTag(TestTags.USE_YUBIKEY),
                    )
                    if (state.useYubiKey) {
                        SlotChoice(state.slotOne, enabled = !creating, onSlotOneChange)
                        Hint(R.string.create_database_yubikey_hint)
                    }
                    state.error?.let {
                        Text(
                            stringResource(it),
                            Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    MediumButton(
                        onClick = onCreate,
                        text = stringResource(if (creating) R.string.create_database_creating else R.string.create_database),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(TestTags.CREATE),
                        enabled = !creating,
                    )
                    // Below the button, so nothing the user just tapped moves when it appears.
                    if (progress != null) TaskProgress(progress, onCancel)
                }
            }
        }
    }
}

/** The master password and its confirmation, which share one show/hide toggle. */
@Composable
private fun NewPasswordFields(state: CreateDatabaseState, enabled: Boolean, onCreate: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    NewPasswordField(
        state.password,
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
        state.confirmation,
        R.string.create_database_confirm_password,
        visible,
        enabled = enabled,
        onDone = onCreate,
        modifier = Modifier.testTag(TestTags.NEW_CONFIRMATION),
    )
}

/** What the YubiKey means for opening the database later. */
@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun previewCreateState(
    name: String = "",
    password: String = "",
    confirmation: String = "",
    usePassword: Boolean = true,
    hasKeyFile: Boolean = false,
    useYubiKey: Boolean = false,
    error: Int? = null,
    progress: DatabaseViewModel.Progress? = null,
) = CreateDatabaseState(
    name = TextFieldState(name),
    password = TextFieldState(password),
    confirmation = TextFieldState(confirmation),
    offersPassword = hasKeyFile || useYubiKey,
    usePassword = usePassword,
    showsPassword = usePassword || !(hasKeyFile || useYubiKey),
    hasKeyFile = hasKeyFile,
    keyName = if (hasKeyFile) "Personal.keyx" else null,
    useYubiKey = useYubiKey,
    slotOne = false,
    error = error,
    progress = progress,
)

@Composable
private fun CreateDatabasePreview(state: CreateDatabaseState = previewCreateState()) {
    KeeBerryTheme {
        CreateDatabaseScreen(
            state,
            onBack = {},
            onCreate = {},
            onCancel = {},
            onPickKeyFile = {},
            onNewKeyFile = {},
            onRemoveKeyFile = {},
            onUsePasswordChange = {},
            onUseYubiKeyChange = {},
            onSlotOneChange = {},
        )
    }
}

@ScreenPreviews
@Composable
private fun CreateDatabaseScreenPreview() = CreateDatabasePreview()

@Preview(name = "Passwords don't match", showSystemUi = true)
@Composable
private fun CreateDatabaseMismatchPreview() = CreateDatabasePreview(
    previewCreateState(
        name = "Personal",
        password = "correct horse",
        confirmation = "correct hrose",
        error = R.string.create_database_password_mismatch,
    ),
)

@Preview(name = "Key file and YubiKey", showSystemUi = true)
@Composable
private fun CreateDatabaseKeyFilePreview() = CreateDatabasePreview(
    previewCreateState(name = "Personal", usePassword = false, hasKeyFile = true, useYubiKey = true),
)

@Preview(name = "Creating", showSystemUi = true)
@Composable
private fun CreateDatabaseProgressPreview() = CreateDatabasePreview(
    previewCreateState(name = "Personal", progress = DatabaseViewModel.Progress(R.string.database_unlock_deriving, 64)),
)
