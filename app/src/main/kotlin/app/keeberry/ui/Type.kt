package app.keeberry.ui

import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.keeberry.AppSettings
import app.keeberry.R

/** Weights KeeBerry's type scale uses, mapped onto each variable font's `wght` axis. */
private val VariableFontWeights = listOf(FontWeight.W400, FontWeight.W500, FontWeight.W600, FontWeight.W700)

private fun variableFont(
    @FontRes resId: Int,
    weight: FontWeight,
    style: FontStyle = FontStyle.Normal,
    vararg axes: FontVariation.Setting,
) = Font(
    resId = resId,
    weight = weight,
    style = style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), *axes),
)

/** License: assets/licenses/google-sans-flex-OFL.txt */
private val GoogleSansFlex = FontFamily(
    listOf(Font(R.font.google_sans_flex)) +
        VariableFontWeights.map { variableFont(R.font.google_sans_flex, it) } +
        // Google Sans Flex carries italics on its `ital` axis in the same file.
        VariableFontWeights.map { variableFont(R.font.google_sans_flex, it, FontStyle.Italic, FontVariation.italic(.5f)) },
)

/** License: assets/licenses/inter-OFL.txt */
private val Inter = FontFamily(
    VariableFontWeights.map { variableFont(R.font.inter, it) } +
        VariableFontWeights.map { variableFont(R.font.inter_italic, it, FontStyle.Italic) },
)

/** License: assets/licenses/roboto-OFL.txt */
private val Roboto = FontFamily(
    VariableFontWeights.map { variableFont(R.font.roboto, it) } +
        VariableFontWeights.map { variableFont(R.font.roboto_italic, it, FontStyle.Italic) },
)

private val AppSettings.Font.family
    get() = when (this) {
        AppSettings.Font.GOOGLE_SANS_FLEX -> GoogleSansFlex
        AppSettings.Font.INTER -> Inter
        AppSettings.Font.ROBOTO -> Roboto
    }

/** The font's name, which isn't translated. */
internal val AppSettings.Font.label
    get() = when (this) {
        AppSettings.Font.GOOGLE_SANS_FLEX -> R.string.font_google_sans_flex
        AppSettings.Font.INTER -> R.string.font_inter
        AppSettings.Font.ROBOTO -> R.string.font_roboto
    }

/** Material's type scale, emphasized styles included, set in [font]. */
internal fun typography(font: AppSettings.Font): Typography {
    val family = font.family
    fun TextStyle.inFont() = merge(fontFamily = family)
    return with(Typography()) {
        copy(
            displayLarge = displayLarge.inFont(),
            displayMedium = displayMedium.inFont(),
            displaySmall = displaySmall.inFont(),
            headlineLarge = headlineLarge.inFont(),
            headlineMedium = headlineMedium.inFont(),
            headlineSmall = headlineSmall.inFont(),
            titleLarge = titleLarge.inFont(),
            titleMedium = titleMedium.inFont(),
            titleSmall = titleSmall.inFont(),
            bodyLarge = bodyLarge.inFont(),
            bodyMedium = bodyMedium.inFont(),
            bodySmall = bodySmall.inFont(),
            labelLarge = labelLarge.inFont(),
            labelMedium = labelMedium.inFont(),
            labelSmall = labelSmall.inFont(),
            displayLargeEmphasized = displayLargeEmphasized.inFont(),
            displayMediumEmphasized = displayMediumEmphasized.inFont(),
            displaySmallEmphasized = displaySmallEmphasized.inFont(),
            headlineLargeEmphasized = headlineLargeEmphasized.inFont(),
            headlineMediumEmphasized = headlineMediumEmphasized.inFont(),
            headlineSmallEmphasized = headlineSmallEmphasized.inFont(),
            titleLargeEmphasized = titleLargeEmphasized.inFont(),
            titleMediumEmphasized = titleMediumEmphasized.inFont(),
            titleSmallEmphasized = titleSmallEmphasized.inFont(),
            bodyLargeEmphasized = bodyLargeEmphasized.inFont(),
            bodyMediumEmphasized = bodyMediumEmphasized.inFont(),
            bodySmallEmphasized = bodySmallEmphasized.inFont(),
            labelLargeEmphasized = labelLargeEmphasized.inFont(),
            labelMediumEmphasized = labelMediumEmphasized.inFont(),
            labelSmallEmphasized = labelSmallEmphasized.inFont(),
        )
    }
}
