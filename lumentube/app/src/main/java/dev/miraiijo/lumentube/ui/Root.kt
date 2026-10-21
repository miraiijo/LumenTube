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
    BackHandler(enabled = state.playingId == null && state.playing == null && (state.opened != null || !inMenu || state.section != dev.miraiijo.lumentube.data.Section.HOME)) {
        when {
            state.opened != null -> {
                vm.closeOpened()
                pageFocusTick++
            }
            !inMenu -> rail.tryRequestFocus()
            else -> vm.show(dev.miraiijo.lumentube.data.Section.HOME)
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
            Crossfade(targetState = state.section to state.opened?.title, animationSpec = tween(220), label = "pages") { _ ->
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
        SideRail(
            state = state,
            modifier = Modifier.focusRequester(rail),
            onFocusChange = { inMenu = it },
            onSection = { section ->
                vm.show(section)
                pageFocusTick++
            },
            onAccount = { if (state.signedIn) vm.signOut() else vm.startSignIn() },
        )
        if (state.signingIn) SignInDialog(code = state.signInCode, error = state.signInError, onRetry = vm::startSignIn, onDismiss = vm::cancelSignIn)
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
                onExit = {
                    vm.stopPlaying()
                    returnFocusTick++
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

/** Where a video resumes: YouTube's saved position, else its watched share (not when finished). */
private fun resumeSeconds(video: dev.miraiijo.lumentube.data.Video): Int {
    val saved = video.raw.startTimeSeconds
    if (saved > 0) return saved
    val percent = video.watchedPercent
    return if (percent in 3..94 && video.durationMs > 0) (video.durationMs / 1000 * percent / 100).toInt() else 0
}
