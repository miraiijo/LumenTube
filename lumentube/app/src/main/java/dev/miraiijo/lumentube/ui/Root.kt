@file:OptIn(ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.miraiijo.lumentube.MainViewModel

/**
 * Lumen's side-menu layout: the menu on the left (account, sections, Compte at the bottom), the
 * section's rows on the right with the focused video's sheet above them. A video plays full screen.
 */
@Composable
fun Root(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    // The page's rows, rebuilt only when what they show changes (state.page builds a new one each
    // time it's read): a notice, the player or a dialog doesn't recompose every card.
    val page = remember(state.section, state.pages, state.opened, state.search, state.resume, state.deArrow) { state.page }
    val content = remember { FocusRequester() }
    val rail = remember { FocusRequester() }
    // Incremented when a page should take the focus (section opened from the menu, channel opened).
    // Starts at 1: at launch, Accueil takes the focus as soon as its rows arrive.
    var pageFocusTick by remember { mutableIntStateOf(1) }
    // Incremented when the player closes: the focus goes back to the card that was played.
    var returnFocusTick by remember { mutableIntStateOf(0) }

    // A page about to be replaced (channel / playlist opened, or closed): its focused card goes
    // away while the next one loads. This invisible holder keeps the focus meanwhile (otherwise it
    // fell into the side menu, which opened and closed); it's only focusable while holding.
    val holder = remember { FocusRequester() }
    var holding by remember { androidx.compose.runtime.mutableStateOf(false) }
    fun holdFocus() {
        holding = true
        if (!holder.tryRequestFocus()) holding = false
    }
    val select: (dev.miraiijo.lumentube.data.Video) -> Unit = remember(vm) {
        { video ->
            if (!video.isVideo || video.videoId == null) holdFocus()
            vm.select(video)
        }
    }

    // Back: channel/playlist → its section; page → menu; menu → Accueil (then the system closes).
    var inMenu by remember { androidx.compose.runtime.mutableStateOf(false) }
    val fullScreenPlayer = (state.playingId != null || state.playing != null) && !state.minimized
    // …then, with a video in the small window, the last Back closes it (the next one leaves the app).
    BackHandler(enabled = !fullScreenPlayer && (state.opened != null || !inMenu || state.section != dev.miraiijo.lumentube.data.Section.HOME || state.minimized)) {
        when {
            state.opened != null -> {
                holdFocus()
                vm.closeOpened()
                pageFocusTick++
            }
            !inMenu -> rail.tryRequestFocus()
            state.section != dev.miraiijo.lumentube.data.Section.HOME -> vm.show(dev.miraiijo.lumentube.data.Section.HOME)
            else -> vm.stopPlaying()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            // Shortcut: the remote's play / pause key brings the small window back to full screen.
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!state.minimized || native.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                    -> {
                        vm.restore()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = RAIL_WIDTH)
                .focusRequester(content)
                // Right from the menu: back to the card you left.
                .focusRestorer(),
        ) {
            Box(
                Modifier
                    .size(1.dp)
                    .focusProperties { canFocus = holding }
                    .focusRequester(holder)
                    .onFocusChanged { if (!it.isFocused && holding) holding = false }
                    .focusable(),
            )
            Crossfade(targetState = state.section to state.opened?.title, animationSpec = tween(220), label = "pages") { (section, opened) ->
                if (section == dev.miraiijo.lumentube.data.Section.SEARCH && opened == null) {
                    SearchPage(
                        query = state.query,
                        page = page,
                        focusTick = pageFocusTick,
                        onSearch = vm::search,
                        onSelect = select,
                        onLoadMore = vm::loadMore,
                        suggestions = state.suggestions,
                        onTyping = vm::suggest,
                        options = state.searchOptions,
                        onOptions = vm::setSearchOptions,
                        onLongPress = vm::openCardMenu,
                    )
                } else {
                    BrowsePage(
                        page = page,
                        title = state.opened?.title,
                        focusTick = pageFocusTick,
                        onSelect = select,
                        onLoadMore = vm::loadMore,
                        onRetry = vm::reload,
                        needsAccount = state.section.account && !state.signedIn && state.opened == null,
                        onSignIn = vm::startSignIn,
                        onLongPress = vm::openCardMenu,
                        channel = state.opened?.channel,
                        signedIn = state.signedIn,
                        onSubscribe = vm::toggleChannelSubscription,
                        onSort = vm::chooseChannelSort,
                    )
                }
            }
        }
        SideRail(
            state = state,
            modifier = Modifier.focusRequester(rail),
            onFocusChange = { inMenu = it },
            onSection = { section ->
                vm.show(section)
                pageFocusTick++
            },
            onAccount = vm::openAccounts,
            onSettings = vm::openSettings,
            onRestorePlayer = vm::restore,
        )
        if (state.signingIn) {
            SignInDialog(code = state.signInCode, error = state.signInError, onRetry = vm::startSignIn, onDismiss = vm::cancelSignIn)
        } else if (state.choosingAccount || state.accountsOpen) {
            ProfileDialog(
                accounts = state.accounts,
                onChoose = vm::chooseAccount,
                onAdd = vm::startSignIn,
                onDismiss = { if (state.choosingAccount) vm.skipAccountChoice() else vm.closeAccounts() },
            )
        } else if (state.settingsOpen) {
            SettingsDialog(
                settings = state.settings,
                accountName = state.accountName.takeIf { state.signedIn },
                onSettings = vm::updateSettings,
                onPair = vm::openPairing,
                onSignOut = vm::signOut,
                onDismiss = vm::closeSettings,
            )
        } else if (state.pairing != null) {
            PairingDialog(state.pairing!!, onRetry = vm::openPairing, onDismiss = vm::closePairing)
        } else if (state.cardMenu != null) {
            val video = state.cardMenu!!
            CardMenuDialog(
                video = video,
                signedIn = state.signedIn,
                canGiveFeedback = remember(video.key) { dev.miraiijo.lumentube.data.YouTube.hasFeedback(video) },
                onPlay = {
                    vm.closeCardMenu()
                    vm.select(video)
                },
                onWatchLater = { vm.cardWatchLater(video) },
                onChannel = {
                    holdFocus()
                    vm.cardOpenChannel(video)
                    pageFocusTick++
                },
                onNotInterested = { channel -> vm.cardNotInterested(video, channel) },
                onDismiss = vm::closeCardMenu,
            )
        }
        // The video plays over the pages (they stay composed: Back returns to the same card).
        val playing = state.playing
        val playingId = state.playingId ?: playing?.videoId
        if (playingId != null) {
            // One player screen per video: everything (PlayerView, subtitle output, listeners) starts
            // fresh for the next one ("À suivre" included).
            androidx.compose.runtime.key(playingId) { PlayerScreen(
                videoId = playingId,
                title = playing?.title,
                channel = playing?.channel,
                thumbnail = playing?.thumbnail,
                startSeconds = playing?.let { resumeSeconds(it) } ?: state.playingStart,
                item = playing?.raw,
                signedIn = state.signedIn,
                onNext = vm::playNext,
                onExit = {
                    vm.stopPlaying()
                    returnFocusTick++
                },
                minimized = state.minimized,
                onMinimize = {
                    vm.minimize()
                    returnFocusTick++
                },
                onRestore = vm::restore,
                onOpenChannel = { id, name ->
                    vm.openChannelFromPlayer(id, name)
                    returnFocusTick++
                },
                modifier = if (state.minimized) {
                    Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 32.dp).size(MINI_WIDTH, MINI_WIDTH * 9 / 16)
                } else {
                    Modifier.fillMaxSize()
                },
            ) }
        }
        Notice(state.notice, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
    }
    LaunchedEffect(returnFocusTick) {
        if (returnFocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        if (!content.tryRequestFocus()) rail.tryRequestFocus()
    }
}

/** Width of the in-app picture-in-picture window (16:9). */
private val MINI_WIDTH = 400.dp

/** Where a video resumes: YouTube's saved position, else its watched share (not when finished). */
private fun resumeSeconds(video: dev.miraiijo.lumentube.data.Video): Int {
    val saved = video.raw.startTimeSeconds
    if (saved > 0) return saved
    val percent = video.watchedPercent
    return if (percent in 3..94 && video.durationMs > 0) (video.durationMs / 1000 * percent / 100).toInt() else 0
}
