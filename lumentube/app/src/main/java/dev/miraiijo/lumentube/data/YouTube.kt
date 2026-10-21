package dev.miraiijo.lumentube.data

import com.liskovsoft.mediaserviceinterfaces.ServiceManager
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import dev.miraiijo.lumentube.feed.FeedCache
import dev.miraiijo.lumentube.ui.LumenIcon
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The side menu's pages. [account]: only shown once signed in. */
enum class Section(val label: String, val icon: LumenIcon, val account: Boolean = false) {
    // No "Tendances": YouTube removed its Trending page in 2025, the feed never answers anymore.
    SEARCH("Rechercher", LumenIcon.SEARCH),
    HOME("Accueil", LumenIcon.HOME),
    SUBSCRIPTIONS("Abonnements", LumenIcon.SUBSCRIPTIONS, account = true),
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
    /** Picture replacing the thumbnail (DeArrow), shown instead of the HD one. */
    val customThumbnail: String? = null,
) {
    val isChannel get() = kind == MediaItem.TYPE_CHANNEL
    val isVideo get() = kind == MediaItem.TYPE_VIDEO || kind == MediaItem.TYPE_MUSIC || kind == MediaItem.TYPE_UNDEFINED && videoId != null
}

/** A SponsorBlock segment the player skips. */
data class Segment(val startMs: Long, val endMs: Long, val category: String) {
    val label: String
        get() = when (category) {
            SponsorSegment.CATEGORY_SPONSOR -> "Sponsor"
            SponsorSegment.CATEGORY_SELF_PROMO -> "Autopromo"
            SponsorSegment.CATEGORY_INTERACTION -> "Rappel d'abonnement"
            SponsorSegment.CATEGORY_INTRO -> "Intro"
            SponsorSegment.CATEGORY_OUTRO -> "Générique de fin"
            else -> "Segment"
        }
}

data class Chapter(val title: String, val startMs: Long)

/** What the player shows next to a video: description, actions' state, chapters, next video. */
data class Details(
    val description: String,
    val views: String?,
    val published: String?,
    val likes: String?,
    val liked: Boolean,
    val subscribed: Boolean,
    val channelId: String?,
    val channel: String?,
    val chapters: List<Chapter>,
    /** Next video: the playlist's next item, else YouTube's "À suivre". */
    val next: Video?,
)

/** A YouTube account linked on this box. */
data class Account(val name: String, val avatar: String?, val selected: Boolean, val raw: com.liskovsoft.mediaserviceinterfaces.oauth.Account)

/** DeArrow's community title / thumbnail for a video (each may be missing). */
data class DeArrow(val title: String?, val thumbnail: String?)

/** A row of cards; [raw] loads the next page (continueGroup). */
data class VideoRow(val key: String, val title: String, val items: List<Video>, val raw: MediaGroup, val canLoadMore: Boolean = true)

/**
 * SmartTube's engine (MediaServiceCore) behind suspend functions. Every call blocks on the network,
 * so it runs on [Dispatchers.IO].
 */
object YouTube {
    private val service: ServiceManager get() = YouTubeServiceManager.instance()
    private const val OBSERVE_TIMEOUT_S = 30L

    /**
     * A section's rows, as they arrive: YouTube sends Accueil (and the other feeds) in several
     * parts, each one is shown as soon as it's here instead of waiting for the whole page.
     * Every emission is the full list so far.
     */
    fun rows(section: Section): Flow<List<VideoRow>> = flow {
        val content = service.contentService
        val rows = mutableListOf<VideoRow>()
        var index = 0

        /** Adds the groups of [parts] as they arrive; [limit] rows at most, [titled] names untitled ones. */
        suspend fun addFeed(parts: Observable<List<MediaGroup>>, limit: Int = Int.MAX_VALUE, titled: String? = null) {
            var added = 0
            val iterator = parts.timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS).blockingIterable().iterator()
            try {
                while (iterator.hasNext()) {
                    for (group in iterator.next().filterNotNull()) {
                        if (added >= limit) break
                        val row = group.toRow("${section.name}-${index++}", titled) ?: continue
                        rows += row
                        added++
                    }
                    emit(rows.toList())
                    if (added >= limit) break
                }
            } finally {
                // Stops the feed's remaining requests (continuations nobody will show).
                (iterator as? Disposable)?.dispose()
            }
        }

        when (section) {
            Section.SEARCH -> emit(emptyList())
            Section.HOME -> {
                // YouTube's own home first, then a taste of each feed: Accueil is never a single row.
                safely { addFeed(content.homeObserve) }
                val extras = buildList<Pair<Observable<List<MediaGroup>>, String>> {
                    if (signedIn) add(Observable.fromCallable { listOfNotNull(subscriptionsForFeed()) } to "Nouveautés des abonnements")
                    add(content.musicObserve to Section.MUSIC.label)
                    add(content.gamingObserve to Section.GAMING.label)
                    add(content.newsObserve to Section.NEWS.label)
                    add(content.sportsObserve to Section.SPORTS.label)
                    add(content.liveObserve to Section.LIVE.label)
                }
                // One feed failing (or slow) never empties Accueil.
                for ((parts, title) in extras) safely { addFeed(parts, limit = 2, titled = title) }
            }
            Section.SUBSCRIPTIONS -> addFeed(Observable.fromCallable { listOfNotNull(subscriptionsForFeed()) })
            Section.MUSIC -> addFeed(content.musicObserve)
            Section.GAMING -> addFeed(content.gamingObserve)
            Section.NEWS -> addFeed(content.newsObserve)
            Section.SPORTS -> addFeed(content.sportsObserve)
            Section.LIVE -> addFeed(content.liveObserve)
            Section.HISTORY -> addFeed(Observable.fromCallable { listOfNotNull(historyForFeed()) })
            Section.PLAYLISTS -> addFeed(content.playlistRowsObserve)
        }
        if (rows.isEmpty()) emit(emptyList())
    }.flowOn(Dispatchers.IO)

    // ---- Lumen's feed (FeedProvider) -----------------------------------------------------------

    /** The account's subscriptions feed, also written to Lumen's feed cache. Blocking. */
    private fun subscriptionsForFeed(): MediaGroup? {
        val generation = FeedCache.generation()
        return service.contentService.subscriptions?.also {
            FeedCache.store(FeedCache.SUBSCRIPTIONS, FeedCache.itemsFrom(it.mediaItems.orEmpty(), FeedCache.SUBSCRIPTIONS), generation)
        }
    }

    /** The account's history, also written to Lumen's feed cache ("continue"). Blocking. */
    private fun historyForFeed(): MediaGroup? {
        val generation = FeedCache.generation()
        return service.contentService.history?.also {
            FeedCache.store(FeedCache.CONTINUE, FeedCache.itemsFrom(it.mediaItems.orEmpty(), FeedCache.CONTINUE), generation)
        }
    }

    /** Refresh of one feed list for the provider (off the main thread). True when it was written. */
    fun refreshFeed(list: String): Boolean = runCatching {
        if (!signedIn) return false
        when (list) {
            FeedCache.CONTINUE -> historyForFeed()
            FeedCache.SUBSCRIPTIONS -> subscriptionsForFeed()
            else -> null
        } != null
    }.getOrDefault(false)

    /** Runs [block], ignoring its failure (not a cancellation: the page was left, stop there). */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // This feed is skipped, the rest of the page still loads.
        }
    }

    /** "Reprendre": videos started and not finished, from the account's history (null if none). */
    suspend fun resumeRow(): VideoRow? = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext null
        val history = runCatching { historyForFeed() }.getOrNull() ?: return@withContext null
        val started = history.mediaItems.orEmpty().filterNotNull()
            .filter { !it.isShorts && !it.isLive && it.videoId != null && it.percentWatched in 3..94 }
            .map { it.toVideo() }
            .distinctBy { it.key }
            .take(20)
        if (started.isEmpty()) null else VideoRow("resume", "Reprendre", started, history, canLoadMore = false)
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

    /** Seek preview pictures (YouTube's storyboard), null when the video has none. */
    suspend fun storyboard(info: MediaItemFormatInfo): MediaItemStoryboard? = withContext(Dispatchers.IO) {
        runCatching { info.createStoryboard()?.also { it.groupSize } }.getOrNull()?.takeIf { it.groupDurationMS > 0 }
    }

    /** Description, like / subscription state, chapters and next video. [item]: the card played (playlist context). */
    suspend fun details(videoId: String, item: MediaItem?): Details? = withContext(Dispatchers.IO) {
        val meta = runCatching { if (item != null) service.mediaItemService.getMetadata(item) else service.mediaItemService.getMetadata(videoId) }.getOrNull()
            ?: return@withContext null
        Details(
            description = meta.description.orEmpty(),
            views = meta.viewCount?.takeIf { it.isNotBlank() },
            published = meta.publishedDate?.takeIf { it.isNotBlank() },
            likes = meta.likeCount?.takeIf { it.isNotBlank() },
            liked = meta.likeStatus == MediaItemMetadata.LIKE_STATUS_LIKE,
            subscribed = meta.isSubscribed,
            channelId = meta.channelId,
            channel = meta.author,
            chapters = meta.chapters.orEmpty().filterNotNull().map { Chapter(it.title.orEmpty(), it.startTimeMs) }.sortedBy { it.startMs },
            next = meta.nextVideo?.takeIf { it.videoId != null && it.videoId != videoId }?.toVideo(),
        )
    }

    /** Like (or remove the like). Needs an account. */
    suspend fun setLiked(videoId: String, item: MediaItem?, liked: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            val target = item ?: itemFor(videoId)
            if (liked) service.mediaItemService.setLike(target) else service.mediaItemService.removeLike(target)
        }.isSuccess
    }

    /** Adds the video to "À regarder plus tard" (YouTube's WL playlist). */
    suspend fun watchLater(videoId: String) = withContext(Dispatchers.IO) {
        runCatching { service.mediaItemService.addToPlaylistObserve(WATCH_LATER, videoId).blockingSubscribe() }.isSuccess
    }

    suspend fun setSubscribed(channelId: String, subscribed: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            if (subscribed) service.mediaItemService.subscribe(channelId) else service.mediaItemService.unsubscribe(channelId)
        }.isSuccess
    }

    /** DeArrow (sponsor.ajay.app) titles and thumbnails for these videos, by video id. */
    suspend fun deArrow(videoIds: List<String>): Map<String, DeArrow> = withContext(Dispatchers.IO) {
        if (videoIds.isEmpty()) return@withContext emptyMap()
        runCatching {
            service.mediaItemService.getDeArrowDataObserve(videoIds)
                .timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS)
                .toList().blockingGet()
                .filter { it.videoId != null }
                .associate { it.videoId to DeArrow(it.title?.takeIf { t -> t.isNotBlank() }, it.thumbnailUrl?.takeIf { u -> u.isNotBlank() }) }
        }.getOrDefault(emptyMap())
    }

    /** SponsorBlock segments to skip (sponsors, self-promotion, reminders, intro, end credits). */
    suspend fun skipSegments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        runCatching { service.mediaItemService.getSponsorSegments(videoId, SKIPPED_CATEGORIES) }.getOrNull().orEmpty()
            .filter { it.action == null || it.action == SponsorSegment.ACTION_SKIP }
            .filter { it.endMs > it.startMs }
            .map { Segment(it.startMs, it.endMs, it.category.orEmpty()) }
            .sortedBy { it.startMs }
    }

    private val SKIPPED_CATEGORIES = setOf(
        SponsorSegment.CATEGORY_SPONSOR,
        SponsorSegment.CATEGORY_SELF_PROMO,
        SponsorSegment.CATEGORY_INTERACTION,
        SponsorSegment.CATEGORY_INTRO,
        SponsorSegment.CATEGORY_OUTRO,
    )

    /** Keeps YouTube's history (and "resume where you stopped") up to date. */
    suspend fun savePosition(videoId: String, seconds: Float) = withContext(Dispatchers.IO) {
        runCatching { service.mediaItemService.updateHistoryPosition(videoId, seconds) }
    }

    val signedIn: Boolean get() = runCatching { service.signInService.isSigned }.getOrDefault(false)

    /** Accounts linked on this box (sign in again to add one). */
    suspend fun accounts(): List<Account> = withContext(Dispatchers.IO) {
        runCatching { service.signInService.accounts }.getOrNull().orEmpty().filterNotNull()
            .filter { !it.isEmpty }
            .map { Account(it.name ?: it.email ?: "Compte", it.avatarImageUrl, it.isSelected, it) }
    }

    suspend fun selectAccount(account: Account) = withContext(Dispatchers.IO) {
        runCatching {
            service.signInService.selectAccount(account.raw)
            service.invalidateCache()
            if (!account.selected) FeedCache.clear()
        }
    }

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
            .subscribe({ onCode(it) }, { onError(it.message ?: "Connexion impossible") }, {
                service.invalidateCache()
                FeedCache.clear()
                onDone()
            })

    fun signOut() {
        runCatching {
            val signIn = service.signInService
            signIn.selectedAccount?.let(signIn::removeAccount)
            service.invalidateCache()
            FeedCache.clear()
        }
    }

    /** Every emission of a (finite) observable, flattened: some sections arrive in several parts. */
    private fun Observable<List<MediaGroup>>.collectAll(): List<MediaGroup?> =
        timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS).toList().blockingGet().flatten()

    private fun MediaGroup.toRow(key: String, fallbackTitle: String? = null): VideoRow? {
        val items = mediaItems.orEmpty().filterNotNull().filter { !it.isShorts }.map { it.toVideo() }.distinctBy { it.key }
        if (items.isEmpty()) return null
        val own = title?.takeIf { it.isNotBlank() }
        // A feed's row on Accueil: "Musique · Top titres" (or just "Musique").
        val shown = when {
            fallbackTitle == null -> own ?: "Vidéos"
            own == null || own == fallbackTitle -> fallbackTitle
            else -> "$fallbackTitle · $own"
        }
        return VideoRow(key, shown, items, this)
    }

    /**
     * A bare MediaItem holding only a video id, for engine calls that take an item but only read its
     * id (like / remove like) when the video was opened from a link, without a card.
     */
    private fun itemFor(videoId: String): MediaItem =
        java.lang.reflect.Proxy.newProxyInstance(MediaItem::class.java.classLoader, arrayOf(MediaItem::class.java)) { _, method, _ ->
            when {
                method.name == "getVideoId" -> videoId
                method.name == "getType" -> MediaItem.TYPE_VIDEO
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                method.returnType == Float::class.javaPrimitiveType -> 0f
                method.returnType == Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        } as MediaItem

    private const val WATCH_LATER = "WL"

    /** An engine item (e.g. the next video) as a card. */
    fun video(item: MediaItem): Video = item.toVideo()

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
