package dev.miraiijo.lumentube

import android.app.Application
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager

class LumenTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // SmartTube's engine keeps the account tokens and the language in these prefs: they must
        // exist before any YouTube call (SmartTube does it at the very start too).
        GlobalPreferences.instance(this)
        // Warm-up, like SmartTube's splash: YouTube's player script and the signature engine load now
        // (in the background) instead of during the first video's start.
        YouTubeServiceManager.instance().refreshCacheIfNeeded()
    }
}
