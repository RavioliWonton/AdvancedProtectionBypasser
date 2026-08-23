package wonton.abp.data

import android.graphics.drawable.Drawable

/**
 * A single installed application shown in the checkbox list.
 *
 * @param requestsInstallPermission true if the app declares
 *   `android.permission.REQUEST_INSTALL_PACKAGES` in its manifest; such apps are
 *   sorted to the top of the list.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val requestsInstallPermission: Boolean,
    val isSystem: Boolean,
)
