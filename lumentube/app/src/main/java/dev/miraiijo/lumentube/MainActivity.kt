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

    /** youtube.com/watch?v=… or youtu.be/… from another app (Lumen, a browser…). */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val id = uri.getQueryParameter("v") ?: uri.takeIf { it.host == "youtu.be" }?.lastPathSegment
        if (!id.isNullOrBlank()) vm.playLink(id)
    }
}
