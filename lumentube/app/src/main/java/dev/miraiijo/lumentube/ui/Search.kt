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
        SearchBar(query, bar, ::search, onTyping, Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 28.dp))
        // One line under the bar: the suggestions while typing, else the filters.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (suggestions.isNotEmpty()) {
                suggestions.forEach { suggestion -> LumenButton(suggestion, icon = LumenIcon.SEARCH) { search(suggestion) } }
            } else {
                SEARCH_FILTERS.forEach { filter ->
                    val current = filter.choices.indexOfFirst { (flag, _) -> flag != 0 && options and flag != 0 }.coerceAtLeast(0)
                    val (_, name) = filter.choices[current]
                    LumenButton(
                        if (current == 0) filter.label else "${filter.label} : $name",
                        containerColor = if (current == 0) Palette.ControlIdle else Palette.Selected,
                    ) {
                        // OK = the next choice of this filter (then back to "all").
                        val next = filter.choices[(current + 1) % filter.choices.size].first
                        onOptions(options and filter.mask.inv() or next)
                    }
                }
            }
        }
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
                onLongPress = onLongPress,
                top = 4.dp,
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, requester: FocusRequester, onSearch: (String) -> Unit, onTyping: (String) -> Unit, modifier: Modifier) {
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
