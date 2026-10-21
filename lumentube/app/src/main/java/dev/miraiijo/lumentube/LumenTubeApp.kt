package dev.miraiijo.lumentube

import android.app.Application
import com.liskovsoft.sharedutils.prefs.GlobalPreferences

class LumenTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // SmartTube's engine keeps the account tokens and the language in these prefs: they must
        // exist before any YouTube call (SmartTube does it at the very start too).
        GlobalPreferences.instance(this)
    }
}
