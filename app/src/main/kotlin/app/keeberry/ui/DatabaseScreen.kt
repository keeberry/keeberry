package app.keeberry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExpandedDockedSearchBar
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalLayoutDirection
import app.keeberry.AppSettings
import app.keeberry.R
import app.keeberry.DatabaseClipboard
import app.keeberry.DatabaseViewModel
import app.keeberry.EntryEditor
import app.keeberry.MasterKeyChange
import app.keeberry.kdbx.Database
import app.keeberry.kdbx.DatabaseEntry
import app.keeberry.kdbx.DatabaseField

/** What the unlock form shows and holds, kept apart from where it comes from so previews can supply it. */
internal class UnlockState(
    val password: TextFieldState,
    /** Whether the form offers "Use master password", which only matters next to another credential. */
    val offersPassword: Boolean,
    val usePassword: Boolean,
    val showsPassword: Boolean,
    val hasKeyFile: Boolean,
    val keyName: String?,
    val useYubiKey: Boolean,
    val error: Int?,
    val progress: DatabaseViewModel.Progress?,
)

/** What an unlocked database shows. */
internal class BrowserState(
    val database: Database,
    val canEdit: Boolean,
    val query: TextFieldState,
    /** The entry a save just stored, which the screen opens once. */
    val savedEntry: String?,
    /** Whether an entry is being deleted or restored, and why the last attempt failed. */
    val changing: Boolean,
    val changeError: Int?,
    /** Whether the recycle bin is shown in place of the entries. */
    val showingBin: Boolean = false,
)

/**
 * The unlock form until [browser] holds the unlocked database, then its entries, and [editor] over
 * them while an entry is being changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabaseScreen(
    fileName: String?,
    unlock: UnlockState,
    browser: BrowserState?,
    editor: EntryEditor?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onCancelUnlock: () -> Unit,
    onPickKeyFile: () -> Unit,
    onRemoveKeyFile: () -> Unit,
    onUsePasswordChange: (Boolean) -> Unit,
    onUseYubiKeyChange: (Boolean) -> Unit,
    onSavedEntryShown: () -> Unit,
    onNewEntry: () -> Unit,
    onEditEntry: (Int) -> Unit,
    onDeleteEntry: (position: Int, fromBin: Boolean) -> Unit,
    onRestoreEntry: (Int) -> Unit,
    onEmptyBin: () -> Unit,
    onDismissChange: () -> Unit,
    onShowBin: (Boolean) -> Unit,
    onSaveEntry: () -> Unit,
    onDiscardEntry: () -> Unit,
    keyChange: MasterKeyChange? = null,
    keyChanged: Boolean = false,
    keyActions: MasterKeyActions = MasterKeyActions(),
    /** The app's settings, while they cover the open database. */
    settings: SettingsState? = null,
    settingsActions: SettingsActions = SettingsActions(),
) {
    // The list and the open entry keep their place while the entry form or the recycle bin covers them.
    val browserState = rememberSaveableStateHolder()
    if (browser != null) {
        val actions = BrowserActions(
            onBack, onLock, onSavedEntryShown, onNewEntry, onEditEntry, onDeleteEntry, onRestoreEntry, onEmptyBin, onDismissChange,
            onShowBin, keyActions.onStart, settingsActions.onOpen,
        )
        // The bin starts at its list each time it opens.
        LaunchedEffect(browser.showingBin) { if (!browser.showingBin) browserState.removeState("bin") }
        when {
            settings != null -> SettingsScreen(
                settings, settingsActions.onClose, settingsActions.onThemeChange, settingsActions.onFontChange,
                settingsActions.onRecentLimitChange,
            )
            keyChange != null -> ChangeMasterKeyScreen(keyChange, keyActions)
            editor != null -> EntryEditorScreen(editor, onSave = onSaveEntry, onDiscard = onDiscardEntry)
            browser.showingBin -> browserState.SaveableStateProvider("bin") {
                // Registered before the panes' own, so going back from an open entry still closes it first.
                BackHandler { onShowBin(false) }
                DatabaseBrowser(browser, fileName, actions, bin = true)
            }
            else -> browserState.SaveableStateProvider("browser") { DatabaseBrowser(browser, fileName, actions, bin = false) }
        }
        if (keyChanged) {
            AlertDialog(
                onDismissRequest = keyActions.onChangedShown,
                title = { Text(stringResource(R.string.master_key_changed_title)) },
                text = { Text(stringResource(R.string.master_key_changed_message)) },
                confirmButton = {
                    TextButton(keyActions.onChangedShown, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close_dialog)) }
                },
                properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            )
        }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.unlock_database)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        UnlockForm(
            unlock,
            fileName,
            onUnlock = onUnlock,
            onCancel = onCancelUnlock,
            onPickKeyFile = onPickKeyFile,
            onRemoveKeyFile = onRemoveKeyFile,
            onUsePasswordChange = onUsePasswordChange,
            onUseYubiKeyChange = onUseYubiKeyChange,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
        )
    }
}

@Composable
internal fun BackButton(onClick: () -> Unit) {
    IconButton(onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.database_back))
    }
}

@Composable
private fun LockButton(onClick: () -> Unit) {
    TextButton(onClick, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.database_lock)) }
}

@Composable
private fun RecycleBinButton(onClick: () -> Unit) {
    val label = stringResource(R.string.recycle_bin)
    WithTooltip(label) {
        IconButton(onClick, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag(TestTags.RECYCLE_BIN)) {
            Icon(painterResource(R.drawable.ic_delete), label)
        }
    }
}

/** What the entry list and the recycle bin can ask for. */
private class BrowserActions(
    val onBack: () -> Unit,
    val onLock: () -> Unit,
    val onSavedEntryShown: () -> Unit,
    val onNewEntry: () -> Unit,
    val onEditEntry: (Int) -> Unit,
    val onDeleteEntry: (position: Int, fromBin: Boolean) -> Unit,
    val onRestoreEntry: (Int) -> Unit,
    val onEmptyBin: () -> Unit,
    val onDismissChange: () -> Unit,
    val onShowBin: (Boolean) -> Unit,
    val onChangeKey: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/** What the settings screen can ask for while a database is open. */
internal class SettingsActions(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onThemeChange: (AppSettings.Theme) -> Unit = {},
    val onFontChange: (AppSettings.Font) -> Unit = {},
    val onRecentLimitChange: (Int) -> Unit = {},
)

/** What the change-master-key form can ask for. */
internal class MasterKeyActions(
    /** Opens the form. */
    val onStart: () -> Unit = {},
    /** Changes the key to what the form holds. */
    val onChange: () -> Unit = {},
    /** Stops the change under way. */
    val onCancel: () -> Unit = {},
    /** Closes the form. */
    val onDiscard: () -> Unit = {},
    val onPickKeyFile: () -> Unit = {},
    val onNewKeyFile: () -> Unit = {},
    /** Dismisses the note that the key changed. */
    val onChangedShown: () -> Unit = {},
)

@Composable
private fun ChangeMasterKeyScreen(change: MasterKeyChange, actions: MasterKeyActions) {
    // Leaving mid-change stops it if it still can; the form only closes once nothing is under way.
    val back = { if (change.busy) actions.onCancel() else actions.onDiscard() }
    BackHandler(onBack = back)
    NewCredentialsScreen(
        title = R.string.master_key_change,
        description = R.string.master_key_change_description,
        credentials = change.credentials,
        error = change.error,
        progress = change.progress,
        action = R.string.master_key_change,
        busyAction = R.string.master_key_changing,
        actionTag = TestTags.SAVE_MASTER_KEY,
        onBack = back,
        onAction = actions.onChange,
        onCancel = actions.onCancel,
        onPickKeyFile = actions.onPickKeyFile,
        onNewKeyFile = actions.onNewKeyFile,
    )
}

@Composable
private fun MoreOptionsButton(onChangeKey: (() -> Unit)?, onOpenSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label = stringResource(R.string.more_options)
        WithTooltip(label) {
            IconButton({ open = true }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag(TestTags.MORE_OPTIONS)) {
                Icon(painterResource(R.drawable.ic_more_vert), label)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true, securePolicy = SecureFlagPolicy.SecureOn),
        ) {
            if (onChangeKey != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.master_key_change)) },
                    onClick = {
                        open = false
                        onChangeKey()
                    },
                    modifier = Modifier.testTag(TestTags.CHANGE_MASTER_KEY),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings)) },
                onClick = {
                    open = false
                    onOpenSettings()
                },
                modifier = Modifier.testTag(TestTags.OPEN_SETTINGS),
            )
        }
    }
}

/**
 * The entry list and the open entry, or with [bin] the recycle bin's: one pane at a time on phones,
 * side by side on wider windows. The open entry is the navigator's detail destination, which
 * survives configuration changes, and system back, predictive back included, returns from it to the
 * list. Entries in the bin can't be edited, only restored or deleted for good.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun DatabaseBrowser(state: BrowserState, fileName: String?, actions: BrowserActions, bin: Boolean) {
    val database = state.database
    val entries = if (bin) database.binned else database.entries
    val onLock = actions.onLock
    // The scaffold moves focus into the pane it navigates to. That helps keyboard users, but by
    // touch it would focus the search field, which opens the search as soon as the list appears.
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val adaptive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    val directive = remember(adaptive, touch) {
        with(adaptive) {
            PaneScaffoldDirective(
                maxHorizontalPartitions = maxHorizontalPartitions,
                horizontalPartitionSpacerSize = horizontalPartitionSpacerSize,
                maxVerticalPartitions = maxVerticalPartitions,
                verticalPartitionSpacerSize = verticalPartitionSpacerSize,
                defaultPanePreferredWidth = defaultPanePreferredWidth,
                defaultPanePreferredHeight = defaultPanePreferredHeight,
                excludedBounds = excludedBounds,
                shouldAutoFocusCurrentDestination = !touch,
            )
        }
    }
    // Entries are opened by their UUID, since a save can move them in the list.
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = directive)
    val scope = rememberCoroutineScope()
    val value = navigator.scaffoldValue
    val listShown = value[ListDetailPaneScaffoldRole.List] != PaneAdaptedValue.Hidden
    val detailShown = value[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden
    val open = navigator.currentDestination?.contentKey
    // Going back on a phone clears the destination at once, while the pane is still sliding away.
    var lastOpen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) { if (open != null) lastOpen = open }
    val positions = remember(entries) {
        entries.withIndex().associate { (position, entry) -> keyOf(entry, position) to position }
    }
    // A saved entry opens once the list is back. Clearing it restarts this effect, so the navigation
    // runs in the screen's scope: in the effect's own, it would be cancelled mid-transition.
    val saved = state.savedEntry.takeIf { !bin }
    LaunchedEffect(saved) {
        if (saved == null) return@LaunchedEffect
        actions.onSavedEntryShown()
        if (saved != open && saved in positions) {
            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, saved) }
        }
    }
    // A deleted or restored entry closes, back to the list.
    LaunchedEffect(positions) {
        if (open != null && open !in positions) navigator.navigateBack()
    }
    val name = database.name.ifBlank { fileName ?: stringResource(R.string.open_database) }
    val adds = state.canEdit && !bin
    // Entries without a valid UUID can't be changed one by one, but emptying the bin removes them too.
    val empties = state.canEdit && bin && entries.isNotEmpty()
    var confirmingEmpty by rememberSaveable { mutableStateOf(false) }
    // Once the bin is empty, the dialog has done its job.
    LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) confirmingEmpty = false }
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                Scaffold(
                    topBar = {
                        val title = if (bin) stringResource(R.string.recycle_bin) else name
                        // In the bin, the database it belongs to; elsewhere, whether it can be changed.
                        val subtitle = if (bin) name else if (!state.canEdit) stringResource(R.string.database_read_only) else null
                        val heading = @Composable { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        val back = @Composable { BackButton(if (bin) { { actions.onShowBin(false) } } else actions.onBack) }
                        val buttons: @Composable RowScope.() -> Unit = {
                            if (!bin && database.showsRecycleBin) RecycleBinButton { actions.onShowBin(true) }
                            if (empties) {
                                TextButton(
                                    { confirmingEmpty = true },
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = Modifier.testTag(TestTags.EMPTY_BIN),
                                ) { Text(stringResource(R.string.recycle_bin_empty_action)) }
                            }
                            LockButton(onLock)
                            if (!bin) MoreOptionsButton(actions.onChangeKey.takeIf { state.canEdit }, actions.onOpenSettings)
                        }
                        if (subtitle == null) {
                            TopAppBar(title = heading, navigationIcon = back, actions = buttons)
                        } else {
                            TopAppBar(
                                title = heading,
                                subtitle = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                navigationIcon = back,
                                actions = buttons,
                            )
                        }
                    },
                    floatingActionButton = {
                        if (adds) {
                            val label = stringResource(R.string.entry_new)
                            ExtendedFloatingActionButton(
                                onClick = actions.onNewEntry,
                                modifier = Modifier.testTag(TestTags.NEW_ENTRY),
                                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                                text = { Text(label) },
                            )
                        }
                    },
                ) { padding ->
                    EntryList(
                        entries,
                        empty = if (bin) R.string.recycle_bin_empty else R.string.database_empty,
                        query = state.query,
                        // Beside the details, the list shows which entry they belong to.
                        selection = if (detailShown) Selection(open?.let(positions::get)) else null,
                        onOpen = { position ->
                            val key = keyOf(entries[position], position)
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) }
                        },
                        // Beside the details, results drop down under the bar; alone, they fill the window.
                        docked = listShown && detailShown,
                        // Room for the last entry to scroll clear of the navigation bar, and of the
                        // new-entry button, which sits 16dp above it.
                        bottomPadding = padding.calculateBottomPadding() + if (adds) 88.dp else 16.dp,
                        modifier = Modifier.padding(padding.withoutBottom()).consumeWindowInsets(padding).imePadding(),
                    )
                }
            }
        },
        detailPane = {
            AnimatedPane {
                val shown = open ?: lastOpen.takeIf { !detailShown }
                val position = shown?.let(positions::get)
                val entry = position?.let(entries::getOrNull)
                if (shown == null || position == null || entry == null) {
                    Placeholder(R.string.database_no_entry_open)
                } else {
                    // Keyed so a newly opened entry starts with its hidden fields hidden.
                    key(shown) {
                        var confirmingDelete by rememberSaveable { mutableStateOf(false) }
                        var confirmingRestore by rememberSaveable { mutableStateOf(false) }
                        val changeable = state.canEdit && entry.uuid != null
                        EntryDetails(
                            entry,
                            onBack = if (listShown) null else { { scope.launch { navigator.navigateBack() } } },
                            onLock = if (listShown) null else onLock,
                            onEdit = if (changeable && !bin) { { actions.onEditEntry(position) } } else null,
                            onRestore = if (changeable && bin) { { confirmingRestore = true } } else null,
                            onDelete = if (changeable) { { confirmingDelete = true } } else null,
                            deleteLabel = if (bin) R.string.entry_delete_forever_action else R.string.entry_delete_action,
                        )
                        val title = titleOf(entry)
                        val dismiss = {
                            confirmingDelete = false
                            confirmingRestore = false
                            actions.onDismissChange()
                        }
                        if (confirmingDelete) {
                            // From the bin, or with the bin turned off, an entry is deleted for good.
                            val toBin = !bin && database.recycleBin
                            ChangeDialog(
                                title = R.string.entry_delete_title,
                                message = stringResource(if (toBin) R.string.entry_delete_to_bin else R.string.entry_delete_for_good, title),
                                confirm = R.string.entry_delete,
                                progress = R.string.entry_deleting,
                                confirmTag = TestTags.CONFIRM_DELETE,
                                busy = state.changing,
                                error = state.changeError,
                                onConfirm = { actions.onDeleteEntry(position, bin) },
                                onDismiss = dismiss,
                            )
                        }
                        if (confirmingRestore) {
                            ChangeDialog(
                                title = R.string.entry_restore_title,
                                message = if (entry.group.isEmpty()) stringResource(R.string.entry_restore_to_root, title)
                                else stringResource(R.string.entry_restore_to_group, title, entry.group),
                                confirm = R.string.entry_restore,
                                progress = R.string.entry_restoring,
                                confirmTag = TestTags.CONFIRM_RESTORE,
                                busy = state.changing,
                                error = state.changeError,
                                onConfirm = { actions.onRestoreEntry(position) },
                                onDismiss = dismiss,
                            )
                        }
                    }
                }
            }
        },
    )
    if (confirmingEmpty && empties) {
        ChangeDialog(
            title = R.string.recycle_bin_empty_title,
            message = pluralStringResource(R.plurals.recycle_bin_empty_message, entries.size, entries.size),
            confirm = R.string.recycle_bin_empty_action,
            progress = R.string.recycle_bin_emptying,
            confirmTag = TestTags.CONFIRM_EMPTY_BIN,
            busy = state.changing,
            error = state.changeError,
            onConfirm = actions.onEmptyBin,
            onDismiss = {
                confirmingEmpty = false
                actions.onDismissChange()
            },
        )
    }
}

/** Which entry the list marks as open, when the details are beside it. */
private class Selection(val position: Int?)

/**
 * A scaffold's padding without its bottom. Lists take that as content padding instead, so they
 * scroll under the transparent navigation bar rather than stopping above it.
 */
@Composable
private fun PaddingValues.withoutBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = calculateStartPadding(direction), top = calculateTopPadding(), end = calculateEndPadding(direction))
}

/** How the navigator names an entry: its UUID, or its place for the rare entry without one. */
private fun keyOf(entry: DatabaseEntry, position: Int): String = entry.key ?: "#$position"

@Composable
private fun Placeholder(text: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnlockForm(
    state: UnlockState,
    fileName: String?,
    onUnlock: () -> Unit,
    onCancel: () -> Unit,
    onPickKeyFile: () -> Unit,
    onRemoveKeyFile: () -> Unit,
    onUsePasswordChange: (Boolean) -> Unit,
    onUseYubiKeyChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val progress = state.progress
    val unlocking = progress != null
    BoxWithConstraints(modifier.fillMaxSize()) {
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
                if (!fileName.isNullOrBlank()) {
                    Text(
                        fileName,
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (state.offersPassword) {
                    CheckboxRow(R.string.database_use_password, state.usePassword, !unlocking, onUsePasswordChange)
                }
                if (state.showsPassword) PasswordField(state.password, enabled = !unlocking, onUnlock)
                KeyFileChoice(state.keyName, state.hasKeyFile, enabled = !unlocking, onPickKeyFile, onRemoveKeyFile)
                CheckboxRow(R.string.database_use_yubikey, state.useYubiKey, !unlocking, onUseYubiKeyChange)
                state.error?.let {
                    Text(
                        stringResource(it),
                        Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                MediumButton(
                    onClick = onUnlock,
                    text = stringResource(if (unlocking) R.string.database_unlocking else R.string.unlock_database),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(TestTags.UNLOCK),
                    enabled = !unlocking,
                )
                // Below the button, so nothing the user just tapped moves when it appears.
                if (progress != null) TaskProgress(progress, onCancel)
            }
        }
    }
}

@Composable
private fun PasswordField(password: TextFieldState, enabled: Boolean, onUnlock: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = password,
        enabled = enabled,
        label = { Text(stringResource(R.string.database_password)) },
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = { onUnlock() },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(TestTags.PASSWORD),
    )
}

/** A slow step under way, such as unlocking or creating a database, with a way to cancel it. */
@Composable
internal fun TaskProgress(progress: DatabaseViewModel.Progress, onCancel: () -> Unit) {
    val status = stringResource(progress.status)
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                status,
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            progress.percent?.let {
                // The indicator announces the value, so this is only for sighted users.
                Text(
                    stringResource(R.string.database_unlock_percent, it),
                    Modifier.padding(start = 12.dp).clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                )
            }
        }
        val indicator = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = status }
        val percent = progress.percent
        if (percent == null) LinearWavyProgressIndicator(indicator)
        else LinearWavyProgressIndicator(progress = { percent / 100f }, modifier = indicator)
        TextButton(onCancel, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
            Text(stringResource(R.string.database_unlock_cancel))
        }
    }
}

/**
 * Every entry, under a search bar. Tapping the bar opens the search view, whose results narrow as
 * the user types; choosing one opens it and closes the search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryList(
    entries: List<DatabaseEntry>,
    empty: Int,
    query: TextFieldState,
    selection: Selection?,
    onOpen: (Int) -> Unit,
    docked: Boolean,
    bottomPadding: Dp,
    modifier: Modifier,
) {
    if (entries.isEmpty()) {
        Placeholder(empty, modifier)
        return
    }
    val search = rememberSearchBarState()
    val scope = rememberCoroutineScope()
    val open = { position: Int ->
        scope.launch { search.animateToCollapsed() }
        onOpen(position)
    }
    // Each search starts afresh, so the bar never shows a query the list behind it ignores.
    LaunchedEffect(search.currentValue) {
        if (search.currentValue == SearchBarValue.Collapsed) query.clearText()
    }
    // The docked results have a fixed width of their own; matching the bar keeps them one shape.
    var barWidth by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current
    Column(modifier.fillMaxSize()) {
        SearchBar(
            state = search,
            inputField = { SearchField(query, search, Modifier.testTag(TestTags.SEARCH)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onSizeChanged { barWidth = with(density) { it.width.toDp() } },
        )
        EntryItems(entries, entries.indices.toList(), selection, open, bottomPadding = bottomPadding)
    }
    val field = @Composable { SearchField(query, search, Modifier.testTag(TestTags.SEARCH_INPUT)) }
    // Both open in a window of their own, which needs the screen's screenshot protection.
    if (docked) {
        ExpandedDockedSearchBar(
            state = search,
            inputField = field,
            modifier = if (barWidth == Dp.Unspecified) Modifier else Modifier.width(barWidth),
            properties = PopupProperties(focusable = true, clippingEnabled = false, securePolicy = SecureFlagPolicy.SecureOn),
        ) { SearchResults(query.text.toString(), entries, selection, open, bottomInset = 0.dp) }
    } else {
        ExpandedFullScreenSearchBar(
            state = search,
            inputField = field,
            // The results take the bottom inset themselves, to scroll under the navigation bar.
            windowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top) },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        ) {
            // The keyboard's height when it's open, which includes the navigation bar.
            val inset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
            SearchResults(query.text.toString(), entries, selection, open, bottomInset = inset)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: TextFieldState, search: SearchBarState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    SearchBarDefaults.InputField(
        textFieldState = query,
        searchBarState = search,
        // Results already follow the query, so searching only makes room for them.
        onSearch = { keyboard?.hide() },
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.database_search)) },
        leadingIcon = {
            if (search.currentValue == SearchBarValue.Expanded) {
                BackButton { scope.launch { search.animateToCollapsed() } }
            } else {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null)
            }
        },
        trailingIcon = if (query.text.isEmpty()) null else {
            {
                IconButton({ query.clearText() }, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.database_clear_search))
                }
            }
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
}

@Composable
private fun SearchResults(
    query: String,
    entries: List<DatabaseEntry>,
    selection: Selection?,
    onOpen: (Int) -> Unit,
    bottomInset: Dp,
) {
    val index = remember(entries) { entries.map(::searchTextOf) }
    val words = query.lowercase().split(' ').filter(String::isNotBlank)
    // Every word has to appear somewhere in the entry, in any order. Positions map to entries, so
    // results keep the database's order.
    val visible = remember(entries, words) {
        entries.indices.filter { entry -> words.all { index[entry].contains(it) } }
    }
    // Centred in the space the keyboard leaves, not behind it.
    if (visible.isEmpty()) Placeholder(R.string.database_no_matches, Modifier.padding(bottom = bottomInset))
    else EntryItems(entries, visible, selection, onOpen, Modifier.testTag(TestTags.SEARCH_RESULTS), bottomInset + 16.dp)
}

@Composable
private fun EntryItems(
    entries: List<DatabaseEntry>,
    positions: List<Int>,
    selection: Selection?,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 16.dp,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(positions, key = { _, position -> position }) { index, position ->
            val entry = entries[position]
            val open = { onOpen(position) }
            val shapes = ListItemDefaults.segmentedShapes(index, positions.size)
            val supporting: (@Composable () -> Unit)? = if (entry.group.isEmpty()) null else {
                { Text(entry.group, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            val title = @Composable { Text(titleOf(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            // Only a list beside the details is a selection; alone, its items just open.
            if (selection == null) {
                SegmentedListItem(
                    onClick = open,
                    shapes = shapes,
                    colors = segmentedListColors(),
                    supportingContent = supporting,
                    content = title,
                )
            } else {
                SegmentedListItem(
                    selected = position == selection.position,
                    onClick = open,
                    shapes = shapes,
                    colors = segmentedListColors(),
                    supportingContent = supporting,
                    content = title,
                )
            }
        }
    }
}

/**
 * An open entry. In a pane rather than a dialog, it has room for long notes and many custom fields,
 * and it stays inside the activity's window, so the screenshot protection covers it.
 * [onBack] and [onLock] are given when the details fill the window and the list's bar is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDetails(
    entry: DatabaseEntry,
    onBack: (() -> Unit)?,
    onLock: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    deleteLabel: Int,
) {
    // Standard fields first in the order KeePass shows them, then custom fields as stored.
    val fields = remember(entry) {
        entry.fields.filter { it.name != "Title" }
            .sortedBy { field -> STANDARD_FIELDS.indexOf(field.name).let { if (it < 0) STANDARD_FIELDS.size else it } }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(titleOf(entry), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                subtitle = if (entry.group.isEmpty()) null else {
                    { Text(entry.group, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                navigationIcon = { onBack?.let { BackButton(it) } },
                actions = {
                    onEdit?.let {
                        val edit = stringResource(R.string.entry_edit_action)
                        WithTooltip(edit) {
                            IconButton(it, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag(TestTags.EDIT_ENTRY)) {
                                Icon(painterResource(R.drawable.ic_edit), edit)
                            }
                        }
                    }
                    onRestore?.let {
                        val restore = stringResource(R.string.entry_restore_action)
                        WithTooltip(restore) {
                            IconButton(it, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag(TestTags.RESTORE_ENTRY)) {
                                Icon(painterResource(R.drawable.ic_restore), restore)
                            }
                        }
                    }
                    onDelete?.let {
                        val delete = stringResource(deleteLabel)
                        WithTooltip(delete) {
                            IconButton(it, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag(TestTags.DELETE_ENTRY)) {
                                Icon(painterResource(R.drawable.ic_delete), delete)
                            }
                        }
                    }
                    onLock?.let { LockButton(it) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding.withoutBottom()).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            // KeePass keeps field names unique within an entry.
            itemsIndexed(fields, key = { _, field -> field.name }) { index, field ->
                FieldItem(field, ListItemDefaults.segmentedShapes(index, fields.size))
            }
        }
    }
}

/**
 * Confirms deleting or restoring an entry and stays open while it runs, showing why it failed if it
 * does. Once the entry has left the list, the details close and the dialog with them.
 */
@Composable
private fun ChangeDialog(
    title: Int,
    message: String,
    confirm: Int,
    progress: Int,
    confirmTag: String,
    busy: Boolean,
    error: Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(message)
                if (busy) {
                    val status = stringResource(progress)
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp).semantics { contentDescription = status })
                }
                error?.let {
                    Text(
                        stringResource(it),
                        Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onConfirm,
                enabled = !busy,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag(confirmTag),
            ) { Text(stringResource(if (busy) progress else confirm)) }
        },
        dismissButton = {
            TextButton(onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
        },
        // A dialog has a window of its own, which needs the screen's screenshot protection.
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

@Composable
private fun FieldItem(field: DatabaseField, shapes: ListItemShapes) {
    val context = LocalContext.current
    val label = labelOf(field.name)
    // Each hidden field is revealed on its own, so showing one secret doesn't expose the rest.
    var revealed by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        shapes = shapes,
        colors = segmentedListColors(),
        overlineContent = { Text(label) },
        trailingContent = {
            Row {
                if (field.sensitive) {
                    val toggle = stringResource(if (revealed) R.string.database_hide_field else R.string.database_show_field, label)
                    WithTooltip(toggle) {
                        IconToggleButton(revealed, { revealed = it }, shapes = IconButtonDefaults.toggleableShapes()) {
                            Icon(painterResource(if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility), toggle)
                        }
                    }
                }
                val copy = stringResource(R.string.database_copy_field, label)
                WithTooltip(copy) {
                    // Copying a hidden field doesn't reveal it on screen.
                    IconButton({ DatabaseClipboard.copy(context, label, field.text(), field.sensitive) }, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_copy), copy)
                    }
                }
            }
        },
    ) {
        when {
            field.sensitive && !revealed -> {
                val hidden = stringResource(R.string.database_hidden)
                Text(stringResource(R.string.database_hidden_value), Modifier.clearAndSetSemantics { contentDescription = hidden })
            }
            // Text selection would copy without the sensitive flag or the timed clear, so secrets
            // go to the clipboard only through the copy button.
            field.sensitive -> Text(displayValue(field))
            else -> SelectionContainer { Text(displayValue(field)) }
        }
    }
}

/** Names an icon-only button for sighted users, as its content description does for TalkBack. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

@Composable
private fun displayValue(field: DatabaseField): String {
    val value = field.text()
    return if (value.length > 16_384) stringResource(R.string.database_truncated_field, value.take(16_384)) else value
}

@Composable
private fun titleOf(entry: DatabaseEntry): String =
    entry.title.take(256).ifBlank { stringResource(R.string.database_untitled_entry) }

@Composable
private fun labelOf(name: String): String = when (name) {
    "UserName" -> stringResource(R.string.field_username)
    "Password" -> stringResource(R.string.field_password)
    "URL" -> stringResource(R.string.field_url)
    "Notes" -> stringResource(R.string.field_notes)
    else -> name
}

/** What a search matches: the title, group and visible standard fields, never protected ones. */
private fun searchTextOf(entry: DatabaseEntry): String = buildString {
    append(entry.title).append('\n').append(entry.group)
    for (field in entry.fields) {
        if (!field.sensitive && field.name in SEARCHED_FIELDS) append('\n').append(field.text().take(4096))
    }
}.lowercase()

private val STANDARD_FIELDS = listOf("UserName", "Password", "URL", "Notes")
private val SEARCHED_FIELDS = setOf("UserName", "URL", "Notes")

private fun previewUnlock(
    hasKeyFile: Boolean = false,
    useYubiKey: Boolean = false,
    error: Int? = null,
    progress: DatabaseViewModel.Progress? = null,
) = UnlockState(
    password = TextFieldState(),
    // As the view model has it: the password is optional only next to another credential.
    offersPassword = hasKeyFile || useYubiKey,
    usePassword = false,
    showsPassword = !hasKeyFile && !useYubiKey,
    hasKeyFile = hasKeyFile,
    keyName = if (hasKeyFile) "personal.keyx" else null,
    useYubiKey = useYubiKey,
    error = error,
    progress = progress,
)

private fun previewBrowser(
    entries: Boolean = true,
    canEdit: Boolean = true,
    savedEntry: String? = null,
    showingBin: Boolean = false,
) = BrowserState(
    database = previewDatabase(entries),
    canEdit = canEdit,
    query = TextFieldState(),
    savedEntry = savedEntry,
    changing = false,
    changeError = null,
    showingBin = showingBin,
)

@Composable
private fun DatabasePreview(unlock: UnlockState = previewUnlock(), browser: BrowserState? = null) {
    KeeBerryTheme {
        DatabaseScreen(
            fileName = "Personal.kdbx",
            unlock = unlock,
            browser = browser,
            editor = null,
            onBack = {},
            onLock = {},
            onUnlock = {},
            onCancelUnlock = {},
            onPickKeyFile = {},
            onRemoveKeyFile = {},
            onUsePasswordChange = {},
            onUseYubiKeyChange = {},
            onSavedEntryShown = {},
            onNewEntry = {},
            onEditEntry = {},
            onDeleteEntry = { _, _ -> },
            onRestoreEntry = {},
            onEmptyBin = {},
            onDismissChange = {},
            onShowBin = {},
            onSaveEntry = {},
            onDiscardEntry = {},
        )
    }
}

@ScreenPreviews
@Composable
private fun UnlockPreview() = DatabasePreview()

@Preview(name = "Wrong password", showSystemUi = true)
@Composable
private fun UnlockErrorPreview() = DatabasePreview(previewUnlock(error = R.string.database_credentials_error))

@Preview(name = "Key file and YubiKey", showSystemUi = true)
@Composable
private fun UnlockKeyFilePreview() = DatabasePreview(previewUnlock(hasKeyFile = true, useYubiKey = true))

@Preview(name = "Unlocking", showSystemUi = true)
@Composable
private fun UnlockingPreview() =
    DatabasePreview(previewUnlock(progress = DatabaseViewModel.Progress(R.string.database_unlock_deriving, 42)))

@ScreenPreviews
@Composable
private fun EntryListPreview() = DatabasePreview(browser = previewBrowser())

@Preview(name = "Read only", showSystemUi = true)
@Composable
private fun EntryListReadOnlyPreview() = DatabasePreview(browser = previewBrowser(canEdit = false))

@Preview(name = "Recycle bin", showSystemUi = true)
@Composable
private fun RecycleBinPreview() = DatabasePreview(browser = previewBrowser(showingBin = true))

@Preview(name = "No entries", showSystemUi = true)
@Composable
private fun EntryListEmptyPreview() = DatabasePreview(browser = previewBrowser(entries = false))

// The screen opens the entry from an effect, which a static preview may not run: use interactive mode
// if it shows the list.
@Preview(name = "Entry details", showSystemUi = true)
@Composable
private fun EntryDetailsPreview() = DatabasePreview(browser = previewBrowser(savedEntry = PREVIEW_BANK_ENTRY))

@Preview(name = "List and details, tablet", showSystemUi = true, device = Devices.TABLET)
@Composable
private fun EntryListDetailPreview() = DatabasePreview(browser = previewBrowser(savedEntry = PREVIEW_BANK_ENTRY))
