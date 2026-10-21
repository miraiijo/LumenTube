@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalGlideComposeApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.ui.layout.ContentScale

/* Lumen's controls and focus scrolling, shared by every LumenTube screen. */

fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus(FocusDirection.Enter)
} catch (_: IllegalStateException) {
    false
}

/** Primary button, as in Lumen: filled pill, white when focused. */
@Composable
fun LumenButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: LumenIcon? = null,
    containerColor: Color = Palette.ControlIdle,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(Dimens.ControlHeight + 6.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        shape = ButtonDefaults.shape(shape = CircleShape),
        scale = ButtonDefaults.scale(focusedScale = FocusScale.Control, pressedScale = FocusScale.Control),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = Palette.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
    ) {
        icon?.let {
            Icon(it, size = 15.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

/** Always scrolls the focused element to [start] from the top: the active row stays in place. */
private class PinSpec(private val start: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - start
}

/** Scrolls only when the focused element would leave the visible area, keeping margins. */
private class MarginSpec(private val start: Float, private val end: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val visibleEnd = containerSize - end
        return when {
            offset < start -> offset - start
            offset + size > visibleEnd -> minOf(offset + size - visibleEnd, offset - start)
            else -> 0f
        }
    }
}

/** Page rows: the active row pinned at [start] (the sheet above it never moves). */
@Composable
fun PinnedRowsScroll(start: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density, start) { with(density) { PinSpec(start.toPx()) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** A row of cards: side-gutter margins, and it remembers its last focused card. */
@Composable
fun RowScroll(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { MarginSpec(Dimens.Gutter.toPx(), Dimens.Gutter.toPx()) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Box(Modifier.focusRestorer()) { content() }
    }
}

/**
 * A video's picture in HD: YouTube's 1280×720 version (maxresdefault), cropped to its frame.
 * Some videos don't have one (404): the feed's thumbnail is loaded instead. Glide downsamples it to
 * the frame's size, so a small card costs no more memory than before.
 */
@Composable
fun Thumbnail(videoId: String?, fallback: String?, modifier: Modifier = Modifier) {
    val hd = videoId?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
    GlideImage(
        model = hd ?: fallback,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        requestBuilderTransform = { if (hd != null && fallback != null) it.error(it.clone().load(fallback)) else it },
    )
}

/** "12:34" / "1:02:03". */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
