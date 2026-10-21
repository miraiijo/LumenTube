package dev.miraiijo.lumentube.player

import android.content.Context
import dev.miraiijo.lumentube.Settings

/** LumenTube's own preferences (not SmartTube's): player choices and the app's switches. */
class PlayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    /** Held quality (1080 = 1080p), 0 = Auto. */
    var quality: Int
        get() = prefs.getInt(KEY_QUALITY, 0)
        set(value) = prefs.edit().putInt(KEY_QUALITY, value).apply()

    /** Name of the last subtitle track chosen ("Français"), null = off. Used when the video has it. */
    var subtitles: String?
        get() = prefs.getString(KEY_SUBTITLES, null)
        set(value) = prefs.edit().putString(KEY_SUBTITLES, value).apply()

    var sponsorBlock: Boolean
        get() = prefs.getBoolean(KEY_SPONSORBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPONSORBLOCK, value).apply()

    /** At the end of a video, the next one starts after a short countdown. */
    var autoplay: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()

    /** YouTube's per-video loudness applied: no jump in volume from one video to the next. */
    var stableVolume: Boolean
        get() = prefs.getBoolean(KEY_STABLE_VOLUME, true)
        set(value) = prefs.edit().putBoolean(KEY_STABLE_VOLUME, value).apply()

    /** Community titles and thumbnails (DeArrow) instead of the clickbait ones. */
    var deArrow: Boolean
        get() = prefs.getBoolean(KEY_DEARROW, false)
        set(value) = prefs.edit().putBoolean(KEY_DEARROW, value).apply()

    /** Picture format: 0 = whole picture (fit), 1 = fills the screen (crops), 2 = stretched. */
    var pictureFormat: Int
        get() = prefs.getInt(KEY_PICTURE_FORMAT, 0)
        set(value) = prefs.edit().putInt(KEY_PICTURE_FORMAT, value).apply()

    fun settings() = Settings(sponsorBlock = sponsorBlock, autoplay = autoplay, stableVolume = stableVolume, deArrow = deArrow)

    private companion object {
        const val KEY_QUALITY = "quality"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_SPONSORBLOCK = "sponsorblock"
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_STABLE_VOLUME = "stable_volume"
        const val KEY_DEARROW = "dearrow"
        const val KEY_PICTURE_FORMAT = "picture_format"
    }
}
