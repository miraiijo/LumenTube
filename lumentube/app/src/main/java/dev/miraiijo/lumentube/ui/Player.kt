@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import dev.miraiijo.lumentube.data.YouTube
import dev.miraiijo.lumentube.player.LumenPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_HIDE_MS = 4_000L
private const val SAVE_EVERY_MS = 30_000L

/**
 * Full-screen player, laid out like Nuvio's: title and channel bottom left, a thin progress bar,
 * round icon buttons (white when focused) and the time on the right, the clock top right,
 * gradients top and bottom. Hidden controls: OK = pause/play, Left/Right = ±10 s, any arrow shows them.
 */
@Composable
fun PlayerScreen(
    videoId: String,
    title: String?,
    channel: String?,
    thumbnail: String?,
    startSeconds: Int,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(videoId) { LumenPlayer(context) }
    var shownTitle by remember(videoId) { mutableStateOf(title.orEmpty()) }
    var shownChannel by remember(videoId) { mutableStateOf(channel.orEmpty()) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var started by remember(videoId) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var live by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(0) }
    var loadTick by remember(videoId) { mutableIntStateOf(0) }
    var retried by remember(videoId) { mutableStateOf(false) }
    /** Seek preview on the progress bar (committed when the arrow is released). */
    var preview by remember { mutableStateOf<Long?>(null) }
    val playPause = remember { FocusRequester() }
    val progress = remember { FocusRequester() }
    val root = remember { FocusRequester() }

    fun show() {
        controls = true
        activity++
    }

    fun togglePlay() {
        player.exo.playWhenReady = !player.exo.playWhenReady
        show()
    }

    fun seekBy(delta: Long) {
        if (live && duration <= 0) return
        val target = (player.exo.currentPosition + delta).coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
        player.exo.seekTo(target)
        position = target
        show()
    }

    // Load (and reload after a 403: SmartTube switches to another YouTube client and retries).
    LaunchedEffect(videoId, loadTick) {
        error = null
        val resumeAt = if (loadTick == 0) startSeconds * 1000L else player.exo.currentPosition
        val info = runCatching { YouTube.formatInfo(videoId) }.getOrNull()
        when {
            info == null -> error = "YouTube ne répond pas."
            info.isUnplayable -> error = info.playabilityReason?.takeIf { it.isNotBlank() } ?: "Cette vidéo ne peut pas être lue."
            else -> {
                if (shownTitle.isBlank()) shownTitle = info.title.orEmpty()
                if (shownChannel.isBlank()) shownChannel = info.author.orEmpty()
                live = info.isLive
                if (!player.open(info, resumeAt)) error = "Pas encore de flux pour cette vidéo (direct à venir ?)."
            }
        }
    }

    DisposableEffect(player) {
        player.onError = { e ->
            if (!retried) {
                retried = true
                YouTubeServiceManager.instance().switchNextClient()
                loadTick++
            } else {
                error = e.cause?.message ?: e.message ?: "Erreur de lecture."
            }
        }
        val listener = object : Player.EventListener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_READY) started = true
                if (playbackState == Player.STATE_ENDED) onExit()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.exo.addListener(listener)
        onDispose {
            val seconds = player.exo.currentPosition / 1000f
            // Outlives the screen (its own scope is cancelled with it).
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { YouTube.savePosition(videoId, seconds) }
            player.exo.removeListener(listener)
            player.release()
        }
    }

    // Time line, refreshed while the screen is up; YouTube history saved every 30 s.
    LaunchedEffect(player) {
        var lastSave = 0L
        while (true) {
            position = player.exo.currentPosition
            duration = player.exo.duration.coerceAtLeast(0L)
            buffered = player.exo.bufferedPosition
            if (isPlaying && System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) {
                lastSave = System.currentTimeMillis()
                launch { YouTube.savePosition(videoId, position / 1000f) }
            }
            delay(500)
        }
    }

    // Controls hide after a while when playing (never while paused or loading).
    LaunchedEffect(activity, isPlaying, controls) {
        if (controls && isPlaying) {
            delay(CONTROLS_HIDE_MS)
            controls = false
        }
    }
    LaunchedEffect(controls) {
        withFrameNanos { }
        if (controls) playPause.tryRequestFocus() else root.tryRequestFocus()
    }

    BackHandler {
        if (controls && isPlaying) controls = false else onExit()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(root)
            // The page under the player never gets the focus.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .focusable()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                if (controls) {
                    activity++
                    return@onPreviewKeyEvent false
                }
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-SEEK_STEP_MS); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(SEEK_STEP_MS); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { show(); true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player.exo
                }
            },
            update = { it.player = player.exo },
            modifier = Modifier.fillMaxSize(),
        )

        // Before the first frame: the thumbnail, dimmed, with the title.
        AnimatedVisibility(visible = !started && error == null, enter = fadeIn(), exit = fadeOut(tween(400))) {
            Box(Modifier.fillMaxSize()) {
                Thumbnail(videoId, thumbnail, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoadingDots()
                    Text(shownTitle, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.9f))
                }
            }
        }
        if (started && buffering && error == null) LoadingDots(Modifier.align(Alignment.Center))

        AnimatedVisibility(visible = controls && error == null, enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
            Controls(
                title = shownTitle,
                channel = shownChannel,
                isPlaying = isPlaying || (player.exo.playWhenReady && buffering),
                live = live,
                position = preview ?: position,
                duration = duration,
                buffered = buffered,
                playPause = playPause,
                progress = progress,
                onPlayPause = ::togglePlay,
                onSeekBy = ::seekBy,
                onPreview = { delta ->
                    preview = ((preview ?: position) + delta).coerceIn(0L, duration.coerceAtLeast(0L))
                    activity++
                },
                onCommit = {
                    preview?.let { player.exo.seekTo(it); position = it }
                    preview = null
                },
                onHide = { controls = false },
            )
        }

        if (error != null) {
            Column(
                Modifier.align(Alignment.Center).background(Palette.Panel.copy(alpha = 0.95f), DialogShape).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Lecture impossible", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(error.orEmpty(), fontSize = 15.sp, color = Palette.TextSecondary)
                val retry = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                    LumenButton("Réessayer", icon = LumenIcon.REFRESH, modifier = Modifier.focusRequester(retry)) {
                        retried = false
                        YouTubeServiceManager.instance().switchNextClientNow()
                        loadTick++
                    }
                    LumenButton("Fermer", onClick = onExit)
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    retry.tryRequestFocus()
                }
            }
        }
    }
}

@Composable
private fun Controls(
    title: String,
    channel: String,
    isPlaying: Boolean,
    live: Boolean,
    position: Long,
    duration: Long,
    buffered: Long,
    playPause: FocusRequester,
    progress: FocusRequester,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    onHide: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
        Clock(Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = Dimens.Gutter))

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = Dimens.Gutter, vertical = 28.dp),
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = DisplayTracking)
            if (channel.isNotBlank()) Text(channel, fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (live) {
                Text("EN DIRECT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 8.dp).background(Palette.Danger, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Spacer(Modifier.height(18.dp))
            if (!live || duration > 0) {
                ProgressBar(position, duration, buffered, progress, down = playPause, onPreview = onPreview, onCommit = onCommit, onClick = onPlayPause, onUp = onHide)
                Spacer(Modifier.height(14.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(if (isPlaying) LumenIcon.PAUSE else LumenIcon.PLAY, Modifier.focusRequester(playPause), up = progress, onDown = onHide, onClick = onPlayPause)
                    if (!live) {
                        ControlButton(LumenIcon.REWIND, up = progress, onDown = onHide) { onSeekBy(-SEEK_STEP_MS) }
                        ControlButton(LumenIcon.FORWARD, up = progress, onDown = onHide) { onSeekBy(SEEK_STEP_MS) }
                    }
                }
                Text(
                    if (live && duration <= 0) "En direct" else "${formatTime(position)} / ${formatTime(duration)}",
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

/** Nuvio's round icon button: transparent, white with a black icon when focused. */
@Composable
private fun ControlButton(icon: LumenIcon, modifier: Modifier = Modifier, up: FocusRequester? = null, onDown: (() -> Unit)? = null, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(52.dp)
            .then(if (up != null) Modifier.focusProperties { this.up = up } else Modifier)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onDown != null && native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                    onDown()
                    true
                } else {
                    false
                }
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape),
    ) {
        Icon(icon, size = 24.dp)
    }
}

/** Thin bar (buffered + played), thicker when focused: Left/Right preview a seek, released = seek. */
@Composable
private fun ProgressBar(
    position: Long,
    duration: Long,
    buffered: Long,
    requester: FocusRequester,
    down: FocusRequester,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    onClick: () -> Unit,
    onUp: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val loaded = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPlayed by animateFloatAsState(played, tween(100), label = "played")
    val height by animateFloatAsState(if (focused) 8f else 4f, tween(150), label = "bar-height")
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .focusRequester(requester)
            .focusProperties { this.down = down }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.action) {
                    AndroidKeyEvent.ACTION_DOWN -> when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onPreview(-SEEK_STEP_MS * (1 + native.repeatCount / 5)); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onPreview(SEEK_STEP_MS * (1 + native.repeatCount / 5)); true }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { if (native.repeatCount == 0) onClick(); true }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
                        else -> false
                    }
                    AndroidKeyEvent.ACTION_UP -> when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onCommit(); true }
                        else -> false
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barShape = RoundedCornerShape(50)
        Box(Modifier.fillMaxWidth().height(height.dp).clip(barShape).background(Palette.Track)) {
            Box(Modifier.fillMaxWidth(loaded).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
            Box(Modifier.fillMaxWidth(shownPlayed).fillMaxHeight().background(Color.White))
        }
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(10_000)
        }
    }
    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = modifier)
}

/** Three pulsing dots (loading). */
@Composable
private fun LoadingDots(modifier: Modifier = Modifier) {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            phase = (phase + 1) % 3
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { i ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == phase) 1f else 0.35f)))
        }
    }
}

private fun formatTime(ms: Long): String = formatDuration(ms).ifEmpty { "0:00" }
