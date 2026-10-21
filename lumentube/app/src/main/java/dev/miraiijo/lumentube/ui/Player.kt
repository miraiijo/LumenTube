@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
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
import com.google.android.exoplayer2.text.CaptionStyleCompat
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import dev.miraiijo.lumentube.data.Chapter
import dev.miraiijo.lumentube.data.Details
import dev.miraiijo.lumentube.data.Segment
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.YouTube
import dev.miraiijo.lumentube.player.LumenPlayer
import dev.miraiijo.lumentube.player.PlayerPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_HIDE_MS = 4_000L
private const val SAVE_EVERY_MS = 30_000L
private const val SKIP_NOTICE_MS = 3_000L
private const val UP_NEXT_SECONDS = 10
private const val SEEK_LINE_MS = 1_500L
/** Pause after the last Left/Right before seeking (the presses in between add up). */
private const val SEEK_COMMIT_MS = 600L
/** adb logcat -s LumenTube.Timing: how long a video takes to start, step by step. */
private const val TIMING_TAG = "LumenTube.Timing"

private fun formatsKind(info: com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo) = when {
    info.containsDashFormats() -> "DASH"
    info.containsSabrFormats() -> "SABR"
    info.isLive -> "live"
    else -> "URL"
}
private val PREVIEW_WIDTH = 256.dp
/** Subtitle height, as a share of the video's height (ExoPlayer's default is 0.0533). */
private const val SUBTITLE_SIZE = 0.042f

/**
 * Full-screen player, laid out like Nuvio's: title and channel bottom left, a thin progress bar,
 * round icon buttons (white when focused) and the time on the right, the clock top right,
 * gradients top and bottom. Hidden controls: OK or Up/Down shows them, Left/Right = ±10 s.
 */
@Composable
fun PlayerScreen(
    videoId: String,
    title: String?,
    channel: String?,
    thumbnail: String?,
    startSeconds: Int,
    /** The card played (playlist context for the next video, like), null for a link. */
    item: MediaItem?,
    signedIn: Boolean,
    onNext: (Video) -> Unit,
    onExit: () -> Unit,
    /** In-app picture-in-picture: small window in a corner, no controls, browsing goes on. */
    minimized: Boolean = false,
    onMinimize: () -> Unit = {},
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val isMini by rememberUpdatedState(minimized)
    val playNext by rememberUpdatedState(onNext)
    val context = LocalContext.current
    val prefs = remember { PlayerPrefs(context) }
    val player = remember(videoId) { LumenPlayer(context).also { it.setQuality(prefs.quality) } }
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
    val settingsButton = remember { FocusRequester() }
    var swallowOkUp by remember { mutableStateOf(false) }
    /** Incremented on each Left/Right with the controls hidden: the time line shows a moment. */
    var seekFlash by remember { mutableIntStateOf(0) }
    var seekLineShown by remember { mutableStateOf(false) }
    /** Target of the Left/Right presses not applied yet. */
    var pendingSeek by remember(videoId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(seekFlash) {
        if (seekFlash == 0) return@LaunchedEffect
        // The presses stopped for a moment: one seek to where they lead.
        delay(SEEK_COMMIT_MS)
        pendingSeek?.let {
            player.exo.seekTo(it)
            position = it
        }
        pendingSeek = null
    }
    LaunchedEffect(seekFlash) {
        if (seekFlash == 0) return@LaunchedEffect
        seekLineShown = true
        delay(SEEK_LINE_MS)
        seekLineShown = false
    }
    /** Settings panel level shown (null = closed). */
    var menu by remember(videoId) { mutableStateOf<PlayerMenu?>(null) }
    /** Level the panel was opened on: Settings button = the main list, subtitles button = its list. */
    var menuRoot by remember(videoId) { mutableStateOf(PlayerMenu.MAIN) }
    /** Incremented when the panel closes: the focus goes back to the settings button. */
    var menuClosed by remember { mutableIntStateOf(0) }
    /** Incremented when the tracks change: the panel's lists follow. */
    var tracksTick by remember(videoId) { mutableIntStateOf(0) }
    var subtitlesRestored by remember(videoId) { mutableStateOf(false) }
    var sponsorBlock by remember { mutableStateOf(prefs.sponsorBlock) }
    var segments by remember(videoId) { mutableStateOf(emptyList<Segment>()) }
    var skipped by remember(videoId) { mutableStateOf(emptySet<Segment>()) }
    var skipNotice by remember(videoId) { mutableStateOf<String?>(null) }
    var details by remember(videoId) { mutableStateOf<Details?>(null) }
    val chapters = details?.chapters.orEmpty()
    var liked by remember(videoId) { mutableStateOf(false) }
    var subscribed by remember(videoId) { mutableStateOf(false) }
    var watchLaterDone by remember(videoId) { mutableStateOf(false) }
    var storyboard by remember(videoId) { mutableStateOf<MediaItemStoryboard?>(null) }
    var pictureFormat by remember { mutableIntStateOf(prefs.pictureFormat) }
    /** Video ended with a next one to play: the "À suivre" card counts down. */
    var upNext by remember(videoId) { mutableStateOf<Video?>(null) }
    /** Formats received: the side requests (SponsorBlock, details) start only then, so they never
     *  compete with the one the picture waits for. */
    var infoReady by remember(videoId) { mutableStateOf(false) }
    var openedAt by remember(videoId) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun closeMenu() {
        if (menu == null) return
        menu = null
        menuClosed++
    }

    fun show() {
        controls = true
        activity++
    }

    fun togglePlay() {
        player.exo.playWhenReady = !player.exo.playWhenReady
        show()
    }

    /** ±10 s from the buttons: immediate, controls stay shown. */
    fun seekBy(delta: Long) {
        if (live && duration <= 0) return
        val target = (player.exo.currentPosition + delta).coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
        player.exo.seekTo(target)
        position = target
        show()
    }

    /**
     * Left/Right with the controls hidden: the presses add up into one target (10 s each, faster
     * when the key is held), shown on the time line with its picture; the player seeks once,
     * when the presses stop — not a reload per press.
     */
    fun nudge(direction: Int, repeatCount: Int) {
        if (live && duration <= 0) return
        val step = SEEK_STEP_MS * when {
            repeatCount >= 20 -> 6
            repeatCount >= 8 -> 3
            else -> 1
        }
        val from = pendingSeek ?: player.exo.currentPosition
        pendingSeek = (from + direction * step).coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
        seekFlash++
    }

    // Load (and reload after a 403: SmartTube switches to another YouTube client and retries).
    LaunchedEffect(videoId, loadTick) {
        error = null
        val resumeAt = if (loadTick == 0) startSeconds * 1000L else player.exo.currentPosition
        openedAt = android.os.SystemClock.elapsedRealtime()
        val info = runCatching { YouTube.formatInfo(videoId) }.getOrNull()
        android.util.Log.i(TIMING_TAG, "$videoId: formats in ${android.os.SystemClock.elapsedRealtime() - openedAt} ms (${info?.let { formatsKind(it) } ?: "none"})")
        infoReady = true
        when {
            info == null -> error = "YouTube ne répond pas."
            info.isUnplayable -> error = info.playabilityReason?.takeIf { it.isNotBlank() } ?: "Cette vidéo ne peut pas être lue."
            else -> {
                if (shownTitle.isBlank()) shownTitle = info.title.orEmpty()
                if (shownChannel.isBlank()) shownChannel = info.author.orEmpty()
                live = info.isLive
                player.setVolumeLevel(if (prefs.stableVolume) info.volumeLevel else 1f)
                if (!player.open(info, resumeAt)) error = "Pas encore de flux pour cette vidéo (direct à venir ?)."
                if (storyboard == null && !info.isLive) storyboard = YouTube.storyboard(info)
            }
        }
    }

    DisposableEffect(player) {
        player.onTracksChanged = {
            tracksTick++
            // Subtitles: the last language chosen, when this video has it (once, at the start).
            val tracks = player.subtitleTracks()
            if (!subtitlesRestored && tracks.isNotEmpty()) {
                subtitlesRestored = true
                // -1 (off) is applied too: no track turns on by itself as the manifest's default.
                player.selectSubtitle(prefs.subtitles?.let(tracks::indexOf) ?: -1)
            }
        }
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
                if (playbackState == Player.STATE_READY && !started) {
                    started = true
                    android.util.Log.i(TIMING_TAG, "$videoId: first frame ready ${android.os.SystemClock.elapsedRealtime() - openedAt} ms after the request")
                }
                if (playbackState == Player.STATE_ENDED) {
                    val next = details?.next
                    when {
                        !prefs.autoplay || next == null -> onExit()
                        // Small window: no card to show, the next one starts right away.
                        isMini -> playNext(next)
                        else -> upNext = next
                    }
                }
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

    // Remote's Home (or another app on top): pause and save the position; back in the app, the
    // video waits paused with its controls shown.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    player.exo.playWhenReady = false
                    val seconds = player.exo.currentPosition / 1000f
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { YouTube.savePosition(videoId, seconds) }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> show()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SponsorBlock segments and chapters, fetched next to the video (they never delay it).
    LaunchedEffect(videoId, sponsorBlock, infoReady) {
        if (!infoReady) return@LaunchedEffect
        segments = if (sponsorBlock) YouTube.skipSegments(videoId) else emptyList()
    }
    LaunchedEffect(videoId, infoReady) {
        if (!infoReady) return@LaunchedEffect
        details = YouTube.details(videoId, item)
        liked = details?.liked == true
        subscribed = details?.subscribed == true
    }
    LaunchedEffect(skipNotice) {
        if (skipNotice != null) {
            delay(SKIP_NOTICE_MS)
            skipNotice = null
        }
    }

    // Time line, refreshed while the screen is up; YouTube history saved every 30 s.
    LaunchedEffect(player) {
        var lastSave = 0L
        while (true) {
            position = player.exo.currentPosition
            duration = player.exo.duration.coerceAtLeast(0L)
            buffered = player.exo.bufferedPosition
            // SponsorBlock: each segment skipped once (seeking back into it plays it).
            if (sponsorBlock && player.exo.playWhenReady) {
                val at = position
                segments.firstOrNull { at >= it.startMs && at < it.endMs - 500 && it !in skipped }?.let { segment ->
                    skipped = skipped + segment
                    player.exo.seekTo(segment.endMs)
                    position = segment.endMs
                    skipNotice = "Passé : ${segment.label}"
                }
            }
            if (isPlaying && System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) {
                lastSave = System.currentTimeMillis()
                launch { YouTube.savePosition(videoId, position / 1000f) }
            }
            delay(500)
        }
    }

    // Controls hide after a while when playing (never while paused or loading).
    LaunchedEffect(activity, isPlaying, controls, menu) {
        if (controls && isPlaying && menu == null) {
            delay(CONTROLS_HIDE_MS)
            controls = false
        }
    }
    LaunchedEffect(controls, minimized) {
        withFrameNanos { }
        if (upNext != null || minimized) return@LaunchedEffect
        if (controls) playPause.tryRequestFocus() else root.tryRequestFocus()
    }
    // "À suivre" counting down when the window shrinks: the next video starts now.
    LaunchedEffect(minimized, upNext) {
        val next = upNext
        if (minimized && next != null) playNext(next)
    }
    // Into the small window: no controls nor panel. Back to full screen: controls shown.
    LaunchedEffect(minimized) {
        if (minimized) {
            menu = null
            controls = false
        } else {
            show()
        }
    }

    fun toggleLike() {
        val target = !liked
        liked = target
        scope.launch { if (!YouTube.setLiked(videoId, item, target)) liked = !target }
        show()
    }

    fun toggleSubscribe() {
        val channelId = details?.channelId ?: return
        val target = !subscribed
        subscribed = target
        details = details?.copy(subscribed = target)
        scope.launch {
            if (!YouTube.setSubscribed(channelId, target)) {
                subscribed = !target
                details = details?.copy(subscribed = !target)
            }
        }
    }

    fun addToWatchLater() {
        if (watchLaterDone) return
        watchLaterDone = true
        scope.launch { if (!YouTube.watchLater(videoId)) watchLaterDone = false }
    }

    BackHandler(enabled = !minimized) {
        if (controls && isPlaying) controls = false else onExit()
    }
    // Declared after: while the panel is open, Back goes up one level, then closes it.
    BackHandler(enabled = menu != null && !minimized) {
        // Up one level, or closed when it's the level the panel was opened on.
        if (menu == menuRoot) closeMenu() else menu = PlayerMenu.MAIN
    }
    LaunchedEffect(menuClosed) {
        if (menuClosed == 0) return@LaunchedEffect
        withFrameNanos { }
        settingsButton.tryRequestFocus()
    }

    Box(
        modifier
            .background(Color.Black)
            .then(
                // Small window: not focusable, the page keeps the remote.
                if (minimized) Modifier else Modifier
                    // The page under the player never gets the focus.
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
                    // The requester sits on the focusable box itself, not on the group: on the group,
                    // a request entered its children, i.e. the controls fading out, and the focus was
                    // lost with them (Back while the progress bar was shown).
                    .focusRequester(root)
                    .focusable(),
            )
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val isOk = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == AndroidKeyEvent.KEYCODE_ENTER
                // The OK that showed the controls: its release must not click the play button that
                // just took the focus (it would pause).
                if (native.action == AndroidKeyEvent.ACTION_UP && isOk && swallowOkUp) {
                    swallowOkUp = false
                    return@onPreviewKeyEvent true
                }
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                // The "À suivre" card has its own buttons.
                if (upNext != null) return@onPreviewKeyEvent false
                if (controls) {
                    activity++
                    return@onPreviewKeyEvent false
                }
                when (native.keyCode) {
                    // OK only shows the controls (play / pause is the focused button then); the remote's
                    // play-pause key still toggles directly.
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { swallowOkUp = true; show(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); true }
                    // Seek without bringing the buttons (nor their focus): only the time line flashes.
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { nudge(-1, native.repeatCount); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { nudge(1, native.repeatCount); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { show(); true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = resizeModeOf(pictureFormat)
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Subtitles readable from the couch: white on a translucent band, a bit larger.
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                0x99000000.toInt(),
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_NONE,
                                android.graphics.Color.TRANSPARENT,
                                null,
                            ),
                        )
                        setFractionalTextSize(SUBTITLE_SIZE)
                    }
                    this.player = player.exo
                    // Registered after PlayerView's own text output, so these cues are the ones shown:
                    // YouTube's cues carry positions (auto captions often "left, 0 %"), which put them
                    // off-center or off-screen. Rebuilt as plain cues: centered, at the bottom.
                    val centered = CenteredCues()
                    val view = subtitleView
                    player.exo.addTextOutput { cues -> view?.setCues(centered.of(cues)) }
                }
            },
            update = {
                it.player = player.exo
                it.resizeMode = resizeModeOf(pictureFormat)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Before the first frame: the thumbnail, dimmed.
        AnimatedVisibility(visible = !started && error == null, enter = fadeIn(), exit = fadeOut(tween(400))) {
            Box(Modifier.fillMaxSize()) {
                Thumbnail(videoId, thumbnail, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                // Just the dots: the title is already in the controls shown at the bottom.
                LoadingDots(Modifier.align(Alignment.Center))
            }
        }
        if (started && buffering && error == null) LoadingDots(Modifier.align(Alignment.Center))

        AnimatedVisibility(visible = controls && error == null && !minimized, enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
            val chapter = chapters.lastOrNull { it.startMs <= (preview ?: position) }?.title
            Controls(
                title = shownTitle,
                channel = listOfNotNull(shownChannel.takeIf { it.isNotBlank() }, chapter?.takeIf { it.isNotBlank() }).joinToString(" · "),
                isPlaying = isPlaying || (player.exo.playWhenReady && buffering),
                live = live,
                position = preview ?: position,
                duration = duration,
                buffered = buffered,
                playPause = playPause,
                progress = progress,
                onPlayPause = ::togglePlay,
                onSeekBy = { seekBy(it) },
                onPreview = { delta ->
                    preview = ((preview ?: position) + delta).coerceIn(0L, duration.coerceAtLeast(0L))
                    activity++
                },
                onCommit = {
                    preview?.let { player.exo.seekTo(it); position = it }
                    preview = null
                },
                onHide = { controls = false },
                segments = segments,
                chapters = chapters,
                hasSubtitles = remember(tracksTick) { player.subtitleTracks().isNotEmpty() },
                settingsButton = settingsButton,
                onSubtitles = {
                    menuRoot = PlayerMenu.SUBTITLES
                    menu = PlayerMenu.SUBTITLES
                },
                onSettings = {
                    menuRoot = PlayerMenu.MAIN
                    menu = PlayerMenu.MAIN
                },
                onMinimize = onMinimize,
                showLike = signedIn && !live,
                liked = liked,
                onLike = ::toggleLike,
                storyboard = storyboard,
                previewing = preview != null,
            )
        }

        // Left/Right with the controls hidden: the time line alone, at the bottom, not focusable.
        AnimatedVisibility(
            visible = seekLineShown && !controls && !minimized && error == null,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SeekLine(pendingSeek ?: position, duration, buffered, storyboard = storyboard.takeIf { pendingSeek != null })
        }

        // SponsorBlock: what was just skipped, top left.
        AnimatedVisibility(
            visible = skipNotice != null && !minimized,
            enter = fadeIn(),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.TopStart).padding(top = 28.dp, start = Dimens.Gutter),
        ) {
            Text(
                skipNotice.orEmpty(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), ControlShape).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        menu?.let { level ->
            PlayerMenuPanel(
                menu = level,
                player = player,
                prefs = prefs,
                live = live,
                tracksTick = tracksTick,
                sponsorBlock = sponsorBlock,
                onSponsorBlock = {
                    sponsorBlock = it
                    prefs.sponsorBlock = it
                },
                pictureFormat = pictureFormat,
                onPictureFormat = {
                    pictureFormat = it
                    prefs.pictureFormat = it
                },
                actions = VideoActions(
                    details = details,
                    signedIn = signedIn,
                    watchLaterDone = watchLaterDone,
                    onSubscribe = ::toggleSubscribe,
                    onWatchLater = ::addToWatchLater,
                ),
                // A choice made in a list opened directly (subtitles button) closes the panel.
                onMenu = { next -> if (next == null || (next == PlayerMenu.MAIN && menuRoot != PlayerMenu.MAIN)) closeMenu() else menu = next },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        upNext?.takeIf { !minimized }?.let { next ->
            UpNextCard(
                next,
                onPlay = { onNext(next) },
                onCancel = onExit,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Dimens.Gutter, bottom = 48.dp),
            )
        }

        // Small window: how to get it back.
        if (minimized && error == null) {
            Text(
                "⏯  Plein écran",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f), ControlShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (error != null && minimized) {
            Text("Lecture impossible", fontSize = 14.sp, color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else if (error != null) {
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
    segments: List<Segment>,
    chapters: List<Chapter>,
    hasSubtitles: Boolean,
    settingsButton: FocusRequester,
    onSubtitles: () -> Unit,
    onSettings: () -> Unit,
    showLike: Boolean,
    liked: Boolean,
    onLike: () -> Unit,
    storyboard: MediaItemStoryboard?,
    previewing: Boolean,
    onMinimize: () -> Unit,
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
                // Seek preview: the picture at the aimed position, above the bar.
                if (previewing && storyboard != null && duration > 0) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
                        val x = (maxWidth * fraction - PREVIEW_WIDTH / 2).coerceIn(0.dp, maxWidth - PREVIEW_WIDTH)
                        Column(Modifier.offset(x = x).width(PREVIEW_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
                            StoryboardFrame(storyboard, position, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ControlShape).background(Color.Black))
                            Text(formatTime(position), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                ProgressBar(position, duration, buffered, progress, down = playPause, onPreview = onPreview, onCommit = onCommit, onClick = onPlayPause, onUp = onHide, segments = segments, chapters = chapters)
                Spacer(Modifier.height(14.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(if (isPlaying) LumenIcon.PAUSE else LumenIcon.PLAY, Modifier.focusRequester(playPause), up = progress, onDown = onHide, onClick = onPlayPause)
                    if (!live) {
                        ControlButton(LumenIcon.REWIND, up = progress, onDown = onHide) { onSeekBy(-SEEK_STEP_MS) }
                        ControlButton(LumenIcon.FORWARD, up = progress, onDown = onHide) { onSeekBy(SEEK_STEP_MS) }
                    }
                    if (showLike) ControlButton(LumenIcon.THUMB_UP, up = progress, onDown = onHide, active = liked, onClick = onLike)
                    if (hasSubtitles) ControlButton(LumenIcon.SUBTITLES, up = progress, onDown = onHide, onClick = onSubtitles)
                    // Small window in a corner: keep watching while browsing ("Lecture en cours" in the menu brings it back).
                    ControlButton(LumenIcon.PIP, up = progress, onDown = onHide, onClick = onMinimize)
                    // Settings always last.
                    ControlButton(LumenIcon.SETTINGS, Modifier.focusRequester(settingsButton), up = progress, onDown = onHide, onClick = onSettings)
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
private fun ControlButton(
    icon: LumenIcon,
    modifier: Modifier = Modifier,
    up: FocusRequester? = null,
    onDown: (() -> Unit)? = null,
    /** On (liked…): a light fill when not focused. */
    active: Boolean = false,
    onClick: () -> Unit,
) {
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
            containerColor = if (active) Palette.ControlOnArt else Color.Transparent,
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
    segments: List<Segment>,
    chapters: List<Chapter>,
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
            if (duration > 0 && (segments.isNotEmpty() || chapters.size > 1)) {
                Canvas(Modifier.matchParentSize()) {
                    // SponsorBlock segments in amber, chapter starts as small gaps.
                    segments.forEach { segment ->
                        val start = size.width * (segment.startMs.toFloat() / duration).coerceIn(0f, 1f)
                        val end = size.width * (segment.endMs.toFloat() / duration).coerceIn(0f, 1f)
                        drawRect(Palette.Warning, topLeft = Offset(start, 0f), size = Size(end - start, size.height))
                    }
                    val gap = 3.dp.toPx()
                    chapters.drop(1).forEach { chapter ->
                        val x = size.width * (chapter.startMs.toFloat() / duration).coerceIn(0f, 1f)
                        drawRect(Color.Black, topLeft = Offset(x - gap / 2, 0f), size = Size(gap, size.height))
                    }
                }
            }
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

/** "À suivre": the next video, played when the countdown ends (OK: now, Annuler: back to the page). */
@Composable
private fun UpNextCard(next: Video, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var seconds by remember(next.key) { mutableIntStateOf(UP_NEXT_SECONDS) }
    val playNow = remember { FocusRequester() }
    LaunchedEffect(next.key) {
        withFrameNanos { }
        playNow.tryRequestFocus()
        while (seconds > 0) {
            delay(1_000)
            seconds--
        }
        onPlay()
    }
    Row(
        modifier.background(Palette.Panel.copy(alpha = 0.95f), DialogShape).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(240.dp).aspectRatio(16f / 9f).clip(ControlShape).background(Palette.Tile)) {
            Thumbnail(next.videoId, next.thumbnail, Modifier.fillMaxSize())
        }
        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("À suivre dans $seconds s", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary)
            Text(next.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
            if (next.channel.isNotBlank()) Text(next.channel, fontSize = 14.sp, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap), modifier = Modifier.padding(top = 6.dp)) {
                LumenButton("Lire maintenant", icon = LumenIcon.PLAY, modifier = Modifier.focusRequester(playNow), onClick = onPlay)
                LumenButton("Annuler", onClick = onCancel)
            }
        }
    }
}

/** The storyboard picture at [positionMs]: one tile of YouTube's sprite sheets. */
@Composable
private fun StoryboardFrame(storyboard: MediaItemStoryboard, positionMs: Long, modifier: Modifier) {
    val size = storyboard.groupSize
    val groupMs = storyboard.groupDurationMS
    if (size == null || groupMs <= 0 || size.durationEachMS <= 0 || size.colCount <= 0 || size.rowCount <= 0) return
    val group = (positionMs / groupMs).toInt()
    val index = ((positionMs % groupMs) / size.durationEachMS).toInt().coerceAtMost(size.colCount * size.rowCount - 1)
    val tile = TileCrop(index % size.colCount, index / size.colCount, size.colCount, size.rowCount, size.width, size.height)
    GlideImage(
        model = storyboard.getGroupUrl(group),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier,
        requestBuilderTransform = { it.transform(tile) },
    )
}

/** Cuts one tile out of a storyboard sheet ([width] × [height] each, or the sheet split evenly). */
private data class TileCrop(val col: Int, val row: Int, val cols: Int, val rows: Int, val width: Int, val height: Int) : BitmapTransformation() {
    override fun transform(pool: BitmapPool, toTransform: android.graphics.Bitmap, outWidth: Int, outHeight: Int): android.graphics.Bitmap {
        val w = (if (width > 0) width else toTransform.width / cols).coerceIn(1, toTransform.width)
        val h = (if (height > 0) height else toTransform.height / rows).coerceIn(1, toTransform.height)
        val x = (col * w).coerceIn(0, toTransform.width - w)
        val y = (row * h).coerceIn(0, toTransform.height - h)
        return android.graphics.Bitmap.createBitmap(toTransform, x, y, w, h)
    }

    override fun updateDiskCacheKey(messageDigest: java.security.MessageDigest) {
        messageDigest.update("lumentube.tile:$col:$row:$cols:$rows:$width:$height".toByteArray())
    }
}

private fun resizeModeOf(pictureFormat: Int) = when (pictureFormat) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/** Time line alone (seeking with the controls hidden): bar and time, nothing to focus. */
@Composable
private fun SeekLine(position: Long, duration: Long, buffered: Long, storyboard: MediaItemStoryboard?) {
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val loaded = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 28.dp)) {
            // The picture at the target, above its place on the bar.
            if (storyboard != null && duration > 0) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val x = (maxWidth * played - PREVIEW_WIDTH / 2).coerceIn(0.dp, maxWidth - PREVIEW_WIDTH)
                    StoryboardFrame(storyboard, position, Modifier.offset(x = x).width(PREVIEW_WIDTH).aspectRatio(16f / 9f).clip(ControlShape).background(Color.Black))
                }
                Spacer(Modifier.height(10.dp))
            }
            Text("${formatTime(position)} / ${formatTime(duration)}", fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.End))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Palette.Track)) {
                Box(Modifier.fillMaxWidth(loaded).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                Box(Modifier.fillMaxWidth(played).fillMaxHeight().background(Color.White))
            }
        }
    }
}

/**
 * YouTube cues rebuilt without their position (as SmartTube does): centered at the bottom. Auto
 * captions repeat the previous line in each cue ("rolling" captions); that part is cut, so each
 * line shows once.
 */
private class CenteredCues {
    private var previous: String? = null

    fun of(cues: List<com.google.android.exoplayer2.text.Cue>): List<com.google.android.exoplayer2.text.Cue> = cues.mapNotNull { cue ->
        val text = cue.text?.toString() ?: return@mapNotNull null
        val shown = when {
            // WebVTT rolling line: kept for the next cue, not shown twice.
            text.endsWith("\n") || text.endsWith(" ") -> {
                previous = text
                return@mapNotNull null
            }
            text.contains("\n") -> {
                val kept = previous?.takeIf { text.contains(it) }?.let { text.replace(it, "").replace("\n", "") } ?: text
                val lines = text.split("\n")
                previous = if (lines.size == 2) lines[1] else text
                kept
            }
            else -> {
                val kept = previous?.let { text.replace(it, "") } ?: text
                previous = kept
                kept
            }
        }
        if (shown.isBlank()) null else com.google.android.exoplayer2.text.Cue(shown)
    }
}
