package app.keeberry.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.keeberry.AppSettings
import app.keeberry.R
import kotlin.math.roundToInt

/** What the settings screen shows, kept apart from where it comes from so tests and previews can supply it. */
internal class SettingsState(val theme: AppSettings.Theme, val recentLimit: Int)

/**
 * App-wide settings, reachable from the start screen and from an open database. Settings are grouped
 * into titled sections of segmented list items, under a large app bar that collapses as it scrolls,
 * and laid out like Android's own settings. The dark theme row opens a page of its own, as it does in
 * the system's display settings.
 */
@Composable
internal fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onThemeChange: (AppSettings.Theme) -> Unit,
    onRecentLimitChange: (Int) -> Unit,
) {
    // Saved, so the page is still open after a theme change recreates the screen.
    var darkThemeOpen by rememberSaveable { mutableStateOf(false) }
    if (darkThemeOpen) {
        DarkThemeScreen(state.theme, onBack = { darkThemeOpen = false }, onThemeChange)
        return
    }
    SettingsScaffold(R.string.settings, onBack, Modifier.testTag(TestTags.SETTINGS)) {
        item {
            SettingsSection(R.string.settings_appearance) {
                DarkThemeItem(state.theme, onOpen = { darkThemeOpen = true }, onThemeChange)
            }
        }
        item {
            SettingsSection(R.string.recent_databases) {
                RecentLimitItem(state.recentLimit, onRecentLimitChange)
            }
        }
    }
}

/** A settings page: a large app bar that collapses as the list under it scrolls. */
@Composable
private fun SettingsScaffold(title: Int, onBack: () -> Unit, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    BackHandler(onBack = onBack)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val direction = LocalLayoutDirection.current
    Scaffold(
        Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scroll,
            )
        },
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

/** A titled group of settings. Wide windows keep it to a readable width. */
@Composable
private fun SettingsSection(title: Int?, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        if (title != null) {
            Text(
                stringResource(title),
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        content()
    }
}

/**
 * The dark theme row of Android's display settings: tapping it opens the dark theme page, and its
 * switch, set apart by a divider, turns the dark theme on or off right there. The switch shows the
 * theme in use, so while KeeBerry matches the device it shows the device's.
 */
@Composable
private fun DarkThemeItem(theme: AppSettings.Theme, onOpen: () -> Unit, onChange: (AppSettings.Theme) -> Unit) {
    val dark = isSystemInDarkTheme()
    SegmentedListItem(
        onClick = onOpen,
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        colors = segmentedListColors(),
        modifier = Modifier.testTag(TestTags.DARK_THEME),
        leadingContent = { Icon(painterResource(R.drawable.ic_dark_mode), contentDescription = null) },
        supportingContent = { Text(stringResource(darkThemeSummary(theme, dark))) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerticalDivider(Modifier.height(32.dp))
                Spacer(Modifier.width(16.dp))
                SettingsSwitch(
                    checked = dark,
                    onCheckedChange = { onChange(if (it) AppSettings.Theme.DARK else AppSettings.Theme.LIGHT) },
                    modifier = Modifier.testTag(TestTags.DARK_THEME_SWITCH),
                )
            }
        },
    ) {
        Text(stringResource(R.string.settings_dark_theme))
    }
}

private fun darkThemeSummary(theme: AppSettings.Theme, dark: Boolean) = when (theme) {
    AppSettings.Theme.SYSTEM -> if (dark) R.string.dark_theme_on_matching else R.string.dark_theme_off_matching
    AppSettings.Theme.LIGHT -> R.string.dark_theme_off
    AppSettings.Theme.DARK -> R.string.dark_theme_on
}

/**
 * The dark theme page of Android's settings: an introduction, the main switch, then whether to
 * match the device, where the system's page has its schedule. Using the main switch stops matching.
 */
@Composable
private fun DarkThemeScreen(theme: AppSettings.Theme, onBack: () -> Unit, onChange: (AppSettings.Theme) -> Unit) {
    val dark = isSystemInDarkTheme()
    val explicit = { on: Boolean -> if (on) AppSettings.Theme.DARK else AppSettings.Theme.LIGHT }
    SettingsScaffold(R.string.settings_dark_theme, onBack, Modifier.testTag(TestTags.DARK_THEME_PAGE)) {
        item {
            SettingsSection(null) {
                Text(
                    stringResource(R.string.dark_theme_intro),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(null) {
                MainSwitch(
                    stringResource(R.string.dark_theme_use),
                    checked = dark,
                    onCheckedChange = { onChange(explicit(it)) },
                    modifier = Modifier.testTag(TestTags.USE_DARK_THEME),
                )
            }
        }
        item {
            SettingsSection(null) {
                val matching = theme == AppSettings.Theme.SYSTEM
                SegmentedListItem(
                    // Turning it off keeps the theme in use, now chosen rather than matched.
                    onClick = { onChange(if (matching) explicit(dark) else AppSettings.Theme.SYSTEM) },
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    colors = segmentedListColors(),
                    supportingContent = { Text(stringResource(R.string.dark_theme_match_device_summary)) },
                    trailingContent = { SettingsSwitch(checked = matching, onCheckedChange = null) },
                    modifier = Modifier.testTag(TestTags.MATCH_DEVICE).semantics(mergeDescendants = true) {
                        role = Role.Switch
                        toggleableState = ToggleableState(matching)
                    },
                ) {
                    Text(stringResource(R.string.dark_theme_match_device))
                }
            }
        }
    }
}

/**
 * The main switch at the top of an Android settings page: a large rounded bar that fills with the
 * primary container color while it's on, and toggles wherever it's tapped.
 */
@Composable
private fun MainSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (checked) colors.primaryContainer else colors.surfaceContainerHighest)
    val content by animateColorAsState(if (checked) colors.onPrimaryContainer else colors.onSurface)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(container)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(start = 24.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = content)
        Spacer(Modifier.width(16.dp))
        SettingsSwitch(checked, onCheckedChange = null)
    }
}

/** A switch as Android's settings draw it, with a check mark on the thumb while it's on. */
@Composable
private fun SettingsSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = if (checked) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(SwitchDefaults.IconSize)) }
        } else null,
    )
}

/**
 * How many databases the start screen remembers. The slider only saves once it's let go, since a
 * lower number forgets the oldest databases and dragging past it on the way shouldn't.
 */
@Composable
private fun RecentLimitItem(limit: Int, onChange: (Int) -> Unit) {
    var value by remember(limit) { mutableIntStateOf(limit) }
    val range = AppSettings.RECENT_LIMITS
    val title = stringResource(R.string.settings_recent_limit)
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        colors = segmentedListColors(),
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
    ) {
        Text(title)
    }
}

@Composable
private fun SettingsPreview(theme: AppSettings.Theme = AppSettings.Theme.SYSTEM, limit: Int = AppSettings.DEFAULT_RECENT_LIMIT) {
    KeeBerryTheme { SettingsScreen(SettingsState(theme, limit), onBack = {}, onThemeChange = {}, onRecentLimitChange = {}) }
}

@ScreenPreviews
@Composable
private fun SettingsScreenPreview() = SettingsPreview()

@Preview(name = "Dark theme, one database", showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun SettingsOneRecentPreview() = SettingsPreview(theme = AppSettings.Theme.DARK, limit = 1)

@ScreenPreviews
@Composable
private fun DarkThemeScreenPreview() {
    KeeBerryTheme { DarkThemeScreen(AppSettings.Theme.SYSTEM, onBack = {}, onChange = {}) }
}
