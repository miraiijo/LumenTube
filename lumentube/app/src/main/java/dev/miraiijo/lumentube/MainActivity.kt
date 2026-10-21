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
        setContent { LumenTubeTheme { Root(vm) } }
        if (savedInstanceState == null) handleLink(intent)
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
        if (!id.isNullOrBlank()) vm.playLink(id, startSeconds(uri.getQueryParameter("t")))
    }

    /** "90", "90s", "1m30s" or "1h2m3s" → seconds (0 when absent or unreadable). */
    private fun startSeconds(t: String?): Int {
        if (t.isNullOrBlank()) return 0
        t.toIntOrNull()?.let { return it.coerceAtLeast(0) }
        val parts = Regex("(\\d+)([hms])").findAll(t.lowercase()).toList()
        if (parts.isEmpty()) return 0
        return parts.sumOf { match ->
            val value = match.groupValues[1].toInt()
            when (match.groupValues[2]) {
                "h" -> value * 3600
                "m" -> value * 60
                else -> value
            }
        }
    }
}
