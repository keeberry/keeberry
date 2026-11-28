package app.keeberry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import app.keeberry.AppSettings
import app.keeberry.PassphraseOptions
import app.keeberry.PasswordGenerator
import app.keeberry.PasswordOptions
import app.keeberry.R
import app.keeberry.Strength
import app.keeberry.WordList
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A sheet that makes a random password or passphrase. A new one appears whenever an option changes
 * or on request, with its strength, and [onUse] takes the one shown. The options are remembered for
 * next time; the generated values are only kept while the sheet is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasswordGeneratorSheet(onUse: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var mode by remember { mutableStateOf(settings.generatorMode) }
    var passwordOptions by remember { mutableStateOf(settings.passwordOptions) }
    var passphraseOptions by remember { mutableStateOf(settings.passphraseOptions) }
    val words by produceState<List<String>?>(null) { value = withContext(Dispatchers.IO) { WordList.load(context) } }
    val generator = remember { PasswordGenerator() }
    var round by remember { mutableIntStateOf(0) }
    val passphrase = mode == AppSettings.GeneratorMode.PASSPHRASE
    val value = remember(mode, passwordOptions, passphraseOptions, words, round) {
        when {
            !passphrase -> if (passwordOptions.valid) generator.password(passwordOptions) else ""
            else -> words?.let { generator.passphrase(passphraseOptions, it) }.orEmpty()
        }
    }
    val bits = if (passphrase) PasswordGenerator.passphraseBits(passphraseOptions, words?.size ?: 0)
    else PasswordGenerator.passwordBits(passwordOptions)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The sheet has a window of its own, which needs the screen's screenshot protection.
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
        modifier = Modifier.testTag(TestTags.GENERATOR),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.generator_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            val modes = AppSettings.GeneratorMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                modes.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == mode,
                        onClick = {
                            mode = option
                            settings.generatorMode = option
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        modifier = Modifier.testTag(TestTags.generatorMode(option.name)),
                    ) {
                        Text(stringResource(if (option == AppSettings.GeneratorMode.PASSWORD) R.string.generator_password else R.string.generator_passphrase))
                    }
                }
            }
            GeneratedValue(value, onRefresh = { round++ })
            StrengthMeter(bits)
            if (passphrase) {
                PassphraseControls(passphraseOptions) {
                    passphraseOptions = it
                    settings.passphraseOptions = it
                }
            } else {
                PasswordControls(passwordOptions) {
                    passwordOptions = it
                    if (it.valid) settings.passwordOptions = it
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
                MediumButton(
                    onClick = { onUse(value) },
                    text = stringResource(if (passphrase) R.string.generator_use_passphrase else R.string.generator_use_password),
                    enabled = value.isNotEmpty(),
                    modifier = Modifier.testTag(TestTags.USE_GENERATED),
                )
            }
        }
    }
}

/** The value made, set so its characters are easy to tell apart, with a way to make another. */
@Composable
private fun GeneratedValue(value: String, onRefresh: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.heightIn(min = 72.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                highlighted(value, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                Modifier.weight(1f).padding(vertical = 12.dp).testTag(TestTags.GENERATED_VALUE),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            )
            val label = stringResource(R.string.generator_refresh)
            WithTooltip(label) {
                IconButton(onRefresh, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag(TestTags.REFRESH_GENERATED)) {
                    Icon(painterResource(R.drawable.ic_refresh), label)
                }
            }
        }
    }
}

/** Digits in [digits] and symbols in [symbols], so a 0 can't pass for an O. */
private fun highlighted(value: String, digits: Color, symbols: Color): AnnotatedString = buildAnnotatedString {
    for (char in value) {
        when {
            char.isDigit() -> withStyle(SpanStyle(color = digits)) { append(char) }
            !char.isLetter() && char != ' ' -> withStyle(SpanStyle(color = symbols)) { append(char) }
            else -> append(char)
        }
    }
}

@Composable
private fun StrengthMeter(bits: Double) {
    val strength = Strength.of(bits)
    val label = stringResource(
        when (strength) {
            Strength.POOR -> R.string.strength_poor
            Strength.WEAK -> R.string.strength_weak
            Strength.GOOD -> R.string.strength_good
            Strength.EXCELLENT -> R.string.strength_excellent
        },
    )
    val rounded = bits.roundToInt()
    val summary = pluralStringResource(R.plurals.generator_strength, rounded, label, rounded)
    val weak = strength == Strength.POOR || strength == Strength.WEAK
    val color = if (weak) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        LinearProgressIndicator(
            progress = { (bits / 128).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = summary },
            color = color,
        )
        Text(
            summary,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (weak) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A titled slider over a range of whole numbers, with the chosen one beside the title. */
@Composable
private fun CountSlider(title: Int, value: Int, range: IntRange, tag: String, onChange: (Int) -> Unit) {
    val name = stringResource(title)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        modifier = Modifier
            .semantics {
                contentDescription = name
                stateDescription = value.toString()
            }
            .testTag(tag),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasswordControls(options: PasswordOptions, onChange: (PasswordOptions) -> Unit) {
    CountSlider(R.string.generator_length, options.length, PasswordOptions.LENGTHS, TestTags.GENERATOR_LENGTH) {
        onChange(options.copy(length = it))
    }
    Text(
        stringResource(R.string.generator_characters),
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
    )
    val kinds = listOf(
        Triple(R.string.generator_upper, options.upper) { on: Boolean -> options.copy(upper = on) },
        Triple(R.string.generator_lower, options.lower) { on: Boolean -> options.copy(lower = on) },
        Triple(R.string.generator_digits, options.digits) { on: Boolean -> options.copy(digits = on) },
        Triple(R.string.generator_symbols, options.symbols) { on: Boolean -> options.copy(symbols = on) },
    )
    val onCount = kinds.count { it.second }
    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { (label, on, change) ->
            FilterChip(
                selected = on,
                onClick = { onChange(change(!on)) },
                label = { Text(stringResource(label)) },
                // At least one kind stays on.
                enabled = !on || onCount > 1,
            )
        }
    }
    CheckboxRow(R.string.generator_avoid_look_alike, options.avoidLookAlike, enabled = true, {
        onChange(options.copy(avoidLookAlike = it))
    })
    CheckboxRow(R.string.generator_every_kind, options.everyKind, enabled = true, {
        onChange(options.copy(everyKind = it))
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassphraseControls(options: PassphraseOptions, onChange: (PassphraseOptions) -> Unit) {
    CountSlider(R.string.generator_words, options.words, PassphraseOptions.WORD_COUNTS, TestTags.GENERATOR_WORDS) {
        onChange(options.copy(words = it))
    }
    Text(
        stringResource(R.string.generator_separator),
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
    )
    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PassphraseOptions.SEPARATORS.forEach { separator ->
            FilterChip(
                selected = separator == options.separator,
                onClick = { onChange(options.copy(separator = separator)) },
                label = {
                    Text(
                        when (separator) {
                            " " -> stringResource(R.string.generator_separator_space)
                            "" -> stringResource(R.string.generator_separator_none)
                            else -> separator
                        },
                    )
                },
            )
        }
    }
    CheckboxRow(R.string.generator_capitalize, options.capitalize, enabled = true, {
        onChange(options.copy(capitalize = it))
    })
}

@ScreenPreviews
@Composable
private fun PasswordGeneratorPreview() {
    KeeBerryTheme { PasswordGeneratorSheet(onUse = {}, onDismiss = {}) }
}
