package app.keeberry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.keeberry.AppSettings
import app.keeberry.R
import kotlin.math.roundToInt

/** What the settings screen shows, kept apart from where it comes from so tests and previews can supply it. */
internal class SettingsState(val recentLimit: Int)

/**
 * App-wide settings, reachable from the start screen and from an open database. Settings are grouped
 * into titled sections of segmented list items, under a large app bar that collapses as it scrolls.
 */
@Composable
internal fun SettingsScreen(state: SettingsState, onBack: () -> Unit, onRecentLimitChange: (Int) -> Unit) {
    BackHandler(onBack = onBack)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val direction = LocalLayoutDirection.current
    Scaffold(
        Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().consumeWindowInsets(padding).testTag(TestTags.SETTINGS),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = padding.calculateEndPadding(direction) + 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                SettingsSection(R.string.recent_databases) {
                    RecentLimitItem(state.recentLimit, onRecentLimitChange)
                }
            }
        }
    }
}

/** A titled group of settings. Wide windows keep it to a readable width. */
@Composable
private fun SettingsSection(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Text(
            stringResource(title),
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
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
private fun SettingsPreview(limit: Int = AppSettings.DEFAULT_RECENT_LIMIT) {
    KeeBerryTheme { SettingsScreen(SettingsState(limit), onBack = {}, onRecentLimitChange = {}) }
}

@ScreenPreviews
@Composable
private fun SettingsScreenPreview() = SettingsPreview()

@Preview(name = "One database", showSystemUi = true)
@Composable
private fun SettingsOneRecentPreview() = SettingsPreview(limit = 1)
