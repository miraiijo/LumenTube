package dev.miraiijo.lumentube.feed

import android.content.Context
import android.net.Uri
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Feed read by the Lumen launcher (see FeedProvider): "continue" (videos started, not finished)
 * and "subscriptions" (latest videos), kept as a JSON file in filesDir. Written each time
 * LumenTube loads its history or its subscriptions, and by the provider when it refreshes them.
 * Only public video data is stored: no token, cookie or account id.
 */
object FeedCache {
    const val AUTHORITY = "dev.miraiijo.lumentube.feed"
    const val CONTINUE = "continue"
    const val SUBSCRIPTIONS = "subscriptions"
    const val MAX_ROWS = 20

    /** Contract with Lumen (frozen): column names and order. */
    val COLUMNS = arrayOf("video_id", "title", "channel", "thumbnail_url", "duration_ms", "watched_percent", "position_s", "published_ms", "live")

    data class Item(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnailUrl: String,
        val durationMs: Long,
        val watchedPercent: Int,
        val positionS: Long,
        val publishedMs: Long,
        val live: Boolean,
    ) {
        fun toRow(): Array<Any> = arrayOf(videoId, title, channel, thumbnailUrl, durationMs, watchedPercent, positionS, publishedMs, if (live) 1 else 0)
    }

    private const val FILE = "lumen_feed.json"
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    @Volatile
    private var context: Context? = null

    /**
     * Account generation: bumped by [clear]. A request started before an account switch carries the
     * old value, and its result is dropped instead of showing the previous account's videos.
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    /** Taken before fetching, passed back to [store]. */
    fun generation(): Int = generation.get()

    /** Called by the app and by the provider (whichever starts first). */
    fun attach(context: Context) {
        if (this.context == null) this.context = context.applicationContext
    }

    fun uri(list: String): Uri = Uri.parse("content://$AUTHORITY/$list")

    /** Feed rows from engine items: videos only (no Shorts, no channels / playlists), at most [MAX_ROWS]. */
    fun itemsFrom(items: List<MediaItem?>, list: String): List<Item> = items.asSequence()
        .filterNotNull()
        .filter { !it.isShorts && it.type != MediaItem.TYPE_CHANNEL && it.type != MediaItem.TYPE_PLAYLIST }
        .filter { it.videoId?.let(VIDEO_ID::matches) == true }
        .filter { list != CONTINUE || it.percentWatched in 1..94 }
        .distinctBy { it.videoId }
        .map { item ->
            val id = item.videoId
            val durationMs = item.durationMs.coerceAtLeast(0L)
            val percent = item.percentWatched.coerceIn(0, 100)
            val position = item.startTimeSeconds.toLong().takeIf { it > 0 } ?: (durationMs / 1000 * percent / 100)
            Item(
                videoId = id,
                title = item.title.orEmpty(),
                channel = item.author ?: item.secondTitle?.toString().orEmpty(),
                thumbnailUrl = httpsThumbnail(item.cardImageUrl, id),
                durationMs = durationMs,
                watchedPercent = percent,
                positionS = position,
                publishedMs = item.publishedDate.takeIf { it > 0 } ?: 0L,
                live = item.isLive,
            )
        }
        .take(MAX_ROWS)
        .toList()

    /** Replaces one list and tells the readers (Lumen) it changed; dropped if the account changed since [fetchedIn]. */
    fun store(list: String, items: List<Item>, fetchedIn: Int) {
        val ctx = context ?: return
        synchronized(this) {
            if (fetchedIn != generation.get()) return
            val root = readRoot(ctx)
            root.put(list, JSONObject().put("updated_ms", System.currentTimeMillis()).put("items", JSONArray(items.map { it.toJson() })))
            // AtomicFile: written to a side file, synced, then swapped (a power cut never leaves it empty).
            val file = android.util.AtomicFile(File(ctx.filesDir, FILE))
            runCatching {
                val out = file.startWrite()
                try {
                    out.write(root.toString().toByteArray())
                    file.finishWrite(out)
                } catch (e: java.io.IOException) {
                    file.failWrite(out)
                }
            }
        }
        runCatching { ctx.contentResolver.notifyChange(uri(list), null) }
    }

    /** Account changed or signed out: nothing of the previous account stays readable. */
    fun clear() {
        val ctx = context ?: return
        synchronized(this) {
            generation.incrementAndGet()
            runCatching { android.util.AtomicFile(File(ctx.filesDir, FILE)).delete() }
        }
        runCatching {
            ctx.contentResolver.notifyChange(uri(CONTINUE), null)
            ctx.contentResolver.notifyChange(uri(SUBSCRIPTIONS), null)
        }
    }

    /** A list and when it was written (0: never). */
    fun load(list: String): Pair<Long, List<Item>> {
        val ctx = context ?: return 0L to emptyList()
        val entry = synchronized(this) { readRoot(ctx).optJSONObject(list) } ?: return 0L to emptyList()
        val array = entry.optJSONArray("items") ?: JSONArray()
        val items = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toItem() }
        return entry.optLong("updated_ms", 0L) to items.take(MAX_ROWS)
    }

    private fun readRoot(ctx: Context): JSONObject =
        runCatching { JSONObject(String(android.util.AtomicFile(File(ctx.filesDir, FILE)).readFully())) }.getOrDefault(JSONObject())

    private fun httpsThumbnail(url: String?, videoId: String): String = when {
        url.isNullOrBlank() -> "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        url.startsWith("https://") -> url
        else -> "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun Item.toJson() = JSONObject()
        .put("video_id", videoId).put("title", title).put("channel", channel).put("thumbnail_url", thumbnailUrl)
        .put("duration_ms", durationMs).put("watched_percent", watchedPercent).put("position_s", positionS)
        .put("published_ms", publishedMs).put("live", live)

    private fun JSONObject.toItem(): Item? {
        val id = optString("video_id").takeIf(VIDEO_ID::matches) ?: return null
        return Item(
            videoId = id,
            title = optString("title"),
            channel = optString("channel"),
            thumbnailUrl = optString("thumbnail_url").takeIf { it.startsWith("https://") } ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            durationMs = optLong("duration_ms"),
            watchedPercent = optInt("watched_percent").coerceIn(0, 100),
            positionS = optLong("position_s"),
            publishedMs = optLong("published_ms"),
            live = optBoolean("live"),
        )
    }
}
