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
        // RGB_565: thumbnails are opaque JPEGs; half the bytes per picture (twice as many cards in
        // the same cache, half the texture uploads on the Freebox Player POP). Glide keeps ARGB for
        // pictures that have transparency.
        com.bumptech.glide.Glide.init(
            this,
            com.bumptech.glide.GlideBuilder()
                .setMemoryCache(com.bumptech.glide.load.engine.cache.LruResourceCache(GLIDE_MEMORY_BYTES))
                .setDefaultRequestOptions(com.bumptech.glide.request.RequestOptions().format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)),
        )
        FeedCache.attach(this)
        // Account changes made in LumenTube reach Lumen (content://dev.miraiijo.lumentube.feed/).
        dev.miraiijo.lumentube.data.YouTube.watchAccountChanges()
    }
}
