package dev.miraiijo.lumentube.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/* Lumen's design tokens (same palettes, radii, Inter typography): LumenTube looks like Lumen. */

/**
 * The two looks of the redesign (Lumen's): Nuit (dark) and Jour (light). Colors are read from the
 * current one, a snapshot state: switching the appearance recomposes everything that uses them.
 */
class LumenColors(
    val background: Color,
    val panel: Color,
    val tile: Color,
    val tileFocused: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val controlIdle: Color,
    val selected: Color,
    val outline: Color,
    val track: Color,
    val divider: Color,
    /** Focused element: a solid pill (white at night, near-black by day) and its content. */
    val focus: Color,
    val onFocus: Color,
    /** Ring around a focused card. */
    val focusRing: Color,
    /** Soft lights of the background: warm top right, cool bottom left. */
    val glowWarm: Color,
    val glowCool: Color,
    val isLight: Boolean,
)

val NightColors = LumenColors(
    background = Color(0xFF0E1014), panel = Color(0xFF171A20), tile = Color(0xFF1B1E25), tileFocused = Color(0xFF272B33),
    textPrimary = Color(0xFFF5F5F7), textSecondary = Color(0xFF9A9CA5),
    controlIdle = Color.White.copy(alpha = 0.10f), selected = Color.White.copy(alpha = 0.16f), outline = Color.White.copy(alpha = 0.22f),
    track = Color.White.copy(alpha = 0.22f), divider = Color.White.copy(alpha = 0.08f),
    focus = Color(0xFFF5F5F7), onFocus = Color(0xFF0E1014), focusRing = Color(0xFFF5F5F7),
    glowWarm = Color(0x33A0784F), glowCool = Color(0x383C5AAA), isLight = false,
)

val DayColors = LumenColors(
    background = Color(0xFFECEEF2), panel = Color(0xFFF7F8FA), tile = Color(0xFFDDE0E6), tileFocused = Color(0xFFD0D4DC),
    textPrimary = Color(0xFF121317), textSecondary = Color(0xFF5F626B),
    controlIdle = Color(0x0F121317), selected = Color(0xFFFFFFFF), outline = Color(0x29121317),
    track = Color(0x29121317), divider = Color(0x14121317),
    focus = Color(0xFF121317), onFocus = Color(0xFFFFFFFF), focusRing = Color(0xFF121317),
    glowWarm = Color(0x73D6BE96), glowCool = Color(0x7396AFEB), isLight = true,
)

object Palette {
    /** Set by LumenTubeTheme from the "Apparence" setting. */
    var colors by androidx.compose.runtime.mutableStateOf(NightColors)

    val Background get() = colors.background
    val Panel get() = colors.panel
    val Tile get() = colors.tile
    val TileFocused get() = colors.tileFocused
    val TextPrimary get() = colors.textPrimary
    val TextSecondary get() = colors.textSecondary
    /** Progress bars, text cursor, pairing codes (the main text color). */
    val Highlight get() = colors.textPrimary
    /** Idle fill of a control (button, field, chip). */
    val ControlIdle get() = colors.controlIdle
    /** Selected / active state (with a check icon). */
    val Selected get() = colors.selected
    val Outline get() = colors.outline
    /** Over artwork (video thumbnails, the player): always light on dark. */
    val ControlOnArt = Color.White.copy(alpha = 0.22f)
    val TextOnArtSecondary = Color.White.copy(alpha = 0.75f)
    val Track get() = colors.track
    val Divider get() = colors.divider
    val Focus get() = colors.focus
    val OnFocus get() = colors.onFocus
    val FocusRing get() = colors.focusRing
    val Danger = Color(0xFFFF453A)
    val Warning = Color(0xFFFF9F0A)
}

/** Three focus lifts only: controls, cards, avatars. */
object FocusScale {
    const val Control = 1.05f
    const val Card = 1.08f
    /** A card with the focus ring (the redesign): a lighter zoom, the ring already shows it. */
    const val CardRing = 1.04f
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

/** "Apparence": Auto (day from 7:30 to 19:30, night otherwise), Jour, Nuit. */
enum class Appearance { AUTO, DAY, NIGHT }

/** Day or night now, for Auto (re-checked every minute). */
private fun isDaytime(): Boolean {
    val now = java.util.Calendar.getInstance()
    val minutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
    return minutes in DAY_START_MIN until DAY_END_MIN
}

private const val DAY_START_MIN = 7 * 60 + 30
/** "Taille de l'interface", in %: from [UI_SCALE_MIN] to [UI_SCALE_MAX] by [UI_SCALE_STEP]. */
const val UI_SCALE_DEFAULT = 80
const val UI_SCALE_MIN = 60
const val UI_SCALE_MAX = 120
const val UI_SCALE_STEP = 5
private const val DAY_END_MIN = 19 * 60 + 30

@Composable
fun LumenTubeTheme(appearance: Appearance = Appearance.NIGHT, uiScale: Int = UI_SCALE_DEFAULT, content: @Composable () -> Unit) {
    var daytime by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(isDaytime()) }
    if (appearance == Appearance.AUTO) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                daytime = isDaytime()
                kotlinx.coroutines.delay(60_000)
            }
        }
    }
    val light = when (appearance) {
        Appearance.DAY -> true
        Appearance.NIGHT -> false
        Appearance.AUTO -> daytime
    }
    val target = if (light) DayColors else NightColors
    if (Palette.colors !== target) Palette.colors = target
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
        // "Taille de l'interface": everything drawn at uiScale % — dp and sp alike, dialogs included
        // (80 % by default, so a 4K TV shows more room around smaller elements).
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scale = uiScale.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX) / 100f
        val scaled = androidx.compose.runtime.remember(density, scale) {
            androidx.compose.ui.unit.Density(density.density * scale, density.fontScale)
        }
        CompositionLocalProvider(
            LocalContentColor provides Palette.TextPrimary,
            androidx.compose.ui.platform.LocalDensity provides scaled,
            content = content,
        )
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
    val RadiusControl = 14.dp
    val RadiusCard = 14.dp
    val RadiusDialog = 24.dp

    /** Smallest text used anywhere (10-foot legibility). */
    val TextMin = 13.sp
}

val ControlShape = RoundedCornerShape(Dimens.RadiusControl)
val CardShape = RoundedCornerShape(Dimens.RadiusCard)
val DialogShape = RoundedCornerShape(Dimens.RadiusDialog)
