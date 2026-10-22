package dev.miraiijo.lumentube.feed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import dev.miraiijo.lumentube.data.YouTube
import dev.miraiijo.lumentube.link.LumenLink
import dev.miraiijo.lumentube.remote.RemoteControl
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Feed for the Lumen launcher (frozen contract):
 *  - query /continue: videos started and not finished;
 *  - query /subscriptions: latest videos of the subscriptions;
 *  - query /accounts: the accounts linked in LumenTube (opaque id, name, avatar, selected);
 *  - query /search?q=…: the first 20 videos YouTube finds (same columns as /continue);
 *  - call "select_account" (arg = account_id): switches account, its feed is refreshed.
 * Contract v2 (contract=2&account=<account_id> on /continue, /subscriptions and /search): that
 * account's data, whatever account LumenTube shows, without switching it; an unknown account (or
 * an id longer than 128 characters) gets an empty list. Without account: LumenTube's account.
 * Trust (lumen-link v3, see link/LumenLink.kt): only answers Lumen, the calling package must be
 * dev.miraiijo.lumen signed with the certificate in BuildConfig.CERT_LUMEN (gradle.properties).
 * Not a signature permission: the two apps have their own keys. LumenTube also works without Lumen.
 * Each account has its own cache (FeedCache); older than 30 min and signed in: refreshed first, 8 s
 * at most, else the old cache is served. Signed out: empty. No e-mail, token or cookie, ever.
 */
class FeedProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(FeedCache.AUTHORITY, FeedCache.CONTINUE, CODE_CONTINUE)
        addURI(FeedCache.AUTHORITY, FeedCache.SUBSCRIPTIONS, CODE_SUBSCRIPTIONS)
        addURI(FeedCache.AUTHORITY, FeedCache.ACCOUNTS, CODE_ACCOUNTS)
        addURI(FeedCache.AUTHORITY, SEARCH, CODE_SEARCH)
    }

    /** Refreshes run here, never on the caller's (or the main) thread. */
    private val worker = Executors.newSingleThreadExecutor()

    /** Searches: their own threads, never stuck behind a feed refresh (two at once at most). */
    private val searches = Executors.newFixedThreadPool(2)

    override fun onCreate(): Boolean {
        context?.let(FeedCache::attach)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context ?: throw SecurityException("Not ready")
        // Who asks comes first: anything else is refused before the URI is even looked at.
        if (!isLumen(ctx)) throw SecurityException("This feed is only readable by the Lumen launcher")
        val code = matcher.match(uri)
        if (code == UriMatcher.NO_MATCH) throw IllegalArgumentException("Unknown feed: $uri")
        ready(ctx)
        // Contract v2: an account given (contract=2 is accepted but changes nothing else).
        val asked = uri.getQueryParameter(PARAM_ACCOUNT)
        val account = when {
            asked == null -> YouTube.homeAccountId()
            asked.length > MAX_ACCOUNT_ID || !YouTube.isKnownAccount(asked) -> null
            else -> asked
        }
        val cursor = when (code) {
            CODE_ACCOUNTS -> accounts()
            CODE_SEARCH -> search(uri.getQueryParameter("q"), account, asked != null)
            else -> videos(if (code == CODE_CONTINUE) FeedCache.CONTINUE else FeedCache.SUBSCRIPTIONS, account)
        }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    /** One row per linked account. Only the opaque id, the display name, the avatar and "selected". */
    private fun accounts(): Cursor {
        val cursor = MatrixCursor(FeedCache.ACCOUNT_COLUMNS)
        YouTube.feedAccounts().forEach { account ->
            cursor.addRow(arrayOf<Any?>(account.id, account.name, account.avatarUrl, if (account.selected) "1" else "0"))
        }
        return cursor
    }

    /** [accountId]: whose list (null: signed out, or an unknown account asked for: empty). */
    private fun videos(list: String, accountId: String?): Cursor {
        val cursor = MatrixCursor(FeedCache.COLUMNS)
        if (accountId == null) return cursor
        var (updatedMs, items) = FeedCache.load(list, accountId)
        if (System.currentTimeMillis() - updatedMs > MAX_AGE_MS) {
            // Fetched as that account (the engine switches back right after); LumenTube on screen:
            // only if it's the engine's account, else the cache is served as it is.
            val refresh = worker.submit<Boolean> { YouTube.asAccount(accountId, allowSwitch = RemoteControl.accountSwitchAllowed) { YouTube.refreshFeed(list) } ?: false }
            val refreshed = runCatching { refresh.get(REFRESH_TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
            // Timed out: the refresh goes on in the background and notifies when it's written.
            if (refreshed) items = FeedCache.load(list, accountId).second
        }
        items.take(FeedCache.MAX_ROWS).forEach { cursor.addRow(it.toRow()) }
        return cursor
    }

    /**
     * /search?q=…: YouTube's results for q (cut at 200 characters), videos only (no channel,
     * playlist or Short), 20 at most. Empty q, error, or no answer within 8 s: empty cursor.
     */
    private fun search(q: String?, accountId: String?, accountAsked: Boolean): Cursor {
        val cursor = MatrixCursor(FeedCache.COLUMNS)
        // Longer than 200 characters: cut at 200 (nothing more is sent to YouTube).
        val text = q?.trim().orEmpty().take(MAX_QUERY).trim()
        if (text.isEmpty()) return cursor
        // An unknown account asked for: empty, never another account's results.
        if (accountAsked && accountId == null) return cursor
        val request = searches.submit<List<FeedCache.Item>> {
            val items = if (accountId == null) {
                YouTube.searchItems(text) // signed out
            } else {
                // As that account. LumenTube on screen on another account (no switch then): an account
                // asked for gets nothing rather than another account's results; none asked: its own.
                YouTube.asAccount(accountId, allowSwitch = RemoteControl.accountSwitchAllowed) { YouTube.searchItems(text) }
                    ?: if (accountAsked || YouTube.playingAsOther) emptyList() else YouTube.searchItems(text)
            }
            FeedCache.itemsFrom(items, SEARCH)
        }
        val items = runCatching { request.get(REFRESH_TIMEOUT_S, TimeUnit.SECONDS) }
            .onFailure { request.cancel(true) }
            .getOrDefault(emptyList())
        items.forEach { cursor.addRow(it.toRow()) }
        return cursor
    }

    /**
     * call(content://dev.miraiijo.lumentube.feed/, "select_account", account_id): selects that
     * account, marks its feed for refresh (its last content stays for an instant display) and
     * refreshes /continue and /subscriptions on the worker. Bundle "ok" = false for an unknown id;
     * null for an unknown method.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: throw SecurityException("Not ready")
        if (!isLumen(ctx)) throw SecurityException("This feed is only readable by the Lumen launcher")
        if (method != METHOD_SELECT_ACCOUNT) return null
        ready(ctx)
        val selected = arg != null && YouTube.selectFeedAccount(arg)
        if (selected) {
            FeedCache.markStale(arg!!)
            worker.execute {
                // As that account (during a Lumen launch played as another one, the engine isn't on it).
                YouTube.asAccount(arg, allowSwitch = RemoteControl.accountSwitchAllowed) {
                    YouTube.refreshFeed(FeedCache.CONTINUE)
                    YouTube.refreshFeed(FeedCache.SUBSCRIPTIONS)
                }
            }
        }
        return Bundle().apply { putBoolean("ok", selected) }
    }

    /** Providers start before Application.onCreate: the engine's prefs (accounts) must exist first. */
    private fun ready(ctx: android.content.Context) {
        GlobalPreferences.instance(ctx.applicationContext)
        FeedCache.attach(ctx)
        YouTube.watchAccountChanges()
        // A Lumen launch cut short (process killed while playing as another account): back home.
        YouTube.restoreHome()
    }

    /**
     * The caller is Lumen (lumen-link v3): its package name, given by the system and not by the
     * caller, and its signing certificate, the one this build expects (BuildConfig.CERT_LUMEN). Any
     * other app, Lumen signed with another key, or a build without that certificate, is refused.
     */
    private fun isLumen(ctx: android.content.Context): Boolean =
        callingPackage == LumenLink.LUMEN && runCatching { LumenLink.callerIsOneOf(ctx, LumenLink.LUMEN) }.getOrDefault(false)

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_CONTINUE, CODE_SUBSCRIPTIONS -> "vnd.android.cursor.dir/vnd.lumentube.video"
        CODE_ACCOUNTS -> "vnd.android.cursor.dir/vnd.lumentube.account"
        CODE_SEARCH -> "vnd.android.cursor.dir/vnd.lumentube.video"
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
        const val CODE_ACCOUNTS = 3
        const val CODE_SEARCH = 4
        const val SEARCH = "search"
        const val MAX_QUERY = 200
        const val MAX_AGE_MS = 30 * 60 * 1000L
        const val REFRESH_TIMEOUT_S = 8L
        const val METHOD_SELECT_ACCOUNT = "select_account"
        const val PARAM_ACCOUNT = "account"
        const val MAX_ACCOUNT_ID = 128
    }
}
