package wonton.abp.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import wonton.abp.common.Prefs

/**
 * Reads and writes the module configuration (selected packages + installer
 * package) through the Xposed framework's remote preferences so that the module
 * running inside hooked processes can see the same values.
 *
 * When the module is not active (no [XposedService] bound) all writes are no-ops
 * and reads return defaults; the UI surfaces this state separately.
 */
class ConfigStore(private val service: XposedService?) {

    private val prefs: SharedPreferences? =
        runCatching { service?.getRemotePreferences(Prefs.GROUP) }.getOrNull()

    val isAvailable: Boolean get() = prefs != null

    fun getSelectedPackages(): Set<String> =
        prefs?.getStringSet(Prefs.KEY_SELECTED, emptySet())?.toSet() ?: emptySet()

    fun getInstallerPackage(): String =
        prefs?.getString(Prefs.KEY_INSTALLER, Prefs.DEFAULT_INSTALLER)
            ?: Prefs.DEFAULT_INSTALLER

    fun setSelectedPackages(packages: Set<String>) {
        prefs?.edit()?.putStringSet(Prefs.KEY_SELECTED, packages)?.apply()
    }

    fun setInstallerPackage(pkg: String) {
        prefs?.edit()?.putString(Prefs.KEY_INSTALLER, pkg)?.apply()
    }
}
