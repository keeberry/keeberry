package app.keeberry.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import app.keeberry.R

/**
 * Material 3 Expressive, with Material You colors from Android 12 and KeeBerry's indigo before that.
 * The expressive theme gives buttons, lists and toggles their shape-morphing and springy motion.
 */
@Composable
internal fun KeeBerryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // values-night holds the dark variants, so the resources already follow the system.
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = colorResource(R.color.primary),
            onPrimary = colorResource(R.color.on_primary),
            primaryContainer = colorResource(R.color.primary_container),
            onPrimaryContainer = colorResource(R.color.on_primary_container),
            secondaryContainer = colorResource(R.color.secondary_container),
            onSecondaryContainer = colorResource(R.color.on_secondary_container),
            background = colorResource(R.color.surface),
            surface = colorResource(R.color.surface),
            onSurface = colorResource(R.color.on_surface),
            onSurfaceVariant = colorResource(R.color.on_surface_variant),
            surfaceContainer = colorResource(R.color.surface_container),
            surfaceContainerHigh = colorResource(R.color.surface_container_high),
            outline = colorResource(R.color.outline),
        )
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}

/**
 * A screen's main action: a medium expressive button whose corners morph while it's pressed.
 * [outlined] gives an action next to the main one the same size with less emphasis.
 */
@Composable
internal fun MediumButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Int? = null,
    outlined: Boolean = false,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        }
        Text(text, style = ButtonDefaults.textStyleFor(height))
    }
    val shapes = ButtonDefaults.shapesFor(height)
    val sized = modifier.heightIn(min = height)
    val padding = ButtonDefaults.contentPaddingFor(height)
    if (outlined) {
        OutlinedButton(onClick = onClick, shapes = shapes, modifier = sized, enabled = enabled, contentPadding = padding, content = content)
    } else {
        Button(onClick = onClick, shapes = shapes, modifier = sized, enabled = enabled, contentPadding = padding, content = content)
    }
}

/**
 * Segmented list items are filled to stand out from the screen; their default fill is the surface
 * itself, which suits lists on a container-colored background rather than KeeBerry's plain screens.
 */
@Composable
internal fun segmentedListColors() =
    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** Semantic hooks for UI tests, where text alone is ambiguous. */
internal object TestTags {
    const val PASSWORD = "database_password"
    const val UNLOCK = "unlock_button"
    const val SEARCH = "database_search"
    const val SEARCH_INPUT = "database_search_input"
    const val SEARCH_RESULTS = "database_search_results"
    const val NEW_NAME = "new_database_name"
    const val NEW_PASSWORD = "new_database_password"
    const val NEW_CONFIRMATION = "new_database_confirmation"
    const val CREATE = "create_database_button"
    const val PICK_KEY_FILE = "pick_key_file_button"
    const val NEW_KEY_FILE = "new_key_file_button"
    const val USE_YUBIKEY = "use_yubikey"
    const val MORE_OPTIONS = "more_options_button"
    const val CHANGE_MASTER_KEY = "change_master_key_item"
    const val SAVE_MASTER_KEY = "save_master_key_button"
    const val NEW_ENTRY = "new_entry_button"
    const val EDIT_ENTRY = "edit_entry_button"
    const val SAVE_ENTRY = "save_entry_button"
    const val ENTRY_TITLE = "entry_title"
    const val ENTRY_USERNAME = "entry_username"
    const val ENTRY_PASSWORD = "entry_password"
    const val ENTRY_GROUP = "entry_group"
    const val DELETE_ENTRY = "delete_entry_button"
    const val CONFIRM_DELETE = "confirm_delete_button"
    const val RECYCLE_BIN = "recycle_bin_button"
    const val RESTORE_ENTRY = "restore_entry_button"
    const val CONFIRM_RESTORE = "confirm_restore_button"
    const val EMPTY_BIN = "empty_bin_button"
    const val CONFIRM_EMPTY_BIN = "confirm_empty_bin_button"
    const val OPEN_SETTINGS = "open_settings_button"
    const val SETTINGS = "settings_screen"
    const val RECENT_LIMIT = "recent_limit_slider"
}

