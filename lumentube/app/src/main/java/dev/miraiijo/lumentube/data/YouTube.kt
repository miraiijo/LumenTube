package dev.miraiijo.lumentube.data

import com.liskovsoft.mediaserviceinterfaces.ServiceManager
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import dev.miraiijo.lumentube.ui.LumenIcon
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The side menu's pages. [account]: only shown once signed in. */
enum class Section(val label: String, val icon: LumenIcon, val account: Boolean = false) {
    HOME("Accueil", LumenIcon.HOME),
    SUBSCRIPTIONS("Abonnements", LumenIcon.SUBSCRIPTIONS, account = true),
    TRENDING("Tendances", LumenIcon.FLAME),
    MUSIC("Musique", LumenIcon.MUSIC),
    GAMING("Gaming", LumenIcon.GAMEPAD),
    NEWS("Actus", LumenIcon.NEWS),
    SPORTS("Sport", LumenIcon.TROPHY),
    LIVE("Direct", LumenIcon.LIVE),
    HISTORY("Historique", LumenIcon.HISTORY, account = true),
    PLAYLISTS("Playlists", LumenIcon.PLAYLIST, account = true),
}

/** A card: video, channel or playlist (the engine's MediaItem, kept to open it). */
data class Video(
    val key: String,
    val videoId: String?,
    val title: String,
    val channel: String,
    val thumbnail: String?,
    val durationMs: Long,
    /** 0..100, from the account's history. */
    val watchedPercent: Int,
    val live: Boolean,
    val upcoming: Boolean,
    val badge: String?,
    val kind: Int,
    val raw: MediaItem,
) {
    val isVideo get() = kind == MediaItem.TYPE_VIDEO || kind == MediaItem.TYPE_MUSIC || kind == MediaItem.TYPE_UNDEFINED && videoId != null
}

/** A row of cards; [raw] loads the next page (continueGroup). */
data class VideoRow(val key: String, val title: String, val items: List<Video>, val raw: MediaGroup, val canLoadMore: Boolean = true)

/**
 * SmartTube's engine (MediaServiceCore) behind suspend functions. Every call blocks on the network,
 * so it runs on [Dispatchers.IO].
 */
object YouTube {
    private val service: ServiceManager get() = YouTubeServiceManager.instance()
    private const val OBSERVE_TIMEOUT_S = 30L

    suspend fun rows(section: Section): List<VideoRow> = withContext(Dispatchers.IO) {
        val content = service.contentService
        val groups: List<MediaGroup?> = when (section) {
            Section.HOME -> content.homeObserve.collectAll()
            Section.SUBSCRIPTIONS -> listOf(content.subscriptions)
            Section.TRENDING -> content.trendingObserve.collectAll()
            Section.MUSIC -> content.musicObserve.collectAll()
            Section.GAMING -> content.gamingObserve.collectAll()
            Section.NEWS -> content.newsObserve.collectAll()
            Section.SPORTS -> content.sportsObserve.collectAll()
            Section.LIVE -> content.liveObserve.collectAll()
            Section.HISTORY -> listOf(content.history)
            Section.PLAYLISTS -> content.playlistRowsObserve.collectAll()
        }
        groups.filterNotNull().mapIndexedNotNull { index, group -> group.toRow("${section.name}-$index") }
    }

    /** Next page of a row (null when there's no more). */
    suspend fun more(row: VideoRow): VideoRow? = withContext(Dispatchers.IO) {
        val next = runCatching { service.contentService.continueGroup(row.raw) }.getOrNull()
        val added = next?.toRow(row.key)?.items.orEmpty().filter { new -> row.items.none { it.key == new.key } }
        if (next == null || added.isEmpty()) row.copy(canLoadMore = false) else row.copy(items = row.items + added, raw = next)
    }

    /** A channel or playlist card opened: its videos. */
    suspend fun open(item: Video): List<VideoRow> = withContext(Dispatchers.IO) {
        val content = service.contentService
        val groups = if (item.kind == MediaItem.TYPE_CHANNEL) content.getChannelObserve(item.raw).collectAll() else listOf(content.getGroup(item.raw))
        groups.filterNotNull().mapIndexedNotNull { index, group -> group.toRow("open-${item.key}-$index") }
    }

    suspend fun search(query: String): List<VideoRow> = withContext(Dispatchers.IO) {
        service.contentService.getSearch(query).orEmpty().mapIndexedNotNull { index, group -> group.toRow("search-$index") }
    }

    suspend fun formatInfo(videoId: String): MediaItemFormatInfo? = withContext(Dispatchers.IO) {
        service.mediaItemService.getFormatInfo(videoId)
    }

    /** Keeps YouTube's history (and "resume where you stopped") up to date. */
    suspend fun savePosition(videoId: String, seconds: Float) = withContext(Dispatchers.IO) {
        runCatching { service.mediaItemService.updateHistoryPosition(videoId, seconds) }
    }

    val signedIn: Boolean get() = runCatching { service.signInService.isSigned }.getOrDefault(false)

    val accountName: String? get() = runCatching { service.signInService.selectedAccount?.name }.getOrNull()

    val accountAvatar: String? get() = runCatching { service.signInService.selectedAccount?.avatarImageUrl }.getOrNull()

    /**
     * Sign-in with a code typed on youtube.com/activate (from a phone or computer).
     * [onCode] gets the code to show, [onDone] runs once the account is linked.
     */
    fun signIn(onCode: (String) -> Unit, onError: (String) -> Unit, onDone: () -> Unit): Disposable =
        service.signInService.signInObserve()
            .subscribeOn(Schedulers.io())
            .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
            .subscribe({ onCode(it) }, { onError(it.message ?: "Connexion impossible") }, { service.invalidateCache(); onDone() })

    fun signOut() {
        runCatching {
            val signIn = service.signInService
            signIn.selectedAccount?.let(signIn::removeAccount)
            service.invalidateCache()
        }
    }

    /** Every emission of a (finite) observable, flattened: some sections arrive in several parts. */
    private fun Observable<List<MediaGroup>>.collectAll(): List<MediaGroup?> =
        timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS).toList().blockingGet().flatten()

    private fun MediaGroup.toRow(key: String): VideoRow? {
        val items = mediaItems.orEmpty().filterNotNull().filter { !it.isShorts }.map { it.toVideo() }.distinctBy { it.key }
        if (items.isEmpty()) return null
        return VideoRow(key, title?.takeIf { it.isNotBlank() } ?: "Vidéos", items, this)
    }

    private fun MediaItem.toVideo() = Video(
        key = videoId ?: channelId?.let { "channel:$it" } ?: playlistId?.let { "playlist:$it" } ?: "item:$id:$title",
        videoId = videoId,
        title = title.orEmpty(),
        channel = author ?: secondTitle?.toString().orEmpty(),
        thumbnail = cardImageUrl ?: backgroundImageUrl,
        durationMs = durationMs,
        watchedPercent = percentWatched.coerceIn(0, 100),
        live = isLive,
        upcoming = isUpcoming,
        badge = badgeText?.takeIf { it.isNotBlank() },
        kind = type,
        raw = this,
    )
}
