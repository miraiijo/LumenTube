package dev.miraiijo.lumentube.link

import android.content.Context
import android.os.Binder
import dev.miraiijo.lumentube.BuildConfig
import dev.miraiijo.lumentube.update.Updater

/**
 * lumen-link v3: how the Lumen apps find each other. Same file in Lumen, LumenTube and LumenIPTV
 * (only the package lines differ): fix it in the three.
 *
 * Each app works alone. A partner is used only when it is installed, when the provider belongs to
 * it, and when it is signed with the certificate this build expects. The certificates (SHA-256 of
 * the signing certificate, public, not a secret) come from gradle.properties
 * (lumen.cert.lumen / lumen.cert.lumentube / lumen.cert.lumeniptv), written by `lumen-keys init`.
 * Empty = linking off for that app (safe default: nothing is read, nothing is answered).
 *
 * Providers (read-only, every query checks the caller):
 *  - Lumen      content://dev.miraiijo.lumen.profiles/profiles
 *               profile_id, name, color (ARGB long), avatar, kids ("1"/"0"), current ("1"/"0")
 *  - LumenTube  content://dev.miraiijo.lumentube.feed/…  (feed contract v2, see FeedProvider)
 *  - LumenIPTV  content://dev.miraiijo.lumeniptv.rows/channels?profile=<profile_id>
 *               channel_id, name, number, logo_url (https or null), kind ("recent"/"favorite"),
 *               now_title, now_start_ms, now_end_ms — never a stream address, a host or an account
 * Each provider notifies its root URI when its data changes. Unknown columns are ignored and
 * missing ones read as empty, so a newer partner never breaks an older one.
 */
object LumenLink {
    const val VERSION = 3

    const val LUMEN = "dev.miraiijo.lumen"
    const val LUMENTUBE = "dev.miraiijo.lumentube"
    const val LUMENIPTV = "dev.miraiijo.lumeniptv"

    const val PROFILES_AUTHORITY = "$LUMEN.profiles"
    const val TUBE_AUTHORITY = "$LUMENTUBE.feed"
    const val IPTV_AUTHORITY = "$LUMENIPTV.rows"

    /** Opens a channel in LumenIPTV: data lumeniptv://channel/<channel_id>, this extra = the Lumen profile. */
    const val IPTV_SCHEME = "lumeniptv"
    const val EXTRA_PROFILE = "dev.miraiijo.link.extra.PROFILE_ID"

    /** Expected signing certificate of [pkg] (empty: linking with it is off). */
    fun expectedCert(pkg: String): String = when (pkg) {
        LUMEN -> BuildConfig.CERT_LUMEN
        LUMENTUBE -> BuildConfig.CERT_LUMENTUBE
        LUMENIPTV -> BuildConfig.CERT_LUMENIPTV
        else -> ""
    }.lowercase().replace(":", "")

    /** [pkg] is installed and signed with the expected certificate. */
    fun isTrusted(context: Context, pkg: String): Boolean {
        val expected = expectedCert(pkg)
        return expected.isNotEmpty() && expected in Updater.signers(context, pkg)
    }

    /** [authority] is served by [pkg] itself (another app declaring the same name is ignored). */
    fun ownsProvider(context: Context, authority: String, pkg: String): Boolean =
        runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName == pkg }.getOrDefault(false)

    /** Partner ready to be read: installed, its own provider, trusted certificate. */
    fun partner(context: Context, pkg: String, authority: String): Boolean =
        ownsProvider(context, authority, pkg) && isTrusted(context, pkg)

    /** Inside a provider: the app calling is one of [allowed], with its expected certificate. */
    fun callerIsOneOf(context: Context, vararg allowed: String): Boolean {
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        return packages.any { it in allowed && isTrusted(context, it) }
    }
}
