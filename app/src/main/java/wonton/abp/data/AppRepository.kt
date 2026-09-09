package wonton.abp.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the list of installed applications and whether each one requests the
 * `REQUEST_INSTALL_PACKAGES` permission.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    suspend fun loadInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_PERMISSIONS
        val packages = pm.getInstalledPackages(flags)
        val marketHandlers = marketAppPackages()
        packages.mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            // Skip our own app.
            if (pkg.packageName == context.packageName) return@mapNotNull null

            val requested = pkg.requestedPermissions?.any {
                it == "android.permission.REQUEST_INSTALL_PACKAGES"
            } ?: false

            AppInfo(
                packageName = pkg.packageName,
                label = appInfo.loadLabel(pm).toString(),
                icon = runCatching { appInfo.loadIcon(pm) }.getOrNull(),
                requestsInstallPermission = requested,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isMarketApp = pkg.packageName in marketHandlers,
            )
        }.sortedWith(
            // Apps with install permission first, then by label (case-insensitive).
            compareByDescending<AppInfo> { it.requestsInstallPermission }
                .thenBy { it.label.lowercase() }
        )
    }

    /**
     * Packages with an exported activity that can handle a `market://details`
     * link, i.e. app stores. Resolved with one implicit-intent query instead of
     * walking every package's activity list.
     */
    private fun marketAppPackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=android"))
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}
