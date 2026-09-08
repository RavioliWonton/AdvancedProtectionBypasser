package wonton.abp.common

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight

/**
 * Google Play services presence check.
 *
 * The Advanced Protection install path this module hooks is provided by Google
 * Play services, so every hook is gated on it (see `XposedEntry`). The official
 * `play-services-base` check is used first because it also verifies the package
 * signature; it is called from system_server as well, where loading the Play
 * services classes must never be able to break the process, so a plain package
 * lookup is used as a fallback whenever the library call fails for any reason.
 */
object Gms {

    /** The Google Play services package name. */
    const val PACKAGE = "com.google.android.gms"

    fun isAvailable(context: Context?): Boolean {
        if (context == null) return false
        val viaLibrary = runCatching {
            GoogleApiAvailabilityLight.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrNull()
        return viaLibrary ?: isPackageInstalled(context)
    }

    private fun isPackageInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)
}
