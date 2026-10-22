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
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions

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
    /** YouTube's suggestions for what's being typed (shown instead of the filters). */
    suggestions: List<String> = emptyList(),
    onTyping: (String) -> Unit = {},
    /** Filters: SearchOptions flags. */
    options: Int = 0,
    onOptions: (Int) -> Unit = {},
    onLongPress: (Video) -> Unit = {},
) {
    val bar = remember { FocusRequester() }
    /** The chips under the bar (suggestions or filters); the first one is where Down from the bar goes. */
    val chips = remember { List(MAX_CHIPS) { FocusRequester() } }
    val belowBar = chips[0]
    // Incremented on each search: the results take the focus when they arrive.
    var resultsTick by remember { mutableIntStateOf(0) }
    /** False after a filter: the focus stays on the filter (to try the next choice), not on the results. */
    var followResults by remember { mutableStateOf(true) }
    /** A filter changed: its chip takes the focus back (its label changed under it). */
    var refocusChip by remember { mutableIntStateOf(-1) }
    var refocusTick by remember { mutableIntStateOf(0) }
    var chipsHaveFocus by remember { mutableStateOf(false) }
    fun search(text: String) {
        if (text.isBlank()) return
        followResults = true
        resultsTick++
        onSearch(text)
    }
    LaunchedEffect(refocusTick) {
        if (refocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        chips.getOrNull(refocusChip)?.tryRequestFocus()
    }
    LaunchedEffect(focusTick) {
        if (focusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        bar.tryRequestFocus()
    }
    Column(Modifier.fillMaxSize()) {
        SearchBar(query, bar, belowBar, ::search, onTyping, Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 28.dp), chipFocused = { chipsHaveFocus })
        // One line under the bar: the suggestions while typing, else the filters.
        Row(
            Modifier.fillMaxWidth().onFocusChanged { chipsHaveFocus = it.hasFocus }.horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Up from this line: back to the bar (not the side menu).
            fun chip(index: Int) = (chips.getOrNull(index)?.let { Modifier.focusRequester(it) } ?: Modifier).focusProperties { up = bar }
            if (suggestions.isNotEmpty()) {
                suggestions.forEachIndexed { index, suggestion ->
                    LumenButton(suggestion, modifier = chip(index), icon = LumenIcon.SEARCH) {
                        // The suggestions line is about to give way to the filters: the focus moves to
                        // the bar first (it fell into the side menu), then to the results when they come.
                        bar.tryRequestFocus()
                        search(suggestion)
                    }
                }
            } else {
                SEARCH_FILTERS.forEachIndexed { index, filter ->
                    val current = filter.choices.indexOfFirst { (flag, _) -> flag != 0 && options and flag != 0 }.coerceAtLeast(0)
                    val (_, name) = filter.choices[current]
                    LumenButton(
                        if (current == 0) filter.label else "${filter.label} : $name",
                        modifier = chip(index),
                        containerColor = if (current == 0) Palette.ControlIdle else Palette.Selected,
                    ) {
                        // OK = the next choice of this filter (then back to "all").
                        val next = filter.choices[(current + 1) % filter.choices.size].first
                        followResults = false
                        refocusChip = index
                        refocusTick++
                        onOptions(options and filter.mask.inv() or next)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BrowsePage(
                page = if (query.isBlank() && page.rows.isEmpty() && !page.loading) page.copy(error = "Cherche une vidéo, une chaîne ou une playlist.") else page,
                title = query.takeIf { it.isNotBlank() }?.let { "Résultats pour « $it »" },
                focusTick = if (followResults) resultsTick else 0,
                onSelect = onSelect,
                onLoadMore = onLoadMore,
                onRetry = { search(query) },
                needsAccount = false,
                onSignIn = {},
                showRetry = query.isNotBlank(),
                onLongPress = onLongPress,
                top = 4.dp,
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    requester: FocusRequester,
    below: FocusRequester,
    onSearch: (String) -> Unit,
    onTyping: (String) -> Unit,
    modifier: Modifier,
    /** A chip under the bar has the focus (then a cancelled typing leaves it there). */
    chipFocused: () -> Boolean = { false },
) {
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

    /** [run]: search (keyboard's search key). [lost]: the field lost the focus (Down to a chip, keyboard closed). */
    fun finish(run: Boolean, lost: Boolean = false) {
        // First: the field losing the focus below must not call finish again.
        hadFocus = false
        keyboard?.hide()
        // The focus goes back to the pill before the field it's in goes away (it fell into the
        // side menu, which opened and closed); the results take it when they arrive. Not when the
        // field lost it: the remote moved it on purpose (a suggestion), it stays there.
        if (!lost) requester.tryRequestFocus()
        editing = false
        // A search moves the focus to its results; a cancel goes back to the pill unless a chip has it.
        if (run) onSearch(text) else if (lost) refocus++
    }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            // The pill only opens the keyboard: moving across it with the remote never pops it up.
            onClick = { editing = true },
            // Down: the chips right under the bar (the 2D search sometimes picked the side menu).
            modifier = Modifier.weight(1f).height(48.dp).focusRequester(requester).focusProperties { down = below },
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
                        onValueChange = {
                            text = it
                            onTyping(it)
                        },
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
                                if (it.isFocused) hadFocus = true else if (hadFocus) finish(run = false, lost = true)
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
            LumenButton("Dicter", modifier = Modifier.focusProperties { down = below }, icon = LumenIcon.MIC) {
                runCatching { dictation.launch(voiceIntent) }
            }
        }
    }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        withFrameNanos { }
        if (!chipFocused()) requester.tryRequestFocus()
    }
}

/** A search filter: OK on its pill goes through [choices] (SearchOptions flag to name, 0 = all). */
private class SearchFilter(val label: String, val choices: List<Pair<Int, String>>) {
    val mask = choices.fold(0) { acc, (flag, _) -> acc or flag }
}

private val SEARCH_FILTERS = listOf(
    SearchFilter(
        "Date",
        listOf(
            0 to "Toutes",
            SearchOptions.UPLOAD_DATE_LAST_HOUR to "Dernière heure",
            SearchOptions.UPLOAD_DATE_TODAY to "Aujourd'hui",
            SearchOptions.UPLOAD_DATE_THIS_WEEK to "Cette semaine",
            SearchOptions.UPLOAD_DATE_THIS_MONTH to "Ce mois-ci",
            SearchOptions.UPLOAD_DATE_THIS_YEAR to "Cette année",
        ),
    ),
    SearchFilter(
        "Durée",
        listOf(
            0 to "Toutes",
            SearchOptions.DURATION_UNDER_4 to "Moins de 4 min",
            SearchOptions.DURATION_BETWEEN_4_20 to "4 à 20 min",
            SearchOptions.DURATION_OVER_20 to "Plus de 20 min",
        ),
    ),
    SearchFilter(
        "Type",
        listOf(
            0 to "Tout",
            SearchOptions.TYPE_VIDEO to "Vidéos",
            SearchOptions.TYPE_CHANNEL to "Chaînes",
            SearchOptions.TYPE_PLAYLIST to "Playlists",
            SearchOptions.FEATURE_LIVE to "En direct",
        ),
    ),
    SearchFilter(
        "Tri",
        listOf(
            0 to "Pertinence",
            SearchOptions.SORT_BY_UPLOAD_DATE to "Date",
            SearchOptions.SORT_BY_VIEW_COUNT to "Vues",
            SearchOptions.SORT_BY_RATING to "Note",
        ),
    ),
)

/** Suggestions shown at most (YouTube's, see YouTube.suggestions) and filters: chips with a requester. */
private const val MAX_CHIPS = 8
