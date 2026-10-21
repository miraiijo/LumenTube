package dev.miraiijo.lumentube.feed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import dev.miraiijo.lumentube.BuildConfig
import dev.miraiijo.lumentube.data.YouTube
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Read-only feed for the Lumen launcher (frozen contract):
 *  - content://dev.miraiijo.lumentube.feed/continue: videos started and not finished;
 *  - content://dev.miraiijo.lumentube.feed/subscriptions: latest videos of the subscriptions.
 * Only answers Lumen: the calling package must be dev.miraiijo.lumen, signed with Lumen's
 * certificate (SHA-256 in BuildConfig). Not a signature permission: the two apps have their own keys.
 * Serves the cache (FeedCache); older than 30 min and signed in: refreshed first, 8 s at most,
 * else the old cache is served. Signed out: an empty cursor. No token, cookie or account id.
 */
class FeedProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(FeedCache.AUTHORITY, FeedCache.CONTINUE, CODE_CONTINUE)
        addURI(FeedCache.AUTHORITY, FeedCache.SUBSCRIPTIONS, CODE_SUBSCRIPTIONS)
    }

    /** Refreshes run here, never on the caller's (or the main) thread. */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(): Boolean {
        context?.let(FeedCache::attach)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context ?: throw SecurityException("Not ready")
        // Who asks comes first: anything else is refused before the URI is even looked at.
        if (!isLumen(ctx)) throw SecurityException("This feed is only readable by the Lumen launcher")
        val list = when (matcher.match(uri)) {
            CODE_CONTINUE -> FeedCache.CONTINUE
            CODE_SUBSCRIPTIONS -> FeedCache.SUBSCRIPTIONS
            else -> throw IllegalArgumentException("Unknown feed: $uri")
        }
        val cursor = MatrixCursor(FeedCache.COLUMNS)
        // Providers start before Application.onCreate: the engine's prefs (account) must exist first.
        GlobalPreferences.instance(ctx.applicationContext)
        FeedCache.attach(ctx)
        if (!YouTube.signedIn) return cursor

        var (updatedMs, items) = FeedCache.load(list)
        if (System.currentTimeMillis() - updatedMs > MAX_AGE_MS) {
            val refresh = worker.submit<Boolean> { YouTube.refreshFeed(list) }
            val refreshed = runCatching { refresh.get(REFRESH_TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
            // Timed out: the refresh goes on in the background and notifies when it's written.
            if (refreshed) items = FeedCache.load(list).second
        }
        items.take(FeedCache.MAX_ROWS).forEach { cursor.addRow(it.toRow()) }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    /**
     * The caller is Lumen: its package name (given by the system, not by the caller) and its
     * current signing certificate. Any other app, or Lumen built with another key, is refused.
     */
    private fun isLumen(ctx: android.content.Context): Boolean {
        val expected = BuildConfig.LUMEN_CERT_SHA256
        val caller = callingPackage ?: return false
        if (caller != LUMEN_PACKAGE || expected.length != 64) return false
        // The system checks the calling app's certificate (key rotation included).
        return runCatching {
            ctx.packageManager.hasSigningCertificate(
                android.os.Binder.getCallingUid(),
                expected.hexToBytes(),
                android.content.pm.PackageManager.CERT_INPUT_SHA256,
            )
        }.getOrDefault(false)
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_CONTINUE, CODE_SUBSCRIPTIONS -> "vnd.android.cursor.dir/vnd.lumentube.video"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only feed")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only feed")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only feed")

    private companion object {
        const val CODE_CONTINUE = 1
        const val CODE_SUBSCRIPTIONS = 2
        const val MAX_AGE_MS = 30 * 60 * 1000L
        const val REFRESH_TIMEOUT_S = 8L
        const val LUMEN_PACKAGE = "dev.miraiijo.lumen"
    }
}
