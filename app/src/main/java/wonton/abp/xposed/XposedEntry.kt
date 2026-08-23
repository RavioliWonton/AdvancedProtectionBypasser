package wonton.abp.xposed

import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import wonton.abp.common.Prefs

/**
 * Xposed module entry point (libxposed API 102).
 *
 * The module is scoped to `system` (system_server) only. All interception
 * happens centrally in system_server, differentiated by the *calling*
 * (source) applicationId:
 *  1. Forces install-permission checks to report "granted" for selected apps
 *     by hooking the in-system resolvers (`AppOpsService` for
 *     `OP_REQUEST_INSTALL_PACKAGES` and `PermissionManagerService` for
 *     `REQUEST_INSTALL_PACKAGES`), keyed on the caller's package/uid.
 *  2. Rewrites `Intent.ACTION_INSTALL_PACKAGE` intents launched by a selected
 *     app to target a configured installer package (default
 *     `moe.shizuku.installer`) via `setPackage(...)`. This is done in the
 *     `ActivityManagerService`/`ActivityTaskManagerService` Binder
 *     `startActivity` path, keyed on the `callingPackage` argument.
 *
 * The selection set and installer package are read from remote preferences that
 * the UI app writes through the libxposed service.
 */
class XposedEntry : XposedModule() {

    /** Cached system_server PackageManager for uid -> package resolution. */
    @Volatile
    private var cachedPm: PackageManager? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: ${param.processName}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "onSystemServerStarting")
        val cl = param.classLoader
        hookInstallPermission(cl)
        hookActivityStart(cl)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        // Interception is centralized in system_server (see onSystemServerStarting).
        // No per-app hooks are installed here.
    }

    // ---------------------------------------------------------------------
    // Preferences
    // ---------------------------------------------------------------------

    private fun isSelected(pkg: String): Boolean = try {
        val set = getRemotePreferences(Prefs.GROUP)
            .getStringSet(Prefs.KEY_SELECTED, emptySet()) ?: emptySet()
        pkg in set
    } catch (t: Throwable) {
        log(Log.WARN, TAG, "Failed to read selection for $pkg", t)
        false
    }

    private fun installerPackage(): String = try {
        getRemotePreferences(Prefs.GROUP)
            .getString(Prefs.KEY_INSTALLER, Prefs.DEFAULT_INSTALLER)
            ?: Prefs.DEFAULT_INSTALLER
    } catch (t: Throwable) {
        Prefs.DEFAULT_INSTALLER
    }

    // ---------------------------------------------------------------------
    // Hook 1 (system_server): install-permission checks -> granted for a
    // selected app.
    //
    // These resolvers all run inside system_server. We differentiate by the
    // *caller*: AppOps checks carry the package name and/or uid, the permission
    // check carries the package name. We only force a positive result when the
    // op/permission is REQUEST_INSTALL_PACKAGES *and* the caller is selected.
    // ---------------------------------------------------------------------

    private fun hookInstallPermission(cl: ClassLoader) {
        hookAppOpsService(cl)
        hookPermissionManagerService(cl)
    }

    /**
     * Hooks `com.android.server.appop.AppOpsService` operation checks so that
     * `OP_REQUEST_INSTALL_PACKAGES` reports `MODE_ALLOWED` for selected callers.
     *
     * The public methods (`checkOperation`, `noteOperation`,
     * `checkOperationRaw`, `noteProxyOperation`, ...) share a leading
     * `(int code, int uid, String packageName, ...)` shape, so we locate the
     * op-code int and the package/uid positionally.
     */
    private fun hookAppOpsService(cl: ClassLoader) {
        val clazz = runCatching {
            cl.loadClass("com.android.server.appop.AppOpsService")
        }.getOrElse {
            // Older location.
            runCatching { cl.loadClass("com.android.server.AppOpsService") }.getOrNull()
        } ?: run {
            log(Log.WARN, TAG, "AppOpsService class not found")
            return
        }

        var hooked = 0
        for (m in clazz.declaredMethods) {
            if (!APP_OPS_METHODS.contains(m.name)) continue
            val params = m.parameterTypes
            if (params.isEmpty() || params[0] != Int::class.javaPrimitiveType) continue

            // uid is the first int after the op code; package name is the first
            // String argument.
            val uidIndex = (1 until params.size).firstOrNull {
                params[it] == Int::class.javaPrimitiveType
            } ?: -1
            val pkgIndex = params.indexOfFirst { it == String::class.java }
            if (uidIndex < 0 && pkgIndex < 0) continue

            runCatching {
                hook(m).intercept { chain ->
                    val op = chain.getArg(0) as? Int
                    if (op == OP_REQUEST_INSTALL_PACKAGES) {
                        val pkg = if (pkgIndex >= 0) chain.getArg(pkgIndex) as? String else null
                        val uid = if (uidIndex >= 0) chain.getArg(uidIndex) as? Int else null
                        if (isSelectedCaller(pkg, uid)) {
                            return@intercept AppOpsManager.MODE_ALLOWED
                        }
                    }
                    chain.proceed()
                }
                hooked++
            }.onFailure { log(Log.WARN, TAG, "AppOpsService.${m.name} hook failed", it) }
        }
        log(Log.INFO, TAG, "Hooked AppOpsService op checks ($hooked methods)")
    }

    /**
     * Hooks the permission resolver so that `REQUEST_INSTALL_PACKAGES` reports
     * granted for selected callers. Targets
     * `com.android.server.pm.permission.PermissionManagerServiceImpl`
     * (and older variants) `checkPermission`/`checkUidPermission`.
     */
    private fun hookPermissionManagerService(cl: ClassLoader) {
        val candidates = listOf(
            "com.android.server.pm.permission.PermissionManagerServiceImpl",
            "com.android.server.pm.permission.PermissionManagerService",
        )
        val clazz = candidates.firstNotNullOfOrNull { name ->
            runCatching { cl.loadClass(name) }.getOrNull()
        } ?: run {
            log(Log.WARN, TAG, "PermissionManagerService class not found")
            return
        }

        var hooked = 0
        for (m in clazz.declaredMethods) {
            if (m.name != "checkPermission" && m.name != "checkUidPermission") continue
            val params = m.parameterTypes
            // First arg is the permission name String on both signatures.
            if (params.isEmpty() || params[0] != String::class.java) continue

            val pkgIndex = (1 until params.size).firstOrNull { params[it] == String::class.java } ?: -1
            val uidIndex = params.indexOfFirst { it == Int::class.javaPrimitiveType }

            runCatching {
                hook(m).intercept { chain ->
                    if (chain.getArg(0) == PERM_REQUEST_INSTALL) {
                        val pkg = if (pkgIndex >= 0) chain.getArg(pkgIndex) as? String else null
                        val uid = if (uidIndex >= 0) chain.getArg(uidIndex) as? Int else null
                        if (isSelectedCaller(pkg, uid)) {
                            return@intercept PackageManager.PERMISSION_GRANTED
                        }
                    }
                    chain.proceed()
                }
                hooked++
                log(Log.INFO, TAG, "Hooked ${clazz.simpleName}.${m.name}")
            }.onFailure { log(Log.WARN, TAG, "${clazz.simpleName}.${m.name} hook failed", it) }
        }
        if (hooked == 0) {
            log(Log.WARN, TAG, "No PermissionManagerService methods hooked")
        }
    }

    /**
     * Determines whether the caller (identified by package name and/or uid) is a
     * user-selected app. When only a uid is available it is resolved to the
     * package name(s) sharing that uid via the system context's PackageManager.
     */
    private fun isSelectedCaller(pkg: String?, uid: Int?): Boolean {
        if (pkg != null) {
            if (pkg == OWN_PACKAGE) return false
            if (isSelected(pkg)) return true
            // A concrete non-selected package: don't fall back to uid resolution.
            return false
        }
        if (uid != null) {
            val names = packagesForUid(uid) ?: return false
            return names.any { it != OWN_PACKAGE && isSelected(it) }
        }
        return false
    }

    private fun packagesForUid(uid: Int): Array<String>? = try {
        systemPackageManager()?.getPackagesForUid(uid)
    } catch (t: Throwable) {
        null
    }

    private fun systemPackageManager(): PackageManager? {
        cachedPm?.let { return it }
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val systemThread = atClass.getMethod("currentActivityThread").invoke(null)
            val ctx = atClass.getMethod("getSystemContext")
                .invoke(systemThread) as android.content.Context
            ctx.packageManager.also { cachedPm = it }
        }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // Hook 2 (system_server): ACTION_INSTALL_PACKAGE intents launched by a
    // selected app -> setPackage(installer).
    //
    // Instead of hooking every target app's ContextWrapper.startActivity, we
    // intercept centrally in system_server on the IActivityManager Binder path.
    // Every startActivity Binder call carries the *calling* package name (the
    // source applicationId) as a String argument, plus one or more Intent
    // arguments. We read the caller, decide via isSelected(...), and rewrite
    // the Intent(s) when the caller is a selected app.
    // ---------------------------------------------------------------------

    private fun hookActivityStart(cl: ClassLoader) {
        // On modern Android the IActivityManager.startActivity* methods live on
        // ActivityTaskManagerService; on older releases on ActivityManagerService.
        val hostClasses = listOf(
            "com.android.server.wm.ActivityTaskManagerService",
            "com.android.server.am.ActivityManagerService",
        )
        var hooked = 0
        for (name in hostClasses) {
            val clazz = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
            for (m in clazz.declaredMethods) {
                val mName = m.name
                if (mName != "startActivity" &&
                    mName != "startActivityAsUser" &&
                    mName != "startActivities" &&
                    mName != "startActivitiesAsUser"
                ) continue

                val params = m.parameterTypes
                // Locate the source applicationId (callingPackage) String arg and
                // the Intent / Intent[] arg positions from the signature.
                val callerIndex = findCallingPackageIndex(params)
                val intentIndex = params.indexOfFirst { it == Intent::class.java }
                val intentArrayIndex = params.indexOfFirst { it == Array<Intent>::class.java }
                if (callerIndex < 0 || (intentIndex < 0 && intentArrayIndex < 0)) continue

                runCatching {
                    hook(m).intercept { chain ->
                        try {
                            val caller = chain.getArg(callerIndex) as? String
                            if (caller != null && caller != OWN_PACKAGE && isSelected(caller)) {
                                val installer = installerPackage()
                                if (intentIndex >= 0) {
                                    (chain.getArg(intentIndex) as? Intent)
                                        ?.let { rewriteInstallIntent(it, installer, caller) }
                                }
                                if (intentArrayIndex >= 0) {
                                    (chain.getArg(intentArrayIndex) as? Array<*>)?.forEach { i ->
                                        (i as? Intent)?.let { rewriteInstallIntent(it, installer, caller) }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            log(Log.WARN, TAG, "startActivity intercept failed", t)
                        }
                        chain.proceed()
                    }
                    hooked++
                    log(Log.INFO, TAG, "Hooked $name.$mName (${params.size} args)")
                }.onFailure { log(Log.WARN, TAG, "Failed to hook $name.$mName", it) }
            }
        }
        if (hooked == 0) {
            log(Log.ERROR, TAG, "No AMS/ATMS startActivity methods were hooked")
        }
    }

    /**
     * Finds the index of the `callingPackage` String argument in an AMS/ATMS
     * `startActivity*` signature. These methods take an `IApplicationThread`
     * caller first, immediately followed by the `String callingPackage` (and,
     * on newer releases, `String callingFeatureId`). We therefore return the
     * first String parameter, which is the calling applicationId.
     */
    private fun findCallingPackageIndex(params: Array<Class<*>>): Int =
        params.indexOfFirst { it == String::class.java }

    private fun rewriteInstallIntent(intent: Intent, installer: String, caller: String) {
        if (intent.action != ACTION_INSTALL_PACKAGE) return
        if (installer.isBlank()) return
        // Don't override an explicit component / package already chosen.
        if (intent.`package` != null || intent.component != null) return
        intent.setPackage(installer)
        log(Log.INFO, TAG, "Redirected install intent from $caller to $installer")
    }

    companion object {
        private const val TAG = "ABP"
        private const val OWN_PACKAGE = "wonton.abp"

        private const val PERM_REQUEST_INSTALL = "android.permission.REQUEST_INSTALL_PACKAGES"

        // AppOpsManager.OP_REQUEST_INSTALL_PACKAGES is @hide; its stable value is 66.
        private const val OP_REQUEST_INSTALL_PACKAGES = 66
        private const val OPSTR_REQUEST_INSTALL_PACKAGES =
            "android:request_install_packages"

        private val APP_OPS_METHODS = setOf(
            "checkOpNoThrow",
            "noteOpNoThrow",
            "unsafeCheckOpNoThrow",
            "unsafeCheckOpRawNoThrow",
            "checkOp",
            "noteOp",
            "noteProxyOpNoThrow",
            "noteProxyOp",
        )

        @Suppress("DEPRECATION")
        private val ACTION_INSTALL_PACKAGE = Intent.ACTION_INSTALL_PACKAGE
    }
}
