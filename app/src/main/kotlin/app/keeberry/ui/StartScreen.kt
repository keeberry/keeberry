package app.keeberry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.keeberry.R
import app.keeberry.RecentDatabases

/** What the start screen shows, kept apart from where it comes from so tests and previews can supply it. */
internal class StartState(
    val recents: List<RecentDatabases.Recent>,
    val checking: Boolean,
    val message: Int?,
    /** The file name of a database just created, to confirm. */
    val created: String?,
)

@Composable
internal fun StartScreen(
    state: StartState,
    onOpenDatabase: () -> Unit,
    onCreateDatabase: () -> Unit,
    onRecent: (RecentDatabases.Recent) -> Unit,
    onRemoveRecent: (RecentDatabases.Recent) -> Unit,
    onDismissMessage: () -> Unit,
    onDismissCreated: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var removing by remember { mutableStateOf<RecentDatabases.Recent?>(null) }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 464.dp).fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // The berry fills about 60% of the drawable, so 112dp shows a ~67dp logo.
                        Icon(
                            painterResource(R.drawable.ic_keeberry),
                            contentDescription = null,
                            Modifier.size(112.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    MediumButton(
                        onClick = onOpenDatabase,
                        text = stringResource(if (state.checking) R.string.checking_file else R.string.open_database),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.checking,
                        icon = R.drawable.ic_folder_open,
                    )
                    MediumButton(
                        onClick = onCreateDatabase,
                        text = stringResource(R.string.create_database),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        enabled = !state.checking,
                        icon = R.drawable.ic_add,
                        outlined = true,
                    )
                    if (state.recents.isNotEmpty()) {
                        RecentList(state.recents, enabled = !state.checking, onRecent, onLongPress = { removing = it })
                    }
                }
            }
            // Settings apply to every database, so they're here before one is opened.
            val settings = stringResource(R.string.settings)
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                WithTooltip(settings) {
                    IconButton(onOpenSettings, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag(TestTags.OPEN_SETTINGS)) {
                        Icon(painterResource(R.drawable.ic_settings), settings)
                    }
                }
            }
        }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(R.string.open_database)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onDismissMessage, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close_dialog)) }
            },
        )
    }
    state.created?.let { name ->
        AlertDialog(
            onDismissRequest = onDismissCreated,
            title = { Text(stringResource(R.string.create_database_done_title)) },
            text = { Text(stringResource(R.string.create_database_done_message, name)) },
            confirmButton = {
                TextButton(onDismissCreated, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close_dialog)) }
            },
        )
    }
    removing?.let { recent ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.recent_remove_title)) },
            text = { Text(stringResource(R.string.recent_remove_message, recent.name)) },
            confirmButton = {
                TextButton(
                    {
                        removing = null
                        onRemoveRecent(recent)
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.recent_remove)) }
            },
            dismissButton = {
                TextButton({ removing = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RecentList(
    recents: List<RecentDatabases.Recent>,
    enabled: Boolean,
    onRecent: (RecentDatabases.Recent) -> Unit,
    onLongPress: (RecentDatabases.Recent) -> Unit,
) {
    Text(
        stringResource(R.string.recent_databases),
        Modifier.padding(top = 32.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val removeLabel = stringResource(R.string.recent_remove)
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        recents.forEachIndexed { index, recent ->
            SegmentedListItem(
                onClick = { onRecent(recent) },
                shapes = ListItemDefaults.segmentedShapes(index, recents.size),
                colors = segmentedListColors(),
                enabled = enabled,
                onLongClick = { onLongPress(recent) },
                onLongClickLabel = removeLabel,
                leadingContent = { Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null) },
            ) {
                Text(
                    recent.name.ifBlank { recent.uri.lastPathSegment.orEmpty() },
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

private val previewRecents = listOf(
    RecentDatabases.Recent("content://preview/personal.kdbx".toUri(), "Personal.kdbx"),
    RecentDatabases.Recent("content://preview/work.kdbx".toUri(), "Work passwords.kdbx"),
    RecentDatabases.Recent("content://preview/family.kdbx".toUri(), "Family.kdbx"),
)

@Composable
private fun StartPreview(
    recents: List<RecentDatabases.Recent> = emptyList(),
    checking: Boolean = false,
    message: Int? = null,
    created: String? = null,
) {
    KeeBerryTheme {
        StartScreen(
            StartState(recents, checking, message, created),
            onOpenDatabase = {},
            onCreateDatabase = {},
            onRecent = {},
            onRemoveRecent = {},
            onDismissMessage = {},
            onDismissCreated = {},
            onOpenSettings = {},
        )
    }
}

@ScreenPreviews
@Composable
private fun StartScreenPreview() = StartPreview()

@Preview(name = "Recent databases", showSystemUi = true)
@Composable
private fun StartRecentsPreview() = StartPreview(recents = previewRecents)

@Preview(name = "Checking a file", showSystemUi = true)
@Composable
private fun StartCheckingPreview() = StartPreview(recents = previewRecents, checking = true)

@Preview(name = "Not a database", showSystemUi = true)
@Composable
private fun StartMessagePreview() = StartPreview(recents = previewRecents, message = R.string.file_not_kdbx)

@Preview(name = "Database created", showSystemUi = true)
@Composable
private fun StartCreatedPreview() = StartPreview(recents = previewRecents, created = "Personal.kdbx")
