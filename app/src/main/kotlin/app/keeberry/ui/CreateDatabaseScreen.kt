package app.keeberry.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.keeberry.DatabaseViewModel
import app.keeberry.NewCredentials
import app.keeberry.R

/** What the new-database form shows and holds, kept apart from where it comes from so previews can supply it. */
internal class CreateDatabaseState(
    val name: TextFieldState,
    val credentials: NewCredentials,
    val error: Int?,
    /** The write under way, if any. */
    val progress: DatabaseViewModel.Progress?,
)

@Composable
internal fun CreateDatabaseScreen(
    state: CreateDatabaseState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    onPickKeyFile: () -> Unit,
    onNewKeyFile: () -> Unit,
) {
    NewCredentialsScreen(
        title = R.string.create_database,
        description = R.string.create_database_description,
        credentials = state.credentials,
        error = state.error,
        progress = state.progress,
        action = R.string.create_database,
        busyAction = R.string.create_database_creating,
        actionTag = TestTags.CREATE,
        onBack = onBack,
        onAction = onCreate,
        onCancel = onCancel,
        onPickKeyFile = onPickKeyFile,
        onNewKeyFile = onNewKeyFile,
    ) { enabled ->
        OutlinedTextField(
            state = state.name,
            enabled = enabled,
            label = { Text(stringResource(R.string.create_database_name)) },
            placeholder = { Text(stringResource(R.string.create_database_default_name)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag(TestTags.NEW_NAME),
        )
    }
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
) = CreateDatabaseState(TextFieldState(name), previewCredentials(password, confirmation, usePassword, hasKeyFile, useYubiKey), error, progress)

internal fun previewCredentials(
    password: String = "",
    confirmation: String = "",
    usePassword: Boolean = true,
    hasKeyFile: Boolean = false,
    useYubiKey: Boolean = false,
) = NewCredentials().apply {
    this.password.setTextAndPlaceCursorAtEnd(password)
    this.confirmation.setTextAndPlaceCursorAtEnd(confirmation)
    if (hasKeyFile) setKeyFile(Uri.EMPTY, "Personal.keyx")
    if (useYubiKey) onUseYubiKeyChanged(true)
    if (!usePassword) onUsePasswordChanged(false)
}

@Composable
private fun CreateDatabasePreview(state: CreateDatabaseState = previewCreateState()) {
    KeeBerryTheme { CreateDatabaseScreen(state, onBack = {}, onCreate = {}, onCancel = {}, onPickKeyFile = {}, onNewKeyFile = {}) }
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
