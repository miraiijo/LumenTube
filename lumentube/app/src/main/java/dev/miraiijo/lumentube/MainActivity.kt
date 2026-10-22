package dev.miraiijo.lumentube

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import dev.miraiijo.lumentube.ui.Appearance
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
        setContent {
            val settings = vm.state.collectAsState().value.settings
            LumenTubeTheme(Appearance.entries.getOrElse(settings.appearance) { Appearance.AUTO }, uiScale = settings.uiScale) { Root(vm) }
        }
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

    /**
     * Gamepad (DualSense, Xbox…): its buttons become the keys the app already understands. Only
     * events from a gamepad are touched, never the remote's. Android's own fallbacks (A → OK,
     * B / Y → Back, X / Start → OK) are replaced by this mapping:
     *  A = OK · B = Back · X = play / pause (and the small window back full screen) ·
     *  Y / Start = Menu (a card's menu, the player's settings) · Select = search ·
     *  L1 / R1 = −10 s / +10 s · L2 / R2 = previous / next chapter (±60 s without chapters).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = GAMEPAD_KEYS[event.keyCode]
        if (mapped == null || !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return super.dispatchKeyEvent(event)
        val copy = KeyEvent(
            event.downTime, event.eventTime, event.action, mapped, event.repeatCount, event.metaState,
            event.deviceId, event.scanCode, event.flags, event.source,
        )
        return super.dispatchKeyEvent(copy)
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
        val GAMEPAD_KEYS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        const val MAX_START_S = 24 * 3600L
    }
}
