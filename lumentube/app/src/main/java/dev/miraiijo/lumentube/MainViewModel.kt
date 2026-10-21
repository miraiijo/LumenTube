package dev.miraiijo.lumentube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.miraiijo.lumentube.data.Section
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.VideoRow
import dev.miraiijo.lumentube.data.YouTube
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A page's content: loading, rows, or an error message. */
data class Page(val rows: List<VideoRow> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** A channel or playlist opened from a card, shown over the section. */
data class Opened(val title: String, val page: Page)

data class UiState(
    val section: Section = Section.HOME,
    val pages: Map<Section, Page> = emptyMap(),
    val opened: Opened? = null,
    val signedIn: Boolean = false,
    val accountName: String? = null,
    val accountAvatar: String? = null,
    /** Sign-in in progress: the code to type on youtube.com/activate (null while it's requested). */
    val signInCode: String? = null,
    val signingIn: Boolean = false,
    val signInError: String? = null,
    /** Video being played (full screen). */
    val playing: Video? = null,
    /** A YouTube link opened from another app (video id), played directly. */
    val playingId: String? = null,
) {
    val page: Page get() = opened?.page ?: pages[section] ?: Page()
}

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val loads = mutableMapOf<Section, Job>()
    private var signIn: Disposable? = null
    /** Rows whose next page is being fetched (one request at a time per row). */
    private val loadingMore = mutableSetOf<String>()

    init {
        refreshAccount()
        show(Section.HOME)
    }

    fun show(section: Section) {
        _state.update { it.copy(section = section, opened = null) }
        val current = _state.value.pages[section]
        if (current == null || current.error != null) load(section)
    }

    fun reload() = load(_state.value.section)

    private fun load(section: Section) {
        loads[section]?.cancel()
        _state.update { it.copy(pages = it.pages + (section to Page(rows = it.pages[section]?.rows.orEmpty(), loading = true))) }
        loads[section] = viewModelScope.launch {
            val page = runCatching { YouTube.rows(section) }.fold(
                onSuccess = { rows -> Page(rows, loading = false, error = if (rows.isEmpty()) "Rien à afficher ici pour l'instant." else null) },
                onFailure = { e -> Page(loading = false, error = e.userMessage()) },
            )
            _state.update { it.copy(pages = it.pages + (section to page)) }
        }
    }

    /** End of a row reached: its next page. */
    fun loadMore(row: VideoRow) {
        if (!row.canLoadMore || !loadingMore.add(row.key)) return
        viewModelScope.launch {
            val next = runCatching { YouTube.more(row) }.getOrNull()
            loadingMore.remove(row.key)
            if (next == null) return@launch
            _state.update { s ->
                fun Page.swap() = copy(rows = rows.map { if (it.key == row.key) next else it })
                val opened = s.opened?.let { o -> o.copy(page = o.page.swap()) }
                s.copy(opened = opened, pages = s.pages.mapValues { (_, page) -> page.swap() })
            }
        }
    }

    /** OK on a card: play a video, open a channel / playlist. */
    fun select(video: Video) {
        if (video.isVideo && video.videoId != null) {
            _state.update { it.copy(playing = video, playingId = null) }
            return
        }
        _state.update { it.copy(opened = Opened(video.title, Page())) }
        viewModelScope.launch {
            val page = runCatching { YouTube.open(video) }.fold(
                onSuccess = { Page(it, loading = false, error = if (it.isEmpty()) "Aucune vidéo." else null) },
                onFailure = { Page(loading = false, error = it.userMessage()) },
            )
            _state.update { s -> if (s.opened == null) s else s.copy(opened = Opened(video.title, page)) }
        }
    }

    fun closeOpened() = _state.update { it.copy(opened = null) }

    fun playLink(videoId: String) = _state.update { it.copy(playing = null, playingId = videoId) }

    fun stopPlaying() = _state.update { it.copy(playing = null, playingId = null) }

    // ---- Account ------------------------------------------------------------------------------

    fun startSignIn() {
        signIn?.dispose()
        _state.update { it.copy(signingIn = true, signInCode = null, signInError = null) }
        signIn = YouTube.signIn(
            onCode = { code -> _state.update { it.copy(signInCode = code) } },
            onError = { message -> _state.update { it.copy(signInError = message) } },
            onDone = {
                _state.update { it.copy(signingIn = false, signInCode = null, pages = emptyMap()) }
                refreshAccount()
                show(_state.value.section)
            },
        )
    }

    fun cancelSignIn() {
        signIn?.dispose()
        _state.update { it.copy(signingIn = false, signInCode = null, signInError = null) }
    }

    fun signOut() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            YouTube.signOut()
            _state.update { it.copy(pages = emptyMap(), section = Section.HOME) }
            refreshAccount()
            launch(kotlinx.coroutines.Dispatchers.Main) { show(Section.HOME) }
        }
    }

    private fun refreshAccount() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val signed = YouTube.signedIn
            _state.update { it.copy(signedIn = signed, accountName = if (signed) YouTube.accountName else null, accountAvatar = if (signed) YouTube.accountAvatar else null) }
        }
    }

    override fun onCleared() {
        signIn?.dispose()
    }

    private fun Throwable.userMessage(): String = when (this) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "Pas de connexion à Internet."
        is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> "YouTube ne répond pas. Réessaie."
        else -> "Impossible de charger cette page (${this::class.java.simpleName})."
    }
}
