package wonton.abp.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
            )
        }.sortedWith(
            // Apps with install permission first, then by label (case-insensitive).
            compareByDescending<AppInfo> { it.requestsInstallPermission }
                .thenBy { it.label.lowercase() }
        )
    }
}
