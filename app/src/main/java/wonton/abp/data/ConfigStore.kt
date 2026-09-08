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

    /** The bound Xposed framework's display name (e.g. "LSPosed"), or null. */
    fun getFrameworkName(): String? =
        runCatching { service?.frameworkName }.getOrNull()

    /** The bound Xposed framework's version string, or null. */
    fun getFrameworkVersion(): String? =
        runCatching { service?.frameworkVersion }.getOrNull()

    /**
     * Number of processes currently hooked by this module (a live "hook count").
     * Requires framework service API 102; returns null when unsupported/unavailable.
     */
    fun getHookedTargetCount(): Int? =
        runCatching { service?.runningTargets?.size }.getOrNull()

    fun getSelectedPackages(): Set<String> =
        prefs?.getStringSet(Prefs.KEY_SELECTED, emptySet())?.toSet() ?: emptySet()

    fun getInstallerPackage(): String =
        prefs?.getString(Prefs.KEY_INSTALLER, Prefs.DEFAULT_INSTALLER)
            ?: Prefs.DEFAULT_INSTALLER

    fun getBypassEcm(): Boolean =
        prefs?.getBoolean(Prefs.KEY_BYPASS_ECM, Prefs.DEFAULT_BYPASS) ?: Prefs.DEFAULT_BYPASS

    fun getBypassUserRestriction(): Boolean =
        prefs?.getBoolean(Prefs.KEY_BYPASS_USER_RESTRICTION, Prefs.DEFAULT_BYPASS)
            ?: Prefs.DEFAULT_BYPASS

    fun getHijackExplicit(): Boolean =
        prefs?.getBoolean(Prefs.KEY_HIJACK_EXPLICIT, Prefs.DEFAULT_HIJACK_EXPLICIT)
            ?: Prefs.DEFAULT_HIJACK_EXPLICIT

    fun setSelectedPackages(packages: Set<String>) {
        prefs?.edit()?.putStringSet(Prefs.KEY_SELECTED, packages)?.apply()
    }

    fun setInstallerPackage(pkg: String) {
        prefs?.edit()?.putString(Prefs.KEY_INSTALLER, pkg)?.apply()
    }

    fun setBypassEcm(enabled: Boolean) {
        prefs?.edit()?.putBoolean(Prefs.KEY_BYPASS_ECM, enabled)?.apply()
    }

    fun setBypassUserRestriction(enabled: Boolean) {
        prefs?.edit()?.putBoolean(Prefs.KEY_BYPASS_USER_RESTRICTION, enabled)?.apply()
    }

    fun setHijackExplicit(enabled: Boolean) {
        prefs?.edit()?.putBoolean(Prefs.KEY_HIJACK_EXPLICIT, enabled)?.apply()
    }
}
