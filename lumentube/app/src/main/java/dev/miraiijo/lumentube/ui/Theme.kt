package dev.miraiijo.lumentube.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import dev.miraiijo.lumentube.R

/* Lumen's design tokens (same palette, radii, Inter typography): LumenTube looks like Lumen. */

object Palette {
    val Background = Color(0xFF070707)
    val Panel = Color(0xFF161618)
    val Tile = Color(0xFF1C1C1E)
    val TileFocused = Color(0xFF2C2C2E)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF98989D)
    /** White highlight: progress bars, text cursor, pairing codes. */
    val Highlight = Color(0xFFFFFFFF)
    /** Idle fill of a control (button, field, chip). */
    val ControlIdle = Color.White.copy(alpha = 0.10f)
    /** Selected / active state (with a check icon). */
    val Selected = Color.White.copy(alpha = 0.16f)
    /** Idle stroke of outlined controls. */
    val Outline = Color.White.copy(alpha = 0.22f)
    /** Primary button laid over artwork (hero, title sheet): translucent white. */
    val ControlOnArt = Color.White.copy(alpha = 0.22f)
    /** Secondary text laid over artwork (card captions). */
    val TextOnArtSecondary = Color.White.copy(alpha = 0.75f)
    /** Progress / health track behind a filled bar. */
    val Track = Color.White.copy(alpha = 0.22f)
    /** Thin separators. */
    val Divider = Color.White.copy(alpha = 0.08f)
    /** Underline of the active top tab (bright white when focused). */
    val TabIndicator = Color.White.copy(alpha = 0.45f)
    val Danger = Color(0xFFFF453A)
    val Warning = Color(0xFFFF9F0A)
}

/** Three focus lifts only: controls, cards, avatars. */
object FocusScale {
    const val Control = 1.05f
    const val Card = 1.08f
    const val Avatar = 1.1f
}


/**
 * Inter: the closest open font to SF Pro (Apple TV's system font, licensed for Apple platforms
 * only). Bundled in res/font, Latin subset, 4 weights; licence in licenses/Inter-OFL.txt.
 */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Tight tracking for large titles (SF Pro Display-like). */
val DisplayTracking = (-0.022).em

/** Every Material text style (also those set inside tv-material buttons) uses Inter. */
private val LumenTypography: Typography = Typography().let { t ->
    // Closer to SF Pro: Material's wide default tracking (up to +0.5 sp) replaced by Apple-like
    // tight tracking, relative to the size (em) so it holds for every explicit fontSize.
    fun TextStyle.text() = copy(fontFamily = InterFamily, letterSpacing = (-0.011).em)
    fun TextStyle.display() = copy(fontFamily = InterFamily, letterSpacing = (-0.022).em)
    Typography(
        displayLarge = t.displayLarge.display(), displayMedium = t.displayMedium.display(), displaySmall = t.displaySmall.display(),
        headlineLarge = t.headlineLarge.display(), headlineMedium = t.headlineMedium.display(), headlineSmall = t.headlineSmall.display(),
        titleLarge = t.titleLarge.display(), titleMedium = t.titleMedium.text(), titleSmall = t.titleSmall.text(),
        bodyLarge = t.bodyLarge.text(), bodyMedium = t.bodyMedium.text(), bodySmall = t.bodySmall.text(),
        labelLarge = t.labelLarge.text(), labelMedium = t.labelMedium.text(), labelSmall = t.labelSmall.text(),
    )
}

@Composable
fun LumenTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Highlight,
            background = Palette.Background,
            surface = Palette.Tile,
            onSurface = Palette.TextPrimary,
        ),
        typography = LumenTypography,
    ) {
        // tv-material's Text takes LocalContentColor, black by default outside a Surface: without
        // this, every Text with no explicit color (row titles, the sheet's title) is black on black.
        CompositionLocalProvider(LocalContentColor provides Palette.TextPrimary, content = content)
    }
}

/** Shared measurements: one set of radii, heights and font sizes for the whole app. */
object Dimens {
    /** Side margin, ~5 % of a 960 dp wide TV screen (overscan safe area). */
    val Gutter = 48.dp
    /** Space between the top of a screen's area and its first content. */
    val ContentTop = 16.dp
    /** Space above each section title (rows never touch the next title). */
    val SectionTop = 30.dp
    /** Every chip, button and field has this height. */
    val ControlHeight = 30.dp
    /** Gap between controls in a row (chips, buttons). */
    val PillGap = 10.dp
    /** Gap between cards in a content row (posters, tiles, Reprendre). */
    val CardGap = 16.dp

    /** Radii: controls, cards (tiles, banners, menus), dialogs. */
    val RadiusControl = 12.dp
    val RadiusCard = 16.dp
    val RadiusDialog = 24.dp

    /** Smallest text used anywhere (10-foot legibility). */
    val TextMin = 13.sp
}

val ControlShape = RoundedCornerShape(Dimens.RadiusControl)
val CardShape = RoundedCornerShape(Dimens.RadiusCard)
val DialogShape = RoundedCornerShape(Dimens.RadiusDialog)
