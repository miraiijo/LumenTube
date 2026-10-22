@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.em
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
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow

/**
 * Room kept above a focused card when the rows scroll: its row title (8 + ~21 + 4 dp) plus the
 * row's top padding (12 dp), so the title stays fully visible above the cards.
 */
private val ROW_TITLE = 46.dp
/** Room kept below a focused card: its title and channel (and the zoom). */
private val CARD_CAPTION = 64.dp
/** Default card width; on a page it follows the screen (as many cards as fit at ~[CARD_SLOT] fill the width). */
private val CARD_WIDTH = 204.dp
/** Width per card used to count the cards of a row: 4 at 100 %, 5 at 80 %, 6 at 60 %, 3 at 120 %. */
private val CARD_SLOT = 232.dp

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
    /** Room above the page's own title / channel header. */
    top: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val first = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    LaunchedEffect(focusTick, page.rows.isNotEmpty(), page.error, needsAccount) {
        if (focusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        if (!first.tryRequestFocus()) action.tryRequestFocus()
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(top))
        when {
            channel != null -> ChannelSheet(channel, signedIn, onSubscribe, onSort)
            title != null -> Text(
                title,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = DisplayTracking,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 20.dp, bottom = 6.dp),
            )
        }
        // The cards share the screen's width: the same layout on any TV, box or density.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val perRow = (maxWidth / CARD_SLOT).toInt().coerceIn(3, 7)
            val cardWidth = ((maxWidth - Dimens.Gutter * 2 - Dimens.CardGap * (perRow - 1)) / perRow).coerceAtLeast(120.dp)
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
                else -> RowsScroll(ROW_TITLE, CARD_CAPTION) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 64.dp),
                    ) {
                        itemsIndexed(page.rows, key = { _, row -> row.key }, contentType = { _, _ -> 0 }) { index, row ->
                            VideoRowView(
                                row,
                                cardWidth = cardWidth,
                                firstRequester = if (index == 0) first else null,
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

@Composable
private fun VideoRowView(
    row: VideoRow,
    cardWidth: androidx.compose.ui.unit.Dp,
    firstRequester: FocusRequester?,
    onSelect: (Video) -> Unit,
    onLoadMore: (VideoRow) -> Unit,
    onLongPress: (Video) -> Unit,
) {
    Column {
        Text(
            row.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
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
                itemsIndexed(row.items, key = { _, video -> video.key }, contentType = { _, video -> if (video.isChannel) 1 else 0 }) { index, video ->
                    VideoCard(
                        video,
                        width = cardWidth,
                        modifier = if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier,
                        onFocus = {
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
fun VideoCard(
    video: Video,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = CARD_WIDTH,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    if (video.isChannel) return ChannelCard(video, modifier, onFocus, onClick)
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
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
            // The redesign's focus: a ring around the card, a small gap from it, a light zoom.
            scale = ClickableSurfaceDefaults.scale(focusedScale = FocusScale.CardRing),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.5.dp, Palette.FocusRing), inset = 4.dp, shape = CardShape),
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
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (video.watchedPercent in 1..99) 14.dp else 8.dp)
                            .background(if (video.live) Palette.Danger else Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (video.watchedPercent in 1..99) {
                    // Watched: a thin white lane inside the picture (always on the artwork).
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.3f)),
                    ) {
                        Box(Modifier.fillMaxWidth(video.watchedPercent / 100f).fillMaxHeight().background(Color.White))
                    }
                }
            }
        }
        Text(
            video.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Palette.TextPrimary else Palette.TextPrimary.copy(alpha = 0.88f),
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

/** The remote's Menu key, when it has one, does what a long press does. */
private fun Modifier.menuKey(action: (() -> Unit)?): Modifier = if (action == null) this else onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.keyCode != android.view.KeyEvent.KEYCODE_MENU) return@onPreviewKeyEvent false
    if (native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0) action()
    true
}

/**
 * A channel page's header, like Lumen's detail page: "CHAÎNE" above its round avatar and name in
 * large, then "S'abonner" / "Abonné" and its orders (Accueil, Plus récentes, Populaires…).
 */
@Composable
private fun ChannelSheet(channel: ChannelHeader, signedIn: Boolean, onSubscribe: () -> Unit, onSort: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 36.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(66.dp).clip(CircleShape).background(Palette.Tile), contentAlignment = Alignment.Center) {
                if (channel.avatar != null) {
                    GlideImage(model = avatarUrl(channel.avatar), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(channel.name.take(1).uppercase(), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column {
                Text("CHAÎNE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em, color = Palette.TextSecondary)
                Text(channel.name, fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = (-0.035).em)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap), verticalAlignment = Alignment.CenterVertically) {
            if (signedIn && channel.subscribed != null) {
                LumenButton(
                    if (channel.subscribed) "Abonné" else "S'abonner",
                    icon = if (channel.subscribed) LumenIcon.CHECK else LumenIcon.PLUS,
                    containerColor = if (channel.subscribed) Palette.ControlIdle else Palette.Danger,
                    onClick = onSubscribe,
                )
            }
            if (channel.sorts.isNotEmpty()) {
                if (signedIn && channel.subscribed != null) Spacer(Modifier.width(12.dp))
                SortChip("Accueil", channel.sort == -1) { onSort(-1) }
                channel.sorts.forEachIndexed { index, name -> SortChip(name, channel.sort == index) { onSort(index) } }
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
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Palette.FocusRing), shape = CircleShape),
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
private const val CHANNEL_FOCUS_SCALE = 1.06f
