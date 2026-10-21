package dev.miraiijo.lumentube

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.miraiijo.lumentube.ui.LumenTubeTheme
import dev.miraiijo.lumentube.ui.Root

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Warm-up, like SmartTube's splash: YouTube's player script and the signature engine load now
        // (in the background) instead of during the first video's start. Here rather than in the
        // Application: a feed query from Lumen starts the process without needing all that.
        com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().refreshCacheIfNeeded()
        setContent { LumenTubeTheme { Root(vm) } }
        if (savedInstanceState == null) handleLink(intent)
    }

    // "Regarder sur la TV": listened to only while LumenTube is on screen (Android 10 doesn't let a
    // background app bring itself up anyway).
    override fun onStart() {
        super.onStart()
        vm.onForeground(true)
    }

    override fun onStop() {
        vm.onForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /**
     * youtube.com/watch?v=…&t=… or youtu.be/…?t=… from another app (Lumen, a browser…): played from
     * t when given (Lumen's "continue" passes the saved position).
     */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val id = uri.getQueryParameter("v") ?: uri.takeIf { it.host == "youtu.be" }?.lastPathSegment
        // Only a real video id (11 characters): the engine puts it as is in its signed-in requests.
        if (id != null && VIDEO_ID.matches(id)) vm.playLink(id, startSeconds(uri.getQueryParameter("t")))
    }

    /** "90", "90s", "1m30s" or "1h2m3s" → seconds (0 when absent or unreadable). */
    private fun startSeconds(t: String?): Int {
        if (t.isNullOrBlank() || t.length > 16) return 0
        val total = t.toLongOrNull() ?: Regex("(\\d{1,9})([hms])").findAll(t.lowercase()).sumOf { match ->
            val value = match.groupValues[1].toLong()
            when (match.groupValues[2]) {
                "h" -> value * 3600
                "m" -> value * 60
                else -> value
            }
        }
        // Capped at a day: a huge or malformed value never overflows nor crashes.
        return total.coerceIn(0L, MAX_START_S).toInt()
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        const val MAX_START_S = 24 * 3600L
    }
}
