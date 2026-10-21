@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.miraiijo.lumentube.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.miraiijo.lumentube.Page
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.VideoRow
import java.util.Locale

/**
 * Rechercher: a search bar (OK opens the TV keyboard, its Search key runs the search) and the
 * microphone when the box has voice recognition, then the results as rows, like any page.
 */
@Composable
fun SearchPage(
    query: String,
    page: Page,
    focusTick: Int,
    onSearch: (String) -> Unit,
    onSelect: (Video) -> Unit,
    onLoadMore: (VideoRow) -> Unit,
) {
    val bar = remember { FocusRequester() }
    // Incremented on each search: the results take the focus when they arrive.
    var resultsTick by remember { mutableIntStateOf(0) }
    fun search(text: String) {
        if (text.isBlank()) return
        resultsTick++
        onSearch(text)
    }
    LaunchedEffect(focusTick) {
        if (focusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        bar.tryRequestFocus()
    }
    Column(Modifier.fillMaxSize()) {
        SearchBar(query, bar, ::search, Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 28.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BrowsePage(
                page = if (query.isBlank() && page.rows.isEmpty() && !page.loading) page.copy(error = "Cherche une vidéo, une chaîne ou une playlist.") else page,
                title = query.takeIf { it.isNotBlank() }?.let { "Résultats pour « $it »" },
                focusTick = resultsTick,
                onSelect = onSelect,
                onLoadMore = onLoadMore,
                onRetry = { search(query) },
                needsAccount = false,
                onSignIn = {},
                showRetry = query.isNotBlank(),
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, requester: FocusRequester, onSearch: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember(query) { mutableStateOf(query) }
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    /** Incremented when typing is cancelled: the focus goes back to the pill. */
    var refocus by remember { mutableIntStateOf(0) }
    val field = remember { FocusRequester() }

    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Que cherches-tu ?")
    }
    val canDictate = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            text = it
            onSearch(it)
        }
    }

    fun finish(run: Boolean) {
        editing = false
        hadFocus = false
        keyboard?.hide()
        // A search moves the focus to its results; a cancel goes back to the pill.
        if (run) onSearch(text) else refocus++
    }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            // The pill only opens the keyboard: moving across it with the remote never pops it up.
            onClick = { editing = true },
            modifier = Modifier.weight(1f).height(48.dp).focusRequester(requester),
            shape = ClickableSurfaceDefaults.shape(shape = ControlShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Palette.ControlIdle,
                contentColor = Palette.TextPrimary,
                focusedContainerColor = Palette.Selected,
                focusedContentColor = Palette.TextPrimary,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White), shape = ControlShape),
            ),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LumenIcon.SEARCH, size = 18.dp)
                Spacer(Modifier.width(12.dp))
                if (editing) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Palette.TextPrimary, fontSize = 18.sp, fontFamily = InterFamily),
                        cursorBrush = SolidColor(Palette.Highlight),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { finish(run = true) }, onDone = { finish(run = true) }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(field)
                            .onFocusChanged {
                                // Keyboard closed with Back: back to the pill, nothing searched.
                                if (it.isFocused) hadFocus = true else if (hadFocus) finish(run = false)
                            },
                    )
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        field.tryRequestFocus()
                        keyboard?.show()
                    }
                } else {
                    Text(
                        text.ifBlank { "Rechercher sur YouTube" },
                        fontSize = 18.sp,
                        color = if (text.isBlank()) Palette.TextSecondary else Palette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (canDictate) {
            LumenButton("Dicter", icon = LumenIcon.MIC) {
                runCatching { dictation.launch(voiceIntent) }
            }
        }
    }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        withFrameNanos { }
        requester.tryRequestFocus()
    }
}
