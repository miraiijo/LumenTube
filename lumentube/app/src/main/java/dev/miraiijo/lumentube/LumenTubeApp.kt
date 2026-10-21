package dev.miraiijo.lumentube

import android.app.Application
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import dev.miraiijo.lumentube.feed.FeedCache

class LumenTubeApp : Application() {
    private companion object {
        const val GLIDE_MEMORY_BYTES = 32L shl 20
    }

    override fun onCreate() {
        super.onCreate()
        // SmartTube's engine keeps the account tokens and the language in these prefs: they must
        // exist before any YouTube call (SmartTube does it at the very start too).
        GlobalPreferences.instance(this)
        // Thumbnails kept decoded in memory (~6 screens of cards instead of Glide's ~2): scrolling
        // back doesn't decode them again from disk. Set before any picture is loaded.
        com.bumptech.glide.Glide.init(
            this,
            com.bumptech.glide.GlideBuilder().setMemoryCache(com.bumptech.glide.load.engine.cache.LruResourceCache(GLIDE_MEMORY_BYTES)),
        )
        FeedCache.attach(this)
        // Account changes made in LumenTube reach Lumen (content://dev.miraiijo.lumentube.feed/).
        dev.miraiijo.lumentube.data.YouTube.watchAccountChanges()
    }
}
