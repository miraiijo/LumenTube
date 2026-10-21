package dev.miraiijo.lumentube

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.miraiijo.lumentube.data.Account
import dev.miraiijo.lumentube.data.DeArrow
import dev.miraiijo.lumentube.data.Section
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.VideoRow
import dev.miraiijo.lumentube.data.YouTube
import dev.miraiijo.lumentube.player.PlayerPrefs
import kotlinx.coroutines.delay
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

/** The app-wide switches of the "Compte et réglages" dialog. */
data class Settings(val sponsorBlock: Boolean, val autoplay: Boolean, val stableVolume: Boolean, val deArrow: Boolean)

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
    /** The video plays in a small window in a corner while browsing (in-app picture-in-picture). */
    val minimized: Boolean = false,
    /** Accounts linked on this box. */
    val accounts: List<Account> = emptyList(),
    /** "Qui regarde ?" at launch (several accounts), before Accueil loads. */
    val choosingAccount: Boolean = false,
    /** "Compte et réglages" dialog open. */
    val settingsOpen: Boolean = false,
    val settings: Settings = Settings(sponsorBlock = true, autoplay = true, stableVolume = true, deArrow = false),
    /** "Reprendre" row at the top of Accueil (null: none, or not signed in). */
    val resume: VideoRow? = null,
    val query: String = "",
    /** Search results (null before the first search). */
    val search: Page? = null,
    /** DeArrow titles / thumbnails by video id (only filled when DeArrow is on). */
    val deArrow: Map<String, DeArrow> = emptyMap(),
) {
    val page: Page
        get() = when {
            opened != null -> opened.page
            section == Section.SEARCH -> search ?: Page(loading = false)
            section == Section.HOME -> (pages[section] ?: Page()).let { home ->
                if (resume == null) home else home.copy(rows = listOf(resume) + home.rows)
            }
            else -> pages[section] ?: Page()
        }.withDeArrow(deArrow)

    /** Cards with DeArrow's title / thumbnail when there's one. */
    private fun Page.withDeArrow(found: Map<String, DeArrow>): Page {
        if (found.isEmpty()) return this
        return copy(
            rows = rows.map { row ->
                row.copy(
                    items = row.items.map { video ->
                        val d = video.videoId?.let(found::get) ?: return@map video
                        video.copy(title = d.title ?: video.title, customThumbnail = d.thumbnail)
                    },
                )
            },
        )
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PlayerPrefs(application)
    private val _state = MutableStateFlow(UiState(settings = prefs.settings()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val loads = mutableMapOf<Section, Job>()
    private var signIn: Disposable? = null
    /** Rows whose next page is being fetched (one request at a time per row). */
    private val loadingMore = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            val accounts = YouTube.accounts()
            // Several accounts: "Qui regarde ?" first, Accueil loads for the one picked.
            if (accounts.size >= 2) {
                _state.update { it.copy(accounts = accounts, choosingAccount = true) }
            } else {
                _state.update { it.copy(accounts = accounts) }
                refreshAccount()
                show(Section.HOME)
            }
        }
    }

    fun show(section: Section) {
        _state.update { it.copy(section = section, opened = null) }
        if (section == Section.SEARCH) return
        val current = _state.value.pages[section]
        if (current == null || current.error != null) load(section)
    }

    fun reload() = load(_state.value.section)

    private fun load(section: Section) {
        loads[section]?.cancel()
        _state.update { it.copy(pages = it.pages + (section to Page(rows = it.pages[section]?.rows.orEmpty(), loading = true))) }
        if (section == Section.HOME) loadResume()
        loads[section] = viewModelScope.launch {
            fun set(page: Page) = _state.update { it.copy(pages = it.pages + (section to page)) }
            var rows = emptyList<VideoRow>()
            val failure = runCatching {
                // Each part of the page is shown as soon as it arrives (still loading after it).
                YouTube.rows(section).collect {
                    rows = it
                    if (it.isNotEmpty()) set(Page(it, loading = true))
                    fetchDeArrow(it)
                }
            }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            set(
                when {
                    rows.isNotEmpty() -> Page(rows, loading = false)
                    failure != null -> Page(loading = false, error = failure.userMessage())
                    else -> Page(loading = false, error = "Rien à afficher ici pour l'instant.")
                },
            )
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
                s.copy(opened = opened, pages = s.pages.mapValues { (_, page) -> page.swap() }, search = s.search?.swap())
            }
            fetchDeArrow(listOf(next))
        }
    }

    /** "Reprendre" (history), next to Accueil's own loading. */
    private fun loadResume() {
        viewModelScope.launch {
            val row = runCatching { YouTube.resumeRow() }.getOrNull()
            _state.update { it.copy(resume = row) }
            row?.let { fetchDeArrow(listOf(it)) }
        }
    }

    // ---- Search -------------------------------------------------------------------------------

    private var searchJob: Job? = null

    fun search(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        searchJob?.cancel()
        _state.update { it.copy(query = text, search = Page(loading = true)) }
        searchJob = viewModelScope.launch {
            val page = runCatching { YouTube.search(text) }.fold(
                onSuccess = { Page(it, loading = false, error = if (it.isEmpty()) "Aucun résultat pour « $text »." else null) },
                onFailure = { Page(loading = false, error = it.userMessage()) },
            )
            _state.update { it.copy(search = page) }
            fetchDeArrow(page.rows)
        }
    }

    // ---- DeArrow ------------------------------------------------------------------------------

    /** Community titles / thumbnails for the videos of these rows not looked up yet (when DeArrow is on). */
    private fun fetchDeArrow(rows: List<VideoRow>) {
        if (!_state.value.settings.deArrow) return
        val known = _state.value.deArrow
        val ids = rows.flatMap { row -> row.items.mapNotNull { it.videoId } }.distinct().filter { it !in known }.take(DEARROW_BATCH)
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val found = YouTube.deArrow(ids)
            if (found.isNotEmpty()) _state.update { it.copy(deArrow = it.deArrow + found) }
        }
    }

    /** OK on a card: play a video, open a channel / playlist. */
    fun select(video: Video) {
        if (video.isVideo && video.videoId != null) {
            // The video already in the small window: back to full screen, where it was.
            val current = _state.value.let { it.playing?.videoId ?: it.playingId }
            if (_state.value.minimized && current == video.videoId) {
                restore()
                return
            }
            _state.update { it.copy(playing = video, playingId = null, minimized = false) }
            return
        }
        _state.update { it.copy(opened = Opened(video.title, Page())) }
        viewModelScope.launch {
            val page = runCatching { YouTube.open(video) }.fold(
                onSuccess = { Page(it, loading = false, error = if (it.isEmpty()) "Aucune vidéo." else null) },
                onFailure = { Page(loading = false, error = it.userMessage()) },
            )
            _state.update { s -> if (s.opened == null) s else s.copy(opened = Opened(video.title, page)) }
            fetchDeArrow(page.rows)
        }
    }

    /** "À suivre" from the player: the next video replaces the current one. */
    fun playNext(video: Video) = _state.update { it.copy(playing = video, playingId = null) }

    fun closeOpened() = _state.update { it.copy(opened = null) }

    fun playLink(videoId: String) = _state.update { it.copy(playing = null, playingId = videoId, minimized = false) }

    /** Small window in a corner; browsing goes on. */
    fun minimize() = _state.update { it.copy(minimized = true) }

    /** Back to full screen. */
    fun restore() = _state.update { it.copy(minimized = false) }

    fun stopPlaying() {
        _state.update { it.copy(playing = null, playingId = null, minimized = false) }
        // "Reprendre" follows what was just watched (YouTube needs a moment to record the position).
        if (_state.value.signedIn) {
            viewModelScope.launch {
                delay(RESUME_REFRESH_DELAY_MS)
                loadResume()
            }
        }
    }

    // ---- Settings -----------------------------------------------------------------------------

    fun openSettings() = _state.update { it.copy(settingsOpen = true) }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }

    fun updateSettings(settings: Settings) {
        prefs.sponsorBlock = settings.sponsorBlock
        prefs.autoplay = settings.autoplay
        prefs.stableVolume = settings.stableVolume
        prefs.deArrow = settings.deArrow
        val deArrowTurnedOn = settings.deArrow && !_state.value.settings.deArrow
        _state.update { it.copy(settings = settings, deArrow = if (settings.deArrow) it.deArrow else emptyMap()) }
        if (deArrowTurnedOn) {
            val s = _state.value
            fetchDeArrow(listOfNotNull(s.resume) + s.pages.values.flatMap { it.rows } + s.search?.rows.orEmpty())
        }
    }
    // ---- Account ------------------------------------------------------------------------------

    fun startSignIn() {
        signIn?.dispose()
        _state.update { it.copy(signingIn = true, signInCode = null, signInError = null) }
        signIn = YouTube.signIn(
            onCode = { code -> _state.update { it.copy(signInCode = code) } },
            onError = { message -> _state.update { it.copy(signInError = message) } },
            onDone = {
                // A new account is the selected one: everything reloads for it.
                _state.update { it.copy(signingIn = false, signInCode = null, choosingAccount = false, settingsOpen = false, pages = emptyMap(), resume = null) }
                refreshAccount()
                show(Section.HOME)
            },
        )
    }

    /** Account picked at launch ("Qui regarde ?") or in the settings: Accueil reloads for it. */
    fun chooseAccount(account: Account) {
        viewModelScope.launch {
            if (!account.selected) YouTube.selectAccount(account)
            loads.values.forEach { it.cancel() }
            _state.update { it.copy(choosingAccount = false, settingsOpen = false, pages = emptyMap(), resume = null, search = null, section = Section.HOME, opened = null) }
            refreshAccount()
            show(Section.HOME)
        }
    }

    /** "Qui regarde ?" dismissed: the account already selected stays. */
    fun skipAccountChoice() {
        _state.update { it.copy(choosingAccount = false) }
        refreshAccount()
        show(Section.HOME)
    }

    fun cancelSignIn() {
        signIn?.dispose()
        _state.update { it.copy(signingIn = false, signInCode = null, signInError = null) }
    }

    fun signOut() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            YouTube.signOut()
            _state.update { it.copy(pages = emptyMap(), resume = null, settingsOpen = false, section = Section.HOME) }
            refreshAccount()
            launch(kotlinx.coroutines.Dispatchers.Main) { show(Section.HOME) }
        }
    }

    private fun refreshAccount() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val signed = YouTube.signedIn
            val accounts = YouTube.accounts()
            _state.update {
                it.copy(
                    signedIn = signed,
                    accountName = if (signed) YouTube.accountName else null,
                    accountAvatar = if (signed) YouTube.accountAvatar else null,
                    accounts = accounts,
                )
            }
        }
    }

    private companion object {
        const val DEARROW_BATCH = 50
        const val RESUME_REFRESH_DELAY_MS = 1_500L
    }

    override fun onCleared() {
        signIn?.dispose()
    }

    // RxJava's blocking calls wrap checked exceptions (TimeoutException…) in a RuntimeException.
    private fun Throwable.userMessage(): String = when (val e = if (this.javaClass == RuntimeException::class.java) cause ?: this else this) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "Pas de connexion à Internet."
        is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> "YouTube ne répond pas. Réessaie."
        else -> "Impossible de charger cette page (${e::class.java.simpleName})."
    }
}
