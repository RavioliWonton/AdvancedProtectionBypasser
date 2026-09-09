package wonton.abp.common

import android.content.Context
import android.os.UserManager

/**
 * Advanced Protection (Android 16+) detection.
 *
 * Advanced Protection blocks sideloading by setting the
 * `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` user restriction, so the presence
 * of that restriction is what the status page reports.
 *
 * Caveat: the module's own "relax unknown-sources install restriction" toggle
 * makes `UserManagerService.hasUserRestriction` return `false` user-wide while
 * it is active, so a `false` reading may be the module's override rather than
 * the real state. Callers can pair this with that toggle to say so.
 */
object AdvancedProtection {

    /** `UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES[_GLOBALLY]`, @hide-stable keys. */
    private val RESTRICTIONS = listOf(
        "no_install_unknown_sources_globally",
        "no_install_unknown_sources",
    )

    /** Whether any of the sideloading restrictions Advanced Protection sets is present. */
    fun isRestrictionPresent(context: Context): Boolean {
        val userManager = context.getSystemService(UserManager::class.java) ?: return false
        return RESTRICTIONS.any { key ->
            runCatching { userManager.hasUserRestriction(key) }.getOrDefault(false)
        }
    }
}
