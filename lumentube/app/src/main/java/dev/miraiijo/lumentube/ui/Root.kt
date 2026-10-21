@file:OptIn(ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import dev.miraiijo.lumentube.MainViewModel

/**
 * Lumen's side-menu layout: the menu on the left (account, sections, Compte at the bottom), the
 * section's rows on the right with the focused video's sheet above them. A video plays full screen.
 */
@Composable
fun Root(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val content = remember { FocusRequester() }
    val rail = remember { FocusRequester() }
    // Incremented when a page should take the focus (section opened from the menu, channel opened).
    // Starts at 1: at launch, Accueil takes the focus as soon as its rows arrive.
    var pageFocusTick by remember { mutableIntStateOf(1) }
    // Incremented when the player closes: the focus goes back to the card that was played.
    var returnFocusTick by remember { mutableIntStateOf(0) }

    // Back: channel/playlist → its section; page → menu; menu → Accueil (then the system closes).
    var inMenu by remember { androidx.compose.runtime.mutableStateOf(false) }
    val fullScreenPlayer = (state.playingId != null || state.playing != null) && !state.minimized
    // …then, with a video in the small window, the last Back closes it (the next one leaves the app).
    BackHandler(enabled = !fullScreenPlayer && (state.opened != null || !inMenu || state.section != dev.miraiijo.lumentube.data.Section.HOME || state.minimized)) {
        when {
            state.opened != null -> {
                vm.closeOpened()
                pageFocusTick++
            }
            !inMenu -> rail.tryRequestFocus()
            state.section != dev.miraiijo.lumentube.data.Section.HOME -> vm.show(dev.miraiijo.lumentube.data.Section.HOME)
            else -> vm.stopPlaying()
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = RAIL_WIDTH)
                .focusRequester(content)
                // Right from the menu: back to the card you left.
                .focusRestorer(),
        ) {
            Crossfade(targetState = state.section to state.opened?.title, animationSpec = tween(220), label = "pages") { (section, opened) ->
                if (section == dev.miraiijo.lumentube.data.Section.SEARCH && opened == null) {
                    SearchPage(
                        query = state.query,
                        page = state.page,
                        focusTick = pageFocusTick,
                        onSearch = vm::search,
                        onSelect = vm::select,
                        onLoadMore = vm::loadMore,
                    )
                } else {
                    BrowsePage(
                        page = state.page,
                        title = state.opened?.title,
                        focusTick = pageFocusTick,
                        onSelect = vm::select,
                        onLoadMore = vm::loadMore,
                        onRetry = vm::reload,
                        needsAccount = state.section.account && !state.signedIn && state.opened == null,
                        onSignIn = vm::startSignIn,
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
            onAccount = vm::openSettings,
            onRestorePlayer = vm::restore,
        )
        if (state.signingIn) {
            SignInDialog(code = state.signInCode, error = state.signInError, onRetry = vm::startSignIn, onDismiss = vm::cancelSignIn)
        } else if (state.choosingAccount || state.settingsOpen) {
            AccountDialog(
                launch = state.choosingAccount,
                accounts = state.accounts,
                signedIn = state.signedIn,
                settings = state.settings,
                onChoose = vm::chooseAccount,
                onAdd = vm::startSignIn,
                onSignOut = vm::signOut,
                onSettings = vm::updateSettings,
                onDismiss = { if (state.choosingAccount) vm.skipAccountChoice() else vm.closeSettings() },
            )
        }
        // The video plays over the pages (they stay composed: Back returns to the same card).
        val playing = state.playing
        val playingId = state.playingId ?: playing?.videoId
        if (playingId != null) {
            PlayerScreen(
                videoId = playingId,
                title = playing?.title,
                channel = playing?.channel,
                thumbnail = playing?.thumbnail,
                startSeconds = playing?.let { resumeSeconds(it) } ?: 0,
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
                modifier = if (state.minimized) {
                    Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 32.dp).size(MINI_WIDTH, MINI_WIDTH * 9 / 16)
                } else {
                    Modifier.fillMaxSize()
                },
            )
        }
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
