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
import dev.miraiijo.lumentube.remote.RemoteControl
import dev.miraiijo.lumentube.ui.UI_SCALE_DEFAULT
import kotlinx.coroutines.delay
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

/** "Lier un téléphone": the TV code to type in the phone's YouTube app (null while it's requested). */
data class Pairing(val code: String? = null, val error: String? = null)

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
    /** Declared before init (which sets it): initializers run in order. */
    private var accountListener: (() -> Unit)? = null

    init {
        RemoteControl.uiAttached = true
        dev.miraiijo.lumentube.remote.CastService.sync(getApplication(), _state.value.settings)
        // Account switched from Lumen (or anywhere else): the pages reload for the new account.
        accountListener = YouTube.onAccountChanged {
            viewModelScope.launch {
                val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.accountName }
                if (name != _state.value.accountName && !_state.value.choosingAccount) {
                    loads.values.forEach { it.cancel() }
                    _state.update { it.copy(pages = emptyMap(), resume = null, search = null, opened = null) }
                    refreshAccount()
                    show(_state.value.section.takeIf { it != Section.SEARCH } ?: Section.HOME)
                }
            }
        }
        viewModelScope.launch { RemoteControl.events.collect(::onRemote) }
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
                    else -> Page(loading = false, error = "Rien à afficher ici pour l’instant.")
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
        updateChannel(channel.id) { it.copy(subscribed = target) }
        viewModelScope.launch {
            if (!YouTube.setSubscribed(channel.id, target)) {
                updateChannel(channel.id) { it.copy(subscribed = !target) }
                showNotice("Impossible de modifier l’abonnement.")
            }
        }
    }

    // ---- Card menu (long press) -----------------------------------------------------------------

    fun openCardMenu(video: Video) = _state.update { it.copy(cardMenu = video) }

    fun closeCardMenu() = _state.update { it.copy(cardMenu = null) }

    fun cardWatchLater(video: Video) {
        closeCardMenu()
        val id = video.videoId ?: return
        viewModelScope.launch { showNotice(if (YouTube.watchLater(id)) "Ajoutée à « À regarder plus tard »." else "Impossible d’ajouter la vidéo.") }
    }

    fun cardOpenChannel(video: Video) {
        val id = video.raw.channelId ?: return closeCardMenu()
        openChannel(id, video.channel, null)
    }

    /** "Pas intéressé" / "Ne pas recommander cette chaîne": the card (or the channel's cards) leaves the rows. */
    fun cardNotInterested(video: Video, channel: Boolean) {
        closeCardMenu()
        viewModelScope.launch {
            if (!YouTube.notInterested(video, channel)) {
                showNotice("YouTube n’a pas pris en compte la demande.")
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

    fun playLink(videoId: String, startSeconds: Int = 0) {
        closeDialogs()
        _state.update { it.copy(playing = null, playingId = videoId, playingStart = startSeconds, minimized = false) }
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
            loads.values.forEach { it.cancel() }
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
        const val SUGGEST_DELAY_MS = 300L
        const val NOTICE_MS = 3_000L
    }

    override fun onCleared() {
        RemoteControl.uiAttached = false
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
