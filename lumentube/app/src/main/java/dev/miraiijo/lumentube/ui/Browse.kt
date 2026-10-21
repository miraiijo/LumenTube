@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import dev.miraiijo.lumentube.ChannelHeader
import dev.miraiijo.lumentube.Page
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.VideoRow
import kotlinx.coroutines.delay

private val SHEET_TOP = 48.dp
private val SHEET_HEIGHT = 130.dp
/**
 * Where the focused row's cards are pinned, from the top of the rows: its title (8 + ~21 + 4 dp)
 * plus the row's top padding (12 dp), so the title stays fully visible above the cards.
 */
private val ROW_TITLE = 46.dp
private val CARD_WIDTH = 232.dp
/** Wait before the sheet follows the focus (no image storm while scrolling along a row). */
private const val SHEET_SETTLE_MS = 180L

/**
 * A section (or an opened channel / playlist), like Lumen's Bibliothèque in the side-menu layout:
 * the focused video's sheet (title, channel, duration) over its picture, and the rows below it,
 * the active row pinned under the sheet.
 */
@Composable
fun BrowsePage(
    page: Page,
    title: String?,
    focusTick: Int,
    onSelect: (Video) -> Unit,
    onLoadMore: (VideoRow) -> Unit,
    onRetry: () -> Unit,
    needsAccount: Boolean,
    onSignIn: () -> Unit,
    /** "Réessayer" under an empty page's message. */
    showRetry: Boolean = true,
    /** Long press (or the Menu key) on a card: its menu. */
    onLongPress: (Video) -> Unit = {},
    /** A channel page: its header (avatar, name, S'abonner, orders) instead of the video sheet. */
    channel: ChannelHeader? = null,
    signedIn: Boolean = false,
    onSubscribe: () -> Unit = {},
    onSort: (Int) -> Unit = {},
    /** Room above the sheet (less under the search bar). */
    top: androidx.compose.ui.unit.Dp = SHEET_TOP,
) {
    var focused by remember { mutableStateOf<Video?>(null) }
    var shown by remember { mutableStateOf<Video?>(null) }
    LaunchedEffect(focused) {
        if (shown != null) delay(SHEET_SETTLE_MS)
        shown = focused
    }
    val first = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    LaunchedEffect(focusTick, page.rows.isNotEmpty(), page.error, needsAccount) {
        if (focusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        if (!first.tryRequestFocus()) action.tryRequestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(shown)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(top))
            if (channel != null) {
                ChannelSheet(channel, shown, signedIn, onSubscribe, onSort)
            } else {
                Sheet(shown, title)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    needsAccount -> Message(
                        "Connecte ton compte YouTube pour voir cette page.",
                        button = "Se connecter",
                        buttonModifier = Modifier.focusRequester(action),
                        onClick = onSignIn,
                    )
                    page.rows.isEmpty() && page.loading -> Message("Chargement…")
                    page.rows.isEmpty() -> Message(
                        page.error ?: "Rien à afficher.",
                        button = if (showRetry) "Réessayer" else null,
                        buttonModifier = Modifier.focusRequester(action),
                        onClick = onRetry,
                    )
                    else -> PinnedRowsScroll(ROW_TITLE) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 64.dp),
                        ) {
                            itemsIndexed(page.rows, key = { _, row -> row.key }) { index, row ->
                                VideoRowView(
                                    row,
                                    firstRequester = if (index == 0) first else null,
                                    onFocus = { focused = it },
                                    onSelect = onSelect,
                                    onLoadMore = onLoadMore,
                                    onLongPress = onLongPress,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoRowView(
    row: VideoRow,
    firstRequester: FocusRequester?,
    onFocus: (Video) -> Unit,
    onSelect: (Video) -> Unit,
    onLoadMore: (VideoRow) -> Unit,
    onLongPress: (Video) -> Unit,
) {
    Column {
        Text(
            row.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Dimens.Gutter, top = 8.dp, bottom = 4.dp),
        )
        RowScroll {
            LazyRow(
                // Channel rows: a bit more room above, so the zoomed round avatar stays clear of the title.
                contentPadding = PaddingValues(horizontal = Dimens.Gutter, vertical = if (row.items.all { it.isChannel }) 16.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            ) {
                itemsIndexed(row.items, key = { _, video -> video.key }) { index, video ->
                    VideoCard(
                        video,
                        modifier = if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier,
                        onFocus = {
                            onFocus(video)
                            // Near the end: the next page of the row.
                            if (index >= row.items.size - 4) onLoadMore(row)
                        },
                        onClick = { onSelect(video) },
                        onLongClick = { onLongPress(video) },
                    )
                }
            }
        }
    }
}

/** 16:9 thumbnail (duration badge, watched lane), title and channel below. */
@Composable
fun VideoCard(video: Video, modifier: Modifier = Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    if (video.isChannel) return ChannelCard(video, modifier, onFocus, onClick)
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(CARD_WIDTH)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .menuKey(onLongClick)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocus()
                },
            shape = ClickableSurfaceDefaults.shape(shape = CardShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = FocusScale.Card),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = CardShape),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Tile, focusedContainerColor = Palette.TileFocused),
        ) {
            Box(Modifier.fillMaxSize()) {
                Thumbnail(video.videoId, video.thumbnail, Modifier.fillMaxSize(), custom = video.customThumbnail, small = true)
                val badge = when {
                    video.live -> "EN DIRECT"
                    video.upcoming -> "BIENTÔT"
                    video.durationMs > 0 -> formatDuration(video.durationMs)
                    else -> video.badge
                }
                if (badge != null) {
                    Text(
                        badge,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(if (video.live) Palette.Danger else Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (video.watchedPercent in 1..99) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Palette.Track)) {
                        Box(Modifier.fillMaxWidth(video.watchedPercent / 100f).fillMaxHeight().background(Palette.Highlight))
                    }
                }
            }
        }
        Text(
            video.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) Palette.TextPrimary else Palette.TextPrimary.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (video.channel.isNotBlank()) {
            Text(video.channel, fontSize = 13.sp, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The focused video's picture, top right, melting into the page (Lumen's Bibliothèque backdrop). */
@Composable
private fun Backdrop(video: Video?) {
    // Picture = (video id for the HD version, feed thumbnail as fallback).
    val picture = video?.takeIf { it.videoId != null || it.thumbnail != null }?.let { it.videoId to it.thumbnail }
    val channel = video?.takeIf { it.isChannel }
    Crossfade(targetState = picture to channel, animationSpec = tween(500), label = "backdrop") { (image, shownChannel) ->
        Box(Modifier.fillMaxSize()) {
            if (shownChannel != null) {
                // A channel: its round avatar (a channel picture stretched to 16:9 is a blur).
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = SHEET_TOP, end = Dimens.Gutter * 2)
                        .size(CHANNEL_BACKDROP)
                        .clip(CircleShape)
                        .background(Palette.Tile),
                ) {
                    GlideImage(model = avatarUrl(shownChannel.thumbnail), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            } else if (image != null) {
                // Top-right quarter: the whole picture, melting into the page on its left (towards
                // the details) and at its bottom.
                Box(Modifier.fillMaxWidth(0.5f).aspectRatio(16f / 9f).align(Alignment.TopEnd).then(MeltIntoPage)) {
                    Thumbnail(image.first, image.second, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** The picture fades out itself (alpha mask): left edge towards the details, bottom towards the rows. */
private val MeltIntoPage = Modifier
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black.copy(alpha = 0.35f), 0.45f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        drawRect(Brush.verticalGradient(0.5f to Color.Black, 0.82f to Color.Black.copy(alpha = 0.4f), 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }

/** Title (or the opened channel / playlist), then channel · duration. */
@Composable
private fun Sheet(video: Video?, pageTitle: String?) {
    Box(Modifier.fillMaxWidth().height(SHEET_HEIGHT).padding(start = Dimens.Gutter, end = Dimens.Gutter)) {
        Crossfade(targetState = video, animationSpec = tween(250), label = "sheet") { shown ->
            Column(Modifier.fillMaxWidth(0.55f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pageTitle != null) {
                    Text(pageTitle.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary, maxLines = 1)
                }
                if (shown != null) {
                    Text(shown.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 31.sp, letterSpacing = DisplayTracking)
                    val meta = listOfNotNull(
                        shown.channel.takeIf { it.isNotBlank() },
                        when {
                            shown.live -> "En direct"
                            shown.durationMs > 0 -> formatDuration(shown.durationMs)
                            else -> null
                        },
                        shown.badge?.takeIf { !shown.live && shown.durationMs <= 0 },
                    ).joinToString("  ·  ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The remote's Menu key, when it has one, does what a long press does. */
private fun Modifier.menuKey(action: (() -> Unit)?): Modifier = if (action == null) this else onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.keyCode != android.view.KeyEvent.KEYCODE_MENU) return@onPreviewKeyEvent false
    if (native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0) action()
    true
}

/**
 * A channel page's header: round avatar, name, "S'abonner" / "Abonné", then its orders
 * ("Accueil", "Plus récentes", "Populaires"…). The focused video's title shows under it.
 */
@Composable
private fun ChannelSheet(channel: ChannelHeader, focused: Video?, signedIn: Boolean, onSubscribe: () -> Unit, onSort: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(SHEET_HEIGHT).padding(start = Dimens.Gutter, end = Dimens.Gutter),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.Tile), contentAlignment = Alignment.Center) {
                if (channel.avatar != null) {
                    GlideImage(model = avatarUrl(channel.avatar), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(channel.name.take(1).uppercase(), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(channel.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = DisplayTracking, modifier = Modifier.weight(1f, fill = false))
            if (signedIn && channel.subscribed != null) {
                LumenButton(
                    if (channel.subscribed) "Abonné" else "S'abonner",
                    icon = if (channel.subscribed) LumenIcon.CHECK else LumenIcon.PLUS,
                    containerColor = if (channel.subscribed) Palette.ControlIdle else Palette.Danger,
                    onClick = onSubscribe,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap), verticalAlignment = Alignment.CenterVertically) {
            if (channel.sorts.isNotEmpty()) {
                SortChip("Accueil", channel.sort == -1) { onSort(-1) }
                channel.sorts.forEachIndexed { index, name -> SortChip(name, channel.sort == index) { onSort(index) } }
            }
            if (focused != null) {
                Text(
                    listOfNotNull(focused.title, focused.durationMs.takeIf { it > 0 }?.let(::formatDuration)).joinToString("  ·  "),
                    fontSize = 14.sp,
                    color = Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) =
    LumenButton(label, icon = if (selected) LumenIcon.CHECK else null, containerColor = if (selected) Palette.Selected else Palette.ControlIdle, onClick = onClick)

@Composable
private fun Message(text: String, button: String? = null, buttonModifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(Modifier.padding(start = Dimens.Gutter, top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, fontSize = 16.sp, color = Palette.TextSecondary)
        if (button != null) LumenButton(button, modifier = buttonModifier, onClick = onClick)
    }
}



/** A channel ("Vos chaînes préférées"…): round avatar and name, smaller than a video card. */
@Composable
private fun ChannelCard(video: Video, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(CHANNEL_CARD), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .size(CHANNEL_AVATAR)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocus()
                },
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = CHANNEL_FOCUS_SCALE),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = CircleShape),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Tile, focusedContainerColor = Palette.TileFocused),
        ) {
            GlideImage(model = avatarUrl(video.thumbnail), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            video.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) Palette.TextPrimary else Palette.TextPrimary.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private val CHANNEL_CARD = 120.dp
private val CHANNEL_AVATAR = 96.dp
private val CHANNEL_BACKDROP = 120.dp
private const val CHANNEL_FOCUS_SCALE = 1.06f
