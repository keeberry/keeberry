package app.keeberry.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.keeberry.AppSettings
import app.keeberry.R
import kotlin.math.roundToInt

/** What the settings screen shows, kept apart from where it comes from so tests and previews can supply it. */
internal class SettingsState(
    val theme: AppSettings.Theme,
    val font: AppSettings.Font,
    val recentLimit: Int,
    /** Seconds an open database stays unlocked in the background, one of [AppSettings.LOCK_TIMEOUTS]. */
    val lockTimeout: Int = 0,
    /** How many databases biometric unlock is on for. */
    val deviceUnlockCount: Int = 0,
)

/** Settings' own pages, which open from a row on the main one. */
private enum class SettingsPage { DARK_THEME, FONT, LOCK_TIMEOUT }

/**
 * App-wide settings, reachable from the start screen and from an open database. Settings are grouped
 * into titled sections of segmented list items, under a large app bar that collapses as it scrolls,
 * and laid out like Android's own settings: bright groups on a container-colored screen. The dark
 * mode and font rows open pages of their own, as they do in the system's display settings.
 */
@Composable
internal fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onThemeChange: (AppSettings.Theme) -> Unit,
    onFontChange: (AppSettings.Font) -> Unit,
    onRecentLimitChange: (Int) -> Unit,
    onLockTimeoutChange: (Int) -> Unit = {},
    onForgetDeviceUnlock: () -> Unit = {},
) {
    // Saved, so the page is still open after a theme change recreates the screen.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val closePage = { page = null }
    when (page) {
        SettingsPage.DARK_THEME -> return DarkThemeScreen(state.theme, onBack = closePage, onThemeChange)
        SettingsPage.FONT -> return FontScreen(state.font, onBack = closePage, onFontChange)
        SettingsPage.LOCK_TIMEOUT -> return LockTimeoutScreen(state.lockTimeout, onBack = closePage, onLockTimeoutChange)
        null -> {}
    }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.device_unlock_forget_title)) },
            text = { Text(stringResource(R.string.device_unlock_forget_message)) },
            confirmButton = {
                TextButton(
                    {
                        confirmForget = false
                        onForgetDeviceUnlock()
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.testTag(TestTags.CONFIRM_FORGET_DEVICE_UNLOCK),
                ) { Text(stringResource(R.string.device_unlock_forget)) }
            },
            dismissButton = {
                TextButton({ confirmForget = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        )
    }
    SettingsScaffold(R.string.settings, onBack, Modifier.testTag(TestTags.SETTINGS)) {
        item {
            SettingsSection(R.string.settings_appearance) {
                DarkModeItem(state.theme, onOpen = { page = SettingsPage.DARK_THEME }, onThemeChange)
                SettingsItem(
                    headline = stringResource(R.string.font),
                    value = stringResource(state.font.label),
                    onClick = { page = SettingsPage.FONT },
                    index = 1,
                    count = 2,
                    modifier = Modifier.testTag(TestTags.FONT),
                )
            }
        }
        item {
            SettingsSection(R.string.settings_security) {
                SettingsItem(
                    headline = stringResource(R.string.lock_timeout),
                    value = lockTimeoutLabel(state.lockTimeout),
                    onClick = { page = SettingsPage.LOCK_TIMEOUT },
                    count = 2,
                    modifier = Modifier.testTag(TestTags.LOCK_TIMEOUT),
                    leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, Modifier.size(18.dp)) },
                )
                val count = state.deviceUnlockCount
                SettingsItem(
                    headline = stringResource(R.string.device_unlock),
                    value = if (count == 0) stringResource(R.string.device_unlock_none)
                    else pluralStringResource(R.plurals.device_unlock_count, count, count),
                    onClick = if (count == 0) null else ({ confirmForget = true }),
                    index = 1,
                    count = 2,
                    modifier = Modifier.testTag(TestTags.FORGET_DEVICE_UNLOCK),
                    leadingContent = { Icon(painterResource(R.drawable.ic_fingerprint), contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection(R.string.recent_databases) {
                RecentLimitItem(state.recentLimit, onRecentLimitChange)
            }
        }
    }
}

@Composable
private fun lockTimeoutLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.lock_timeout_immediately)
    seconds < 60 -> pluralStringResource(R.plurals.lock_timeout_seconds, seconds, seconds)
    else -> pluralStringResource(R.plurals.lock_timeout_minutes, seconds / 60, seconds / 60)
}

/** How long an open database stays unlocked once KeeBerry leaves the screen, as radio preferences. */
@Composable
private fun LockTimeoutScreen(timeout: Int, onBack: () -> Unit, onChange: (Int) -> Unit) {
    val options = AppSettings.LOCK_TIMEOUTS
    SettingsScaffold(R.string.lock_timeout, onBack, Modifier.testTag(TestTags.LOCK_TIMEOUT_PAGE)) {
        item {
            SettingsSection(R.string.theme_options, Modifier.selectableGroup()) {
                options.forEachIndexed { index, option ->
                    SettingsItem(
                        headline = lockTimeoutLabel(option),
                        onClick = { if (option != timeout) onChange(option) },
                        index = index,
                        count = options.size,
                        selected = option == timeout,
                        modifier = Modifier.testTag(TestTags.lockTimeoutOption(option)),
                        leadingContent = { RadioButton(selected = option == timeout, onClick = null) },
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.lock_timeout_note),
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A settings page: a large app bar that collapses as the list under it scrolls. */
@Composable
private fun SettingsScaffold(title: Int, onBack: () -> Unit, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    BackHandler(onBack = onBack)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val direction = LocalLayoutDirection.current
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scroll,
            )
        },
        containerColor = background,
    ) { padding ->
        LazyColumn(
            modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = padding.calculateEndPadding(direction) + 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * A titled group of settings, like AOSP's expressive preference category: the title sits 24.dp from
 * the screen's edge, 8.dp in from its group. Wide windows keep the group to a readable width.
 */
@Composable
private fun SettingsSection(title: Int?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.widthIn(max = 640.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (title != null) {
            Text(
                stringResource(title),
                Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp - ListItemDefaults.SegmentedGap)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        content()
    }
}

/** Theme choices in the order Android's dark theme options list them. */
private val THEME_OPTIONS = listOf(AppSettings.Theme.LIGHT, AppSettings.Theme.DARK, AppSettings.Theme.SYSTEM)

private val AppSettings.Theme.label
    get() = when (this) {
        AppSettings.Theme.LIGHT -> R.string.theme_off
        AppSettings.Theme.DARK -> R.string.theme_on
        AppSettings.Theme.SYSTEM -> R.string.theme_device
    }

/**
 * AOSP's two-target preference: the row opens the dark theme page, and the switch, set apart by a
 * divider, turns dark mode on or off right there. The switch shows the theme in use, so while
 * KeeBerry uses the device theme it shows the device's.
 */
@Composable
private fun DarkModeItem(theme: AppSettings.Theme, onOpen: () -> Unit, onChange: (AppSettings.Theme) -> Unit) {
    val dark = isSystemInDarkTheme()
    val title = stringResource(R.string.dark_mode)
    // ListItem shrinks the minimum touch size of trailing content by the row's padding, but here
    // that padding belongs to the row's own target, so the switch keeps the full minimum size.
    val minimumTouchSize = LocalMinimumInteractiveComponentSize.current
    val switchInteractions = remember { MutableInteractionSource() }
    SettingsItem(
        headline = title,
        value = stringResource(theme.label),
        onClick = onOpen,
        count = 2,
        modifier = Modifier.testTag(TestTags.DARK_THEME),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, Modifier.size(18.dp))
                VerticalDivider(
                    Modifier.padding(start = 8.dp, end = 12.dp).height(40.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides minimumTouchSize) {
                    // A separate focusable control, as in TwoTargetSwitchPreference. The toggle wraps
                    // the minimum size so its touch and accessibility bounds cover the whole target,
                    // not only the switch track drawn inside it.
                    Box(
                        Modifier
                            .semantics { contentDescription = title }
                            .toggleable(
                                value = dark,
                                onValueChange = { onChange(if (it) AppSettings.Theme.DARK else AppSettings.Theme.LIGHT) },
                                role = Role.Switch,
                                interactionSource = switchInteractions,
                                indication = null,
                            )
                            .minimumInteractiveComponentSize()
                            .testTag(TestTags.DARK_THEME_SWITCH),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Shares the interactions so the thumb and focus ring still respond.
                        Switch(
                            checked = dark,
                            onCheckedChange = null,
                            interactionSource = switchInteractions,
                            // AOSP's expressive switch uses primary for the check mark on onPrimary.
                            colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
                            thumbContent = {
                                Icon(
                                    painterResource(if (dark) R.drawable.ic_check else R.drawable.ic_switch_off),
                                    contentDescription = null,
                                    Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}

/** The dark theme page: its options as radio preferences, each row one target for touch and TalkBack. */
@Composable
private fun DarkThemeScreen(theme: AppSettings.Theme, onBack: () -> Unit, onChange: (AppSettings.Theme) -> Unit) {
    SettingsScaffold(R.string.dark_theme, onBack, Modifier.testTag(TestTags.DARK_THEME_PAGE)) {
        item {
            SettingsSection(R.string.theme_options, Modifier.selectableGroup()) {
                THEME_OPTIONS.forEachIndexed { index, option ->
                    SettingsItem(
                        headline = stringResource(option.label),
                        onClick = { if (option != theme) onChange(option) },
                        index = index,
                        count = THEME_OPTIONS.size,
                        selected = option == theme,
                        modifier = Modifier.testTag(TestTags.themeOption(option.name)),
                        leadingContent = { RadioButton(selected = option == theme, onClick = null) },
                    )
                }
            }
        }
    }
}

/**
 * The font page: each font as a radio preference, its row set in that font so it shows what it
 * looks like before it's chosen.
 */
@Composable
private fun FontScreen(font: AppSettings.Font, onBack: () -> Unit, onChange: (AppSettings.Font) -> Unit) {
    SettingsScaffold(R.string.font, onBack, Modifier.testTag(TestTags.FONT_PAGE)) {
        item {
            SettingsSection(R.string.theme_options, Modifier.selectableGroup()) {
                AppSettings.Font.entries.forEachIndexed { index, option ->
                    MaterialTheme(typography = remember(option) { typography(option) }) {
                        SettingsItem(
                            headline = stringResource(option.label),
                            onClick = { if (option != font) onChange(option) },
                            index = index,
                            count = AppSettings.Font.entries.size,
                            selected = option == font,
                            modifier = Modifier.testTag(TestTags.fontOption(option.name)),
                            leadingContent = { RadioButton(selected = option == font, onClick = null) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A preference row, as Android's expressive settings draw it: a titleMedium headline over its value,
 * at least 72.dp tall, in a bright group whose outline stays put when pressed or selected. Radio
 * preferences show their selection with the radio, not the row.
 */
@Composable
private fun SettingsItem(
    headline: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    index: Int = 0,
    count: Int = 1,
    selected: Boolean? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    verticalAlignment: Alignment.Vertical =
        if (leadingContent != null || trailingContent != null) Alignment.CenterVertically else ListItemDefaults.verticalAlignment(),
    supportingContent: @Composable (() -> Unit)? = value?.let { { Text(it) } },
) {
    val segmented = ListItemDefaults.segmentedShapes(index, count)
    val shapes = segmented.copy(selectedShape = segmented.shape, pressedShape = segmented.shape)
    val colors = settingsItemColors()
    val typography = MaterialTheme.typography
    val padding = ListItemDefaults.ContentPadding
    val twoLines = with(LocalDensity.current) { typography.titleMedium.lineHeight.toDp() + typography.bodyMedium.lineHeight.toDp() }
    // Material's 72.dp two-line minimum, growing with the font size.
    val minHeight = maxOf(72.dp, twoLines + padding.calculateTopPadding() + padding.calculateBottomPadding())
    val rowModifier = modifier.fillMaxWidth().heightIn(min = minHeight)
    val headlineText = @Composable { Text(headline, style = typography.titleMedium) }
    when {
        selected != null && onClick != null -> SegmentedListItem(
            selected = selected, onClick = onClick, shapes = shapes, colors = colors, modifier = rowModifier,
            leadingContent = leadingContent, trailingContent = trailingContent, supportingContent = supportingContent,
            verticalAlignment = verticalAlignment, content = headlineText,
        )
        onClick != null -> SegmentedListItem(
            onClick = onClick, shapes = shapes, colors = colors, modifier = rowModifier,
            leadingContent = leadingContent, trailingContent = trailingContent, supportingContent = supportingContent,
            verticalAlignment = verticalAlignment, content = headlineText,
        )
        else -> SegmentedListItem(
            shapes = shapes, colors = colors, modifier = rowModifier,
            leadingContent = leadingContent, trailingContent = trailingContent, supportingContent = supportingContent,
            verticalAlignment = verticalAlignment, content = headlineText,
        )
    }
}

/** Android Settings fills expressive preference groups with surfaceBright, in both themes. */
@Composable
private fun settingsItemColors() = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceBright,
    selectedContainerColor = MaterialTheme.colorScheme.surfaceBright,
    selectedContentColor = MaterialTheme.colorScheme.onSurface,
)

/**
 * How many databases the start screen remembers. The slider only saves once it's let go, since a
 * lower number forgets the oldest databases and dragging past it on the way shouldn't.
 */
@Composable
private fun RecentLimitItem(limit: Int, onChange: (Int) -> Unit) {
    var value by remember(limit) { mutableIntStateOf(limit) }
    val range = AppSettings.RECENT_LIMITS
    val title = stringResource(R.string.settings_recent_limit)
    SettingsItem(
        headline = title,
        verticalAlignment = Alignment.Top,
        leadingContent = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
        trailingContent = {
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        supportingContent = {
            Column {
                Text(pluralStringResource(R.plurals.settings_recent_limit_summary, value, value))
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.roundToInt().coerceIn(range) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    // The stops between the ends, one for each value.
                    steps = range.last - range.first - 1,
                    onValueChangeFinished = { if (value != limit) onChange(value) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics {
                            contentDescription = title
                            stateDescription = value.toString()
                        }
                        .testTag(TestTags.RECENT_LIMIT),
                )
            }
        },
    )
}

@Composable
private fun SettingsPreview(
    theme: AppSettings.Theme = AppSettings.Theme.SYSTEM,
    font: AppSettings.Font = AppSettings.DEFAULT_FONT,
    limit: Int = AppSettings.DEFAULT_RECENT_LIMIT,
) {
    KeeBerryTheme(font) {
        SettingsScreen(SettingsState(theme, font, limit), onBack = {}, onThemeChange = {}, onFontChange = {}, onRecentLimitChange = {})
    }
}

@ScreenPreviews
@Composable
private fun SettingsScreenPreview() = SettingsPreview()

@Preview(name = "Dark theme, one database", showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun SettingsOneRecentPreview() = SettingsPreview(theme = AppSettings.Theme.DARK, font = AppSettings.Font.INTER, limit = 1)

@ScreenPreviews
@Composable
private fun DarkThemeScreenPreview() {
    KeeBerryTheme { DarkThemeScreen(AppSettings.Theme.SYSTEM, onBack = {}, onChange = {}) }
}

@ScreenPreviews
@Composable
private fun LockTimeoutScreenPreview() {
    KeeBerryTheme { LockTimeoutScreen(60, onBack = {}, onChange = {}) }
}

@ScreenPreviews
@Composable
private fun FontScreenPreview() {
    KeeBerryTheme { FontScreen(AppSettings.DEFAULT_FONT, onBack = {}, onChange = {}) }
}
