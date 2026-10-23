package dev.miraiijo.lumentube.kids

import android.content.Context
import android.net.Uri
import dev.miraiijo.lumentube.link.MirazukiLink

/**
 * Mirazuki's current profile (mirazuki-link v3, content://dev.miraiijo.lumen.profiles/profiles): a kids
 * profile there opens MirazukiTube in its kids profile, with no way out from here (the profile is
 * changed in Mirazuki, behind Mirazuki's own parental code).
 */
object MirazukiProfile {
    private val URI: Uri = Uri.parse("content://${MirazukiLink.PROFILES_AUTHORITY}/profiles")

    /** True: Mirazuki's current profile is a kids one; false: an adult one; null: no trusted Mirazuki. Blocking. */
    fun currentIsKids(context: Context): Boolean? {
        if (!MirazukiLink.partner(context, MirazukiLink.MIRAZUKI, MirazukiLink.PROFILES_AUTHORITY)) return null
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
