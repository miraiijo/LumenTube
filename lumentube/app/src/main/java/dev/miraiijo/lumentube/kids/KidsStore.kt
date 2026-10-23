package dev.miraiijo.lumentube.kids

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A channel a parent allowed for the kids profile. */
@androidx.compose.runtime.Immutable
data class KidChannel(val id: String, val name: String, val avatar: String?)

/** What a parental code check gives. */
sealed interface PinResult {
    data object Ok : PinResult
    data object Wrong : PinResult
    data class Locked(val minutes: Int) : PinResult
}

/**
 * LumenTube's kids profile (one per box): no YouTube account, only the channels a parent allowed,
 * no search, no comments, no YouTube suggestions; leaving it asks for the parental code. Exists
 * once a code is set. [active] survives restarts: a kid closing the app comes back to the profile.
 * Only the code's salted hash is stored ([PinHash]); wrong codes are counted on disk, and after
 * [MAX_TRIES] the code is refused for [LOCK_MS] (the 10 000 codes can't be tried in a row).
 */
class KidsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("kids", Context.MODE_PRIVATE)

    val exists: Boolean get() = prefs.getString(KEY_PIN, null) != null

    var active: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVE, value).apply()

    /** Turned on because Lumen's current profile is a kids one (off again when Lumen's isn't). */
    var fromLumen: Boolean
        get() = prefs.getBoolean(KEY_FROM_LUMEN, false)
        set(value) = prefs.edit().putBoolean(KEY_FROM_LUMEN, value).apply()

    /** The account to come back to when the kids profile is left (opaque id, null: none). */
    var previousAccount: String?
        get() = prefs.getString(KEY_PREVIOUS, null)
        set(value) = prefs.edit().putString(KEY_PREVIOUS, value).apply()

    val channels: List<KidChannel>
        get() = runCatching {
            val array = JSONArray(prefs.getString(KEY_CHANNELS, "[]"))
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                KidChannel(id, o.optString("name").ifBlank { "Chaîne" }, o.optString("avatar").takeIf { it.startsWith("https://") })
            }
        }.getOrDefault(emptyList())

    fun isAllowed(channelId: String): Boolean = channels.any { it.id == channelId }

    fun allow(channel: KidChannel) {
        if (isAllowed(channel.id)) return
        save(channels + channel)
    }

    fun disallow(channelId: String) = save(channels.filterNot { it.id == channelId })

    private fun save(list: List<KidChannel>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar ?: "")) }
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply()
    }

    /** Sets (or replaces) the parental code: the profile exists from then on. Off the main thread. */
    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN, PinHash.hash(pin)).putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).apply()
    }

    /** Checks the parental code, counting wrong ones. Off the main thread. */
    fun verify(pin: String): PinResult {
        val now = System.currentTimeMillis()
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        // A clock set back never extends the lock beyond LOCK_MS.
        if (lockedUntil > now && lockedUntil - now <= LOCK_MS) return PinResult.Locked(minutesLeft(lockedUntil - now))
        val stored = prefs.getString(KEY_PIN, null) ?: return PinResult.Wrong
        if (PinHash.verify(pin, stored)) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).apply()
            return PinResult.Ok
        }
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        if (fails >= MAX_TRIES) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, now + LOCK_MS).apply()
            return PinResult.Locked(minutesLeft(LOCK_MS))
        }
        prefs.edit().putInt(KEY_FAILS, fails).apply()
        return PinResult.Wrong
    }

    /** Removes the kids profile (code, channels, state). */
    fun delete() = prefs.edit().clear().apply()

    private fun minutesLeft(ms: Long) = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)

    private companion object {
        const val KEY_PIN = "pin"
        const val KEY_ACTIVE = "active"
        const val KEY_FROM_LUMEN = "from_lumen"
        const val KEY_PREVIOUS = "previous_account"
        const val KEY_CHANNELS = "channels"
        const val KEY_FAILS = "fails"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val MAX_TRIES = 5
        const val LOCK_MS = 5 * 60_000L
    }
}
