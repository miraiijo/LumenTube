package dev.miraiijo.lumentube

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.miraiijo.lumentube.data.ACCOUNT_CHANGED_MESSAGE
import dev.miraiijo.lumentube.data.Account
import dev.miraiijo.lumentube.data.ActionResult
import dev.miraiijo.lumentube.data.DeArrow
import dev.miraiijo.lumentube.data.Section
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.VideoRow
import dev.miraiijo.lumentube.data.YouTube
import dev.miraiijo.lumentube.player.PlayerPrefs
import dev.miraiijo.lumentube.remote.RemoteControl
import dev.miraiijo.lumentube.ui.UI_SCALE_DEFAULT
import dev.miraiijo.lumentube.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A page's content: loading, rows, or an error message. */
@androidx.compose.runtime.Immutable
data class Page(val rows: List<VideoRow> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** A channel or playlist opened from a card, shown over the section. [channel]: a channel page's header. */
@androidx.compose.runtime.Immutable
data class Opened(val title: String, val page: Page, val channel: ChannelHeader? = null)

/**
 * A channel page's header: avatar, name, "S'abonner" ([subscribed] null: unknown or signed out) and
 * its orders ([sorts]: "Plus récentes", "Populaires"…; [sort] -1 = the channel's own page).
 */
@androidx.compose.runtime.Immutable
data class ChannelHeader(
    val id: String,
    val name: String,
    val avatar: String?,
    val subscribed: Boolean? = null,
    val sorts: List<String> = emptyList(),
    val sort: Int = -1,
)

/** The app-wide switches of the "Compte et réglages" dialog. */
data class Settings(
    val sponsorBlock: Boolean,
    val autoplay: Boolean,
    val stableVolume: Boolean,
    val deArrow: Boolean,
    val remote: Boolean = false,
    /** 0 = Auto, 1 = Jour, 2 = Nuit (ui.Appearance). */
    val appearance: Int = 0,
    /** "Taille de l'interface", in % (ui.UI_SCALE_MIN..UI_SCALE_MAX). */
    val uiScale: Int = UI_SCALE_DEFAULT,
    /** "Recevoir même LumenTube fermé": the phone link listens in the background (remote.CastService). */
    val backgroundCast: Boolean = false,
)

/**
 * Réglages › Mises à jour: the installed version, « Recevoir les versions bêta », and the version
 * found on the public channel ([found], null: up to date or not checked). [progress]: download 0..1.
 */
@androidx.compose.runtime.Immutable
data class UpdateUi(
    val installed: String = "",
    val wantsBeta: Boolean = false,
    val busy: Boolean = false,
    val progress: Float? = null,
    val found: Updater.UpdateInfo? = null,
    val message: String? = null,
)

/** "Lier un téléphone": the TV code to type in the phone's YouTube app (null while it's requested). */
data class Pairing(val code: String? = null, val error: String? = null)

data class UiState(
    val section: Section = Section.HOME,
    val pages: Map<Section, Page> = emptyMap(),
    val opened: Opened? = null,
    val signedIn: Boolean = false,
    /**
     * Opaque id of the account the pages show (null: signed out or not read yet): the actions made
     * from them (subscribe, À regarder plus tard, Pas intéressé) only go to it.
     */
    val accountId: String? = null,
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
    /** Where that link starts (its t parameter, seconds). */
    val playingStart: Int = 0,
    /** The video plays in a small window in a corner while browsing (in-app picture-in-picture). */
    val minimized: Boolean = false,
    /** Accounts linked on this box. */
    val accounts: List<Account> = emptyList(),
    /** "Qui regarde ?" at launch (several accounts), before Accueil loads. */
    val choosingAccount: Boolean = false,
    /** "Qui regarde ?" opened from the account at the top of the menu. */
    val accountsOpen: Boolean = false,
    /** Réglages dialog open. */
    val settingsOpen: Boolean = false,
    val settings: Settings = Settings(sponsorBlock = true, autoplay = true, stableVolume = true, deArrow = false),
    /** "Reprendre" row at the top of Accueil (null: none, or not signed in). */
    val resume: VideoRow? = null,
    val query: String = "",
    /** Search results (null before the first search). */
    val search: Page? = null,
    /** DeArrow titles / thumbnails by video id (only filled when DeArrow is on). */
    val deArrow: Map<String, DeArrow> = emptyMap(),
    /** Search filters (SearchOptions flags, 0 = none) and YouTube's suggestions for what's typed. */
    val searchOptions: Int = 0,
    val suggestions: List<String> = emptyList(),
    /** Long press on a card: its menu. */
    val cardMenu: Video? = null,
    /** Short message at the bottom of the screen ("Ajoutée à À regarder plus tard"…). */
    val notice: String? = null,
    /** "Lier un téléphone" dialog open. */
    val pairing: Pairing? = null,
    /** Réglages › Mises à jour. */
    val update: UpdateUi = UpdateUi(),
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

class MainViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val prefs = PlayerPrefs(application)
    private val _state = MutableStateFlow(UiState(settings = prefs.settings()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val loads = mutableMapOf<Section, Job>()
    /** Accueil's extra rows being loaded (declared before init, which may use it). */
    private var homeExtrasJob: Job? = null

    /** Lumen asked to skip "Qui regarde ?" (contract v2): the start doesn't show it. */
    private var skipPicker = false

    /** The video plays as another account than LumenTube's (a Lumen launch): undone when it stops. */
    private var playingAs = false
    private var signIn: Disposable? = null
    /** Rows whose next page is being fetched (one request at a time per row). */
    private val loadingMore = mutableSetOf<String>()
    /** Declared before init (which sets it): initializers run in order. */
    private var accountListener: (() -> Unit)? = null

    init {
        RemoteControl.uiAttached = true
        dev.miraiijo.lumentube.remote.CastService.sync(getApplication(), _state.value.settings)
        // Account switched from Lumen (or anywhere else): the pages reload for the new account.
        accountListener = YouTube.onAccountChanged {
            viewModelScope.launch {
                // By id, not by name (two accounts can share a name): the pages are another account's.
                val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.homeAccountId() }
                if (id != _state.value.accountId && !_state.value.choosingAccount) {
                    cancelLoads()
                    _state.update { it.copy(pages = emptyMap(), resume = null, search = null, opened = null, cardMenu = null) }
                    refreshAccount()
                    show(_state.value.section.takeIf { it != Section.SEARCH } ?: Section.HOME)
                }
            }
        }
        viewModelScope.launch { RemoteControl.events.collect(::onRemote) }
        // Back after the process was killed in the background: the section, the video and the
        // answer to "Qui regarde ?" come back (not a video played as another account for Lumen).
        val restored = saved.get<Boolean>(KEY_PICKED) != null
        val savedSection = saved.get<String>(KEY_SECTION)?.let { name -> Section.entries.firstOrNull { it.name == name } } ?: Section.HOME
        saved.get<String>(KEY_VIDEO)?.takeIf { VIDEO_ID.matches(it) }?.let { id ->
            _state.update { it.copy(playingId = id, playingStart = saved.get<Int>(KEY_POSITION) ?: 0) }
        }
        viewModelScope.launch {
            // A Lumen launch cut short (process killed while playing as another account): back home.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YouTube.restoreHome() } }
            val accounts = YouTube.accounts()
            // Several accounts: "Qui regarde ?" first, Accueil loads for the one picked (not when
            // Lumen launched a video and asked to skip it, nor when it was answered before the
            // process was killed).
            val ask = if (restored) saved.get<Boolean>(KEY_PICKED) == false else accounts.size >= 2
            if (ask && !skipPicker) {
                _state.update { it.copy(accounts = accounts, choosingAccount = true) }
            } else {
                _state.update { it.copy(accounts = accounts) }
                refreshAccount()
                show(if (restored) savedSection else Section.HOME)
            }
            // Kept from now on (written into the saved state at each change, cheap).
            state.collect { s ->
                saved[KEY_PICKED] = !s.choosingAccount
                saved[KEY_SECTION] = s.section.name
                val video = (s.playing?.videoId ?: s.playingId).takeIf { !playingAs }
                if (video != saved.get<String>(KEY_VIDEO)) {
                    saved[KEY_VIDEO] = video
                    saved[KEY_POSITION] = s.playing?.let { 0 } ?: s.playingStart
                }
            }
        }
        initUpdates()
    }

    /** Where the video is (the player tells it when it pauses or goes to the background). */
    fun notePosition(videoId: String, seconds: Int) {
        if (saved.get<String>(KEY_VIDEO) == videoId) saved[KEY_POSITION] = seconds.coerceAtLeast(0)
    }

    /** Every section load, Accueil's extra rows included. */
    private fun cancelLoads() {
        loads.values.forEach { it.cancel() }
        homeExtrasJob?.cancel()
        homeExtrasJob = null
    }

    fun show(section: Section) {
        _state.update { it.copy(section = section, opened = null) }
        if (section == Section.SEARCH) return
        val current = _state.value.pages[section]
        if (current == null || current.error != null) load(section)
    }

    fun reload() = load(_state.value.section)

    /** The playlist (or other card) opened last: "Réessayer" on its page opens it again. */
    private var openedFrom: Video? = null

    /** "Réessayer" on a page: the opened channel / playlist again, else the section. */
    fun retry() {
        val opened = _state.value.opened ?: return reload()
        val channel = opened.channel
        when {
            channel != null -> openChannel(channel.id, channel.name, channel.avatar)
            openedFrom != null -> select(openedFrom!!)
            else -> reload()
        }
    }

    private fun load(section: Section) {
        loads[section]?.cancel()
        if (section == Section.HOME) {
            homeExtrasJob?.cancel()
            homeExtrasJob = null
        }
        _state.update { it.copy(pages = it.pages + (section to Page(rows = it.pages[section]?.rows.orEmpty().filterNot { r -> r.key.startsWith(YouTube.HOME_EXTRA) }, loading = true))) }
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
            // Accueil without YouTube's own rows: the feeds' rows right away (they decide the error).
            if (section == Section.HOME && rows.isEmpty()) {
                loadHomeExtras(failure?.userMessage(), fromLoad = true)
                return@launch
            }
            set(
                when {
                    rows.isNotEmpty() -> Page(rows, loading = false)
                    failure != null -> Page(loading = false, error = failure.userMessage())
                    else -> Page(loading = false, error = "Rien à afficher ici pour l’instant.")
                },
            )
        }
    }

    /**
     * Accueil's end reached (or YouTube's home empty): a taste of each feed, under its rows, once
     * per load. [error]: shown when no row at all comes (YouTube's home failed too).
     */
    fun loadHomeExtras(error: String? = null, fromLoad: Boolean = false) {
        val s = _state.value
        if (homeExtrasJob != null) return
        // From the page: only Accueil's own (a page fading out doesn't ask).
        if (!fromLoad && (s.section != Section.HOME || s.opened != null)) return
        val home = s.pages[Section.HOME] ?: return
        if (home.rows.any { it.key.startsWith(YouTube.HOME_EXTRA) }) return
        homeExtrasJob = viewModelScope.launch {
            // From a failed load: none of the rows kept while it loaded (they'd hide its error).
            val base = if (fromLoad) emptyList() else home.rows.filterNot { it.key.startsWith(YouTube.HOME_EXTRA) }
            fun set(page: Page) = _state.update { it.copy(pages = it.pages + (Section.HOME to page)) }
            var extras = emptyList<VideoRow>()
            val failure = runCatching {
                YouTube.homeExtras().collect {
                    extras = it
                    if (it.isNotEmpty()) set(Page(base + it, loading = true))
                    fetchDeArrow(it)
                }
            }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            set(
                when {
                    base.isNotEmpty() || extras.isNotEmpty() -> Page(base + extras, loading = false)
                    else -> Page(loading = false, error = error ?: failure?.userMessage() ?: "Rien à afficher ici pour l’instant.")
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
        suggestJob?.cancel()
        val options = _state.value.searchOptions
        _state.update { it.copy(query = text, search = Page(loading = true), suggestions = emptyList()) }
        searchJob = viewModelScope.launch {
            val page = runCatching { YouTube.search(text, options) }.fold(
                onSuccess = { Page(it, loading = false, error = if (it.isEmpty()) "Aucun résultat pour « $text »." else null) },
                onFailure = { Page(loading = false, error = it.userMessage()) },
            )
            _state.update { it.copy(search = page) }
            fetchDeArrow(page.rows)
        }
    }

    private var suggestJob: Job? = null

    /** Typing in the search bar: YouTube's suggestions, once the typing pauses. */
    fun suggest(text: String) {
        suggestJob?.cancel()
        val typed = text.trim()
        if (typed.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DELAY_MS)
            val found = YouTube.suggestions(typed)
            _state.update { it.copy(suggestions = found) }
        }
    }

    /** A filter changed (date, duration, type, order): the current search runs again with it. */
    fun setSearchOptions(options: Int) {
        _state.update { it.copy(searchOptions = options) }
        _state.value.query.takeIf { it.isNotBlank() }?.let(::search)
    }

    // ---- DeArrow ------------------------------------------------------------------------------

    /** Community titles / thumbnails for the videos of these rows not looked up yet (when DeArrow is on). */
    /** Video ids already asked to DeArrow (answered or not): each one is asked once. */
    private val deArrowAsked = mutableSetOf<String>()

    private fun fetchDeArrow(rows: List<VideoRow>) {
        if (!_state.value.settings.deArrow) return
        val ids = rows.flatMap { row -> row.items.mapNotNull { it.videoId } }.distinct().filter { it !in deArrowAsked }.take(DEARROW_BATCH)
        if (ids.isEmpty()) return
        deArrowAsked += ids
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
        val channelId = video.raw.channelId
        if (video.isChannel && channelId != null) {
            openChannel(channelId, video.title, video.thumbnail)
            return
        }
        openedFrom = video
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

    // ---- Channel page ---------------------------------------------------------------------------

    private var channelJob: Job? = null
    /** The opened channel's rows: its own page, then one per order. */
    private var channelHome: List<VideoRow> = emptyList()
    private var channelSortRows: List<VideoRow> = emptyList()

    /** From the player: the video goes to the small window, the channel page opens behind it. */
    fun openChannelFromPlayer(channelId: String, name: String) {
        _state.update { it.copy(minimized = true) }
        openChannel(channelId, name, null)
    }

    /** A channel page (from a channel card, a card's menu or the player): header, then its rows. */
    fun openChannel(channelId: String, name: String, avatar: String?) {
        channelJob?.cancel()
        channelHome = emptyList()
        channelSortRows = emptyList()
        val header = ChannelHeader(channelId, name.ifBlank { "Chaîne" }, avatar)
        _state.update { it.copy(opened = Opened(header.name, Page(), header), cardMenu = null) }
        channelJob = viewModelScope.launch {
            launch {
                val subscribed = if (_state.value.signedIn) YouTube.isSubscribed(channelId) else null
                updateChannel(channelId) { it.copy(subscribed = subscribed) }
            }
            launch {
                val sorts = runCatching { YouTube.channelSorts(channelId) }.getOrDefault(emptyList())
                channelSortRows = sorts
                updateChannel(channelId) { it.copy(sorts = sorts.map { row -> row.title }) }
            }
            val page = runCatching { YouTube.channelRows(channelId) }.fold(
                onSuccess = { Page(it, loading = false, error = if (it.isEmpty()) "Aucune vidéo." else null) },
                onFailure = { Page(loading = false, error = it.userMessage()) },
            )
            channelHome = page.rows
            _state.update { s ->
                val opened = s.opened?.takeIf { it.channel?.id == channelId } ?: return@update s
                if (opened.channel?.sort != -1) return@update s
                s.copy(opened = opened.copy(page = page))
            }
            fetchDeArrow(page.rows)
        }
    }

    private fun updateChannel(channelId: String, change: (ChannelHeader) -> ChannelHeader) = _state.update { s ->
        val opened = s.opened ?: return@update s
        val channel = opened.channel?.takeIf { it.id == channelId } ?: return@update s
        s.copy(opened = opened.copy(channel = change(channel)))
    }

    /** "Accueil de la chaîne" (-1) or one of its orders. */
    fun chooseChannelSort(index: Int) {
        val channel = _state.value.opened?.channel ?: return
        val rows = if (index < 0) channelHome else listOfNotNull(channelSortRows.getOrNull(index))
        updateChannel(channel.id) { it.copy(sort = index) }
        _state.update { s -> s.opened?.let { s.copy(opened = it.copy(page = Page(rows, loading = false, error = if (rows.isEmpty()) "Aucune vidéo." else null))) } ?: s }
        fetchDeArrow(rows)
    }

    fun toggleChannelSubscription() {
        val channel = _state.value.opened?.channel ?: return
        val target = channel.subscribed != true
        val account = _state.value.accountId
        updateChannel(channel.id) { it.copy(subscribed = target) }
        viewModelScope.launch {
            val result = YouTube.setSubscribed(channel.id, target, account)
            if (result != ActionResult.DONE) {
                updateChannel(channel.id) { it.copy(subscribed = !target) }
                showNotice(if (result == ActionResult.ACCOUNT_CHANGED) ACCOUNT_CHANGED_MESSAGE else "Impossible de modifier l’abonnement.")
            }
        }
    }

    // ---- Card menu (long press) -----------------------------------------------------------------

    fun openCardMenu(video: Video) = _state.update { it.copy(cardMenu = video) }

    fun closeCardMenu() = _state.update { it.copy(cardMenu = null) }

    fun cardWatchLater(video: Video) {
        closeCardMenu()
        val id = video.videoId ?: return
        val account = _state.value.accountId
        viewModelScope.launch {
            showNotice(
                when (YouTube.watchLater(id, account)) {
                    ActionResult.DONE -> "Ajoutée à « À regarder plus tard »."
                    ActionResult.ACCOUNT_CHANGED -> ACCOUNT_CHANGED_MESSAGE
                    ActionResult.FAILED -> "Impossible d’ajouter la vidéo."
                },
            )
        }
    }

    fun cardOpenChannel(video: Video) {
        val id = video.raw.channelId ?: return closeCardMenu()
        openChannel(id, video.channel, null)
    }

    /** "Pas intéressé" / "Ne pas recommander cette chaîne": the card (or the channel's cards) leaves the rows. */
    fun cardNotInterested(video: Video, channel: Boolean) {
        closeCardMenu()
        val account = _state.value.accountId
        viewModelScope.launch {
            val result = YouTube.notInterested(video, channel, account)
            if (result != ActionResult.DONE) {
                showNotice(if (result == ActionResult.ACCOUNT_CHANGED) ACCOUNT_CHANGED_MESSAGE else "YouTube n’a pas pris en compte la demande.")
                return@launch
            }
            val channelId = video.raw.channelId
            removeCards { it.key == video.key || (channel && channelId != null && it.raw.channelId == channelId) }
            showNotice(if (channel) "Cette chaîne ne sera plus recommandée." else "Tu ne verras plus cette vidéo.")
        }
    }

    private fun removeCards(matches: (Video) -> Boolean) = _state.update { s ->
        fun Page.without() = copy(rows = rows.map { row -> row.copy(items = row.items.filterNot(matches)) }.filter { it.items.isNotEmpty() })
        s.copy(
            pages = s.pages.mapValues { (_, page) -> page.without() },
            search = s.search?.without(),
            opened = s.opened?.let { it.copy(page = it.page.without()) },
            resume = s.resume?.let { row -> row.copy(items = row.items.filterNot(matches)).takeIf { it.items.isNotEmpty() } },
        )
    }

    private var noticeJob: Job? = null

    fun showNotice(text: String) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = text) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.update { it.copy(notice = null) }
        }
    }

    // ---- "Regarder sur la TV" (phone remote) ------------------------------------------------------

    private var foreground = false

    /** The activity is on screen (or not): commands are listened to only then, when it's enabled. */
    fun onForeground(on: Boolean) {
        foreground = on
        RemoteControl.uiForeground = on
        // Off screen, the background service (when enabled) keeps listening.
        if (on && _state.value.settings.remote) RemoteControl.start(getApplication())
        else if (!dev.miraiijo.lumentube.remote.CastService.running) RemoteControl.stop()
    }

    /** "Lier un téléphone": the TV code; listening starts so the phone finds the TV right away. */
    fun openPairing() {
        // Over Réglages (they stay behind it, on Téléphone).
        _state.update { it.copy(pairing = Pairing()) }
        if (!_state.value.settings.remote) updateSettings(_state.value.settings.copy(remote = true))
        viewModelScope.launch {
            val code = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { RemoteControl.pairingCode().timeout(20, java.util.concurrent.TimeUnit.SECONDS).blockingFirst() }.getOrNull()
            }
            _state.update { s ->
                if (s.pairing == null) s
                else s.copy(pairing = if (code.isNullOrBlank()) Pairing(error = "Code indisponible. Réessaie.") else Pairing(code = code.chunked(3).joinToString(" ")))
            }
        }
    }

    fun closePairing() = _state.update { it.copy(pairing = null) }

    private fun onRemote(event: RemoteControl.Event) {
        when (event) {
            is RemoteControl.Event.Open -> {
                val s = _state.value
                val current = s.playing?.videoId ?: s.playingId
                // The video already playing: only its position (the player handles the Seek).
                if (current == event.videoId) {
                    if (s.minimized) restore()
                } else if (playingAs) {
                    // A Lumen launch was playing as another account: the phone's video plays with LumenTube's.
                    playLink(event.videoId, (event.startMs / 1000).toInt())
                } else {
                    closeDialogs()
                    _state.update { it.copy(playing = null, playingId = event.videoId, playingStart = (event.startMs / 1000).toInt(), minimized = false, cardMenu = null) }
                }
            }
            RemoteControl.Event.Stop -> if (_state.value.let { it.playing != null || it.playingId != null }) stopPlaying()
            RemoteControl.Event.StateRequest -> if (_state.value.let { it.playing == null && it.playingId == null }) RemoteControl.reportIdle()
            is RemoteControl.Event.Connected -> {
                showNotice("${event.device?.takeIf { it.isNotBlank() } ?: "Un téléphone"} est connecté.")
                if (_state.value.pairing != null) closePairing()
            }
            RemoteControl.Event.Disconnected -> showNotice("Téléphone déconnecté.")
            else -> Unit // seek, play, pause, next: the player screen
        }
    }

    /** "À suivre" from the player: the next video replaces the current one. */
    fun playNext(video: Video) = _state.update { it.copy(playing = video, playingId = null) }

    fun closeOpened() = _state.update { it.copy(opened = null) }

    /**
     * [accountId] (Lumen, contract v2): play as that account, for this playback only (history and
     * position go to it); LumenTube's own account comes back when the video stops. Unknown or the
     * same: the selected account. [skipPickerAsked]: no "Qui regarde ?".
     */
    fun playLink(videoId: String, startSeconds: Int = 0, accountId: String? = null, skipPickerAsked: Boolean = false) {
        // A launch for an account plays at once: "Qui regarde ?" never covers it.
        if (skipPickerAsked || accountId != null) {
            skipPicker = true
            if (_state.value.choosingAccount) skipAccountChoice()
        }
        closeDialogs()
        // No account and nothing played as another one: as before, at once.
        if (accountId == null && !playingAs) {
            _state.update { it.copy(playing = null, playingId = videoId, playingStart = startSeconds, minimized = false) }
            return
        }
        viewModelScope.launch {
            // The video playing goes first: its last position is saved to its own account.
            val hadVideo = _state.value.let { it.playing != null || it.playingId != null }
            if (hadVideo) _state.update { it.copy(playing = null, playingId = null) }
            // One account change at a time, in order (a stop then a new launch never cross).
            playAsMutex.withLock {
                val was = playingAs
                if (was || hadVideo) awaitHistoryWrites()
                // An account given: play as it; none (or unknown, or LumenTube's own): back to LumenTube's.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YouTube.beginPlayAs(accountId) } }
                playingAs = YouTube.playingAsOther
                if (was && !playingAs) reloadHome()
            }
            _state.update { it.copy(playing = null, playingId = videoId, playingStart = startSeconds, minimized = false) }
        }
    }

    /** One account change at a time (begin / end of a play-as). */
    private val playAsMutex = kotlinx.coroutines.sync.Mutex()

    /** The last position save goes to the account played: waits for it (dispose, then the write). */
    private suspend fun awaitHistoryWrites() {
        delay(HISTORY_SETTLE_MS)
        var waited = 0L
        while (YouTube.historyWrites.get() > 0 && waited < HISTORY_WAIT_MS) {
            delay(100)
            waited += 100
        }
    }

    /** After a Lumen launch played as another account: LumenTube's own account and pages again. */
    private fun endPlayingAs() {
        if (!playingAs) return
        viewModelScope.launch {
            playAsMutex.withLock {
                if (!playingAs) return@withLock
                awaitHistoryWrites()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YouTube.endPlayAs() } }
                playingAs = false
                reloadHome()
                // "Reprendre" of LumenTube's account (not the one just played as).
                loadResume()
            }
        }
    }

    /** Pages loaded meanwhile may be the other account's: reloaded for LumenTube's own. */
    private fun reloadHome() {
        cancelLoads()
        _state.update { it.copy(pages = emptyMap(), resume = null, search = null, opened = null) }
        refreshAccount()
        show(_state.value.section.takeIf { it != Section.SEARCH } ?: Section.HOME)
    }

    /**
     * A video arriving from the phone or a link: the dialogs close (each is its own window, they
     * stayed over the player and the remote drove them instead of the video).
     */
    private fun closeDialogs() {
        val s = _state.value
        if (s.signingIn) cancelSignIn()
        if (s.choosingAccount) skipAccountChoice()
        _state.update { it.copy(settingsOpen = false, accountsOpen = false, pairing = null, cardMenu = null) }
    }

    /** Small window in a corner; browsing goes on. */
    fun minimize() = _state.update { it.copy(minimized = true) }

    /** Back to full screen. */
    fun restore() = _state.update { it.copy(minimized = false) }

    fun stopPlaying() {
        _state.update { it.copy(playing = null, playingId = null, minimized = false) }
        // A Lumen launch played as another account: its end reloads LumenTube's (Reprendre included).
        if (playingAs) {
            endPlayingAs()
            return
        }
        // "Reprendre" follows what was just watched (YouTube needs a moment to record the position).
        if (_state.value.signedIn) {
            viewModelScope.launch {
                delay(RESUME_REFRESH_DELAY_MS)
                loadResume()
            }
        }
    }

    // ---- Self-update (Réglages › Mises à jour) -------------------------------------------------

    /** The installed version, then the silent daily check (a notice when a version is ready). */
    private fun initUpdates() {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                update = UpdateUi(
                    installed = runCatching { Updater.installedVersionName(app) }.getOrDefault(""),
                    wantsBeta = runCatching { Updater.wantsBeta(app) }.getOrDefault(false),
                ),
            )
        }
        viewModelScope.launch {
            if (!runCatching { Updater.dailyCheckDue(app) }.getOrDefault(false)) return@launch
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Updater.check(app, UPDATE_APP) } }
            // Recorded only once the channel answered: offline at start-up, the next start checks again.
            if (result.isSuccess) runCatching { Updater.markChecked(app) }
            val found = result.getOrNull()
            if (found != null) {
                _state.update { it.copy(update = it.update.copy(found = found)) }
                showNotice("Mise à jour disponible : ${found.versionName} (Réglages › Mises à jour).")
            }
        }
    }

    fun setWantsBeta(on: Boolean) {
        runCatching { Updater.setWantsBeta(getApplication(), on) }
        _state.update { it.copy(update = it.update.copy(wantsBeta = on, found = null, message = null)) }
        checkUpdate()
    }

    fun checkUpdate() {
        if (_state.value.update.busy) return
        val app = getApplication<Application>()
        _state.update { it.copy(update = it.update.copy(busy = true, message = null)) }
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Updater.check(app, UPDATE_APP) } }
            _state.update {
                it.copy(
                    update = it.update.copy(
                        busy = false,
                        found = result.getOrNull(),
                        message = result.fold(
                            { found -> if (found == null) "LumenTube est à jour." else "Nouvelle version : ${found.versionName}." },
                            { e -> e.message ?: "Vérification impossible." },
                        ),
                    ),
                )
            }
        }
    }

    fun installUpdate() {
        val current = _state.value.update
        val found = current.found ?: return
        if (current.busy || Updater.isDownloading()) return
        val app = getApplication<Application>()
        if (!runCatching { Updater.canInstall(app) }.getOrDefault(false)) {
            runCatching { Updater.openInstallPermissionSettings(app) }
            _state.update { it.copy(update = it.update.copy(message = "Autorise LumenTube à installer des applis, puis relance l’installation.")) }
            return
        }
        _state.update { it.copy(update = it.update.copy(busy = true, progress = 0f, message = null)) }
        viewModelScope.launch {
            var shown = 0f
            val apk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    Updater.download(app, found) { p ->
                        // A step every 2 %: no state update per 64 KB block.
                        if (p - shown >= 0.02f || p >= 1f) {
                            shown = p
                            _state.update { it.copy(update = it.update.copy(progress = p.coerceIn(0f, 1f))) }
                        }
                    }
                }
            }
            _state.update { it.copy(update = it.update.copy(busy = false, progress = null)) }
            apk.fold(
                { file ->
                    runCatching { Updater.install(app, file) }
                        .onFailure { e -> _state.update { it.copy(update = it.update.copy(message = "Installation impossible : ${e.message}")) } }
                },
                { e -> _state.update { it.copy(update = it.update.copy(message = "Échec : ${e.message ?: "téléchargement impossible"}")) } },
            )
        }
    }

    // ---- Settings -----------------------------------------------------------------------------

    fun openSettings() = _state.update { it.copy(settingsOpen = true) }

    fun openAccounts() {
        _state.update { it.copy(accountsOpen = true) }
        refreshAccount()
    }

    fun closeAccounts() = _state.update { it.copy(accountsOpen = false) }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }

    fun updateSettings(settings: Settings) {
        prefs.sponsorBlock = settings.sponsorBlock
        prefs.autoplay = settings.autoplay
        prefs.stableVolume = settings.stableVolume
        prefs.deArrow = settings.deArrow
        prefs.remote = settings.remote
        prefs.appearance = settings.appearance
        prefs.uiScale = settings.uiScale
        prefs.backgroundCast = settings.backgroundCast
        if (settings.remote != _state.value.settings.remote) {
            if (settings.remote && foreground) RemoteControl.start(getApplication()) else if (!settings.remote) RemoteControl.stop()
        }
        val deArrowTurnedOn = settings.deArrow && !_state.value.settings.deArrow
        if (!settings.deArrow) deArrowAsked.clear()
        _state.update { it.copy(settings = settings, deArrow = if (settings.deArrow) it.deArrow else emptyMap()) }
        dev.miraiijo.lumentube.remote.CastService.sync(getApplication(), settings)
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
                _state.update { it.copy(signingIn = false, signInCode = null, choosingAccount = false, accountsOpen = false, settingsOpen = false, pages = emptyMap(), resume = null) }
                refreshAccount()
                show(Section.HOME)
            },
        )
    }

    /** Account picked at launch ("Qui regarde ?") or in the settings: Accueil reloads for it. */
    fun chooseAccount(account: Account) {
        viewModelScope.launch {
            if (!account.selected) YouTube.selectAccount(account)
            // Playing as another account for Lumen: the new home loads when that video stops.
            if (playingAs) {
                _state.update { it.copy(choosingAccount = false, accountsOpen = false, settingsOpen = false) }
                refreshAccount()
                return@launch
            }
            cancelLoads()
            _state.update { it.copy(choosingAccount = false, accountsOpen = false, settingsOpen = false, pages = emptyMap(), resume = null, search = null, section = Section.HOME, opened = null) }
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
            // LumenTube's own account (its pages), not one a Lumen launch plays as.
            val id = if (signed) YouTube.homeAccountId() else null
            _state.update {
                it.copy(
                    signedIn = signed,
                    accountId = id,
                    accountName = if (signed) YouTube.accountName else null,
                    accountAvatar = if (signed) YouTube.accountAvatar else null,
                    accounts = accounts,
                )
            }
        }
    }

    private companion object {
        /** Saved state (survives the process being killed in the background). */
        const val KEY_SECTION = "section"
        const val KEY_VIDEO = "video"
        const val KEY_POSITION = "position"
        /** "Qui regarde ?" answered (false: still on screen). Absent: a fresh start. */
        const val KEY_PICKED = "picked"
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        const val DEARROW_BATCH = 50
        const val RESUME_REFRESH_DELAY_MS = 1_500L
        const val SUGGEST_DELAY_MS = 300L
        const val NOTICE_MS = 3_000L
        /** This app's file on the update channel (lumen-releases › channel › lumentube.json). */
        const val UPDATE_APP = "lumentube"
        /** After a play-as video stops: the player's last position save starts (dispose)… */
        const val HISTORY_SETTLE_MS = 400L
        /** …and is waited for, at most this long, before LumenTube's account comes back. */
        const val HISTORY_WAIT_MS = 5_000L
    }

    override fun onCleared() {
        RemoteControl.uiAttached = false
        // Leaving while a Lumen launch played as another account: LumenTube's own one again.
        if (playingAs) {
            AppScope.launch {
                delay(HISTORY_SETTLE_MS)
                var waited = 0L
                while (YouTube.historyWrites.get() > 0 && waited < HISTORY_WAIT_MS) {
                    delay(100)
                    waited += 100
                }
                runCatching { YouTube.endPlayAs() }
            }
        }
        accountListener?.let(YouTube::removeAccountListener)
        signIn?.dispose()
    }

    // RxJava's blocking calls wrap checked exceptions (TimeoutException…) in a RuntimeException.
    private fun Throwable.userMessage(): String = when (val e = if (this.javaClass == RuntimeException::class.java) cause ?: this else this) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "Pas de connexion à Internet."
        is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> "YouTube ne répond pas. Réessaie."
        else -> "Impossible de charger cette page (${e::class.java.simpleName})."
    }
}
