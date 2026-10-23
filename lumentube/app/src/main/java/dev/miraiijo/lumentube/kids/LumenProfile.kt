package dev.miraiijo.lumentube.kids

import android.content.Context
import android.net.Uri
import dev.miraiijo.lumentube.link.LumenLink

/**
 * Lumen's current profile (lumen-link v3, content://dev.miraiijo.lumen.profiles/profiles): a kids
 * profile there opens LumenTube in its kids profile, with no way out from here (the profile is
 * changed in Lumen, behind Lumen's own parental code).
 */
object LumenProfile {
    private val URI: Uri = Uri.parse("content://${LumenLink.PROFILES_AUTHORITY}/profiles")

    /** True: Lumen's current profile is a kids one; false: an adult one; null: no trusted Lumen. Blocking. */
    fun currentIsKids(context: Context): Boolean? {
        if (!LumenLink.partner(context, LumenLink.LUMEN, LumenLink.PROFILES_AUTHORITY)) return null
        return runCatching {
            context.contentResolver.query(URI, arrayOf("kids", "current"), null, null, null)?.use { cursor ->
                val kids = cursor.getColumnIndex("kids")
                val current = cursor.getColumnIndex("current")
                if (kids < 0 || current < 0) return@use null
                var found: Boolean? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(current) == "1") {
                        found = cursor.getString(kids) == "1"
                        break
                    }
                }
                found
            }
        }.getOrNull()
    }
}
