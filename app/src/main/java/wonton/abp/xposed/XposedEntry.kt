package wonton.abp.xposed

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wonton.abp.common.ModuleLog
import wonton.abp.common.Prefs
import wonton.abp.install.ApkStageProvider
import wonton.abp.install.InstallHandoff

/**
 * Xposed module entry point (libxposed API 102).
 *
 * The module is scoped to `system` (system_server) only. All interception
 * happens centrally in system_server, differentiated by the *calling*
 * (source) applicationId:
 *  1. Forces install-permission checks to report "granted" for selected apps.
 *     `REQUEST_INSTALL_PACKAGES` is an appop-backed permission, so the decision
 *     is made in two places that we hook: the top-level Binder gate
 *     `PackageManagerService.canRequestPackageInstalls(...)` and the underlying
 *     `AppOpsService.checkOperation(...)` it (and `checkOpNoThrow`) funnel into,
 *     keyed on the caller's package/uid.
 *  2. Rewrites `Intent.ACTION_INSTALL_PACKAGE` intents launched by a selected
 *     app to target a configured installer package (default
 *     `io.github.vvb2060.packageinstaller`) via `setPackage(...)`. This is done
 *     in the `ActivityManagerService`/`ActivityTaskManagerService` Binder
 *     `startActivity` path, keyed on the `callingPackage` argument.
 *
 * The selection set and installer package are read from remote preferences that
 * the UI app writes through the libxposed service.
 */
class XposedEntry : XposedModule() {

    /** Cached system_server PackageManager for uid -> package resolution. */
    @Volatile
    private var cachedPm: PackageManager? = null

    /** Cached system_server context (ActivityThread.getSystemContext). */
    @Volatile
    private var cachedCtx: Context? = null

    /**
     * Scope for the asynchronous part of the session-install hand-off. It lives
     * exactly as long as system_server, so it is never cancelled.
     */
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: ${param.processName}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "=== onSystemServerStarting: ABP module active in system_server ===")
        registerProxyLogReceiver()
        val cl = param.classLoader
        hookInstallPermission(cl)
        hookActivityStart(cl)
        hookPackageInstallerSession(cl)
        log(Log.INFO, TAG, "=== onSystemServerStarting done: hooks installed ===")
    }

    /**
     * Receives log lines the app process forwards through [ModuleLog] and
     * re-logs them with the Xposed logger: LSPosed only captures logs written
     * from hooked processes, so the app's own logcat output never shows up
     * there. The receiver is guarded by a signature-level permission, so only
     * builds signed with our key can send to it.
     */
    private fun registerProxyLogReceiver() {
        val ctx = systemContext()
        if (ctx == null) {
            log(Log.WARN, TAG, "proxy log receiver not registered (no system context)")
            return
        }
        val filter = IntentFilter(ModuleLog.ACTION)
        runCatching {
            ctx.registerReceiver(proxyLogReceiver, filter, ModuleLog.PERMISSION, null)
        }.onFailure {
            log(Log.WARN, TAG, "proxy log receiver registration failed", it)
        }.onSuccess {
            log(Log.INFO, TAG, "proxy log receiver registered (${ModuleLog.ACTION})")
        }
    }

    private val proxyLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra(ModuleLog.EXTRA_LINE) ?: return
            log(Log.INFO, TAG, "[2A] $line")
        }
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

    /**
     * Whether the user has selected at least one app. Used to gate hooks that
     * operate on version/global signals with NO per-caller argument (e.g. the
     * `DISALLOW_INSTALL_UNKNOWN_SOURCES` user restriction). We only relax such
     * global gates when the user actually intends to bypass for some app.
     */
    private fun hasAnySelection(): Boolean = try {
        val set = getRemotePreferences(Prefs.GROUP)
            .getStringSet(Prefs.KEY_SELECTED, emptySet()) ?: emptySet()
        set.isNotEmpty()
    } catch (t: Throwable) {
        false
    }

    private fun installerPackage(): String = try {
        getRemotePreferences(Prefs.GROUP)
            .getString(Prefs.KEY_INSTALLER, Prefs.DEFAULT_INSTALLER)
            ?: Prefs.DEFAULT_INSTALLER
    } catch (t: Throwable) {
        Prefs.DEFAULT_INSTALLER
    }

    /**
     * Whether the user opted in to relaxing the Enhanced Confirmation Mode
     * restricted-setting gate (Android 15+). Off by default.
     */
    private fun bypassEcmEnabled(): Boolean = try {
        getRemotePreferences(Prefs.GROUP)
            .getBoolean(Prefs.KEY_BYPASS_ECM, Prefs.DEFAULT_BYPASS)
    } catch (t: Throwable) {
        Prefs.DEFAULT_BYPASS
    }

    /**
     * Whether the user opted in to relaxing the
     * DISALLOW_INSTALL_UNKNOWN_SOURCES[_GLOBALLY] user restriction. Off by
     * default. This is a user-wide relaxation (no per-app scope available).
     */
    private fun bypassUserRestrictionEnabled(): Boolean = try {
        getRemotePreferences(Prefs.GROUP)
            .getBoolean(Prefs.KEY_BYPASS_USER_RESTRICTION, Prefs.DEFAULT_BYPASS)
    } catch (t: Throwable) {
        Prefs.DEFAULT_BYPASS
    }

    /**
     * Whether the user opted in to hijacking install intents that already name
     * an explicit target activity/package. Off by default.
     */
    private fun hijackExplicitEnabled(): Boolean = try {
        getRemotePreferences(Prefs.GROUP)
            .getBoolean(Prefs.KEY_HIJACK_EXPLICIT, Prefs.DEFAULT_HIJACK_EXPLICIT)
    } catch (t: Throwable) {
        Prefs.DEFAULT_HIJACK_EXPLICIT
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
        // Order matters only for logging; all gates are independent.
        //
        // Coverage across Android versions (API 26 = Android 8.0 .. API 36 =
        // Android 16), all keyed on the *calling* app:
        //  - canRequestPackageInstalls: the high-level PackageManager Binder
        //    gate. Declared on PackageManagerService (API 26-30), moved to the
        //    abstract IPackageManagerBase (final 2-arg) with the real worker on
        //    ComputerEngine (API 31+). Covered by superclass-walking seeds.
        //  - AppOpsService.checkOperation(op 66): the appop backing the
        //    REQUEST_INSTALL_PACKAGES permission on EVERY version. Also counters
        //    Android 16 Advanced Protection which force-sets op 66 to
        //    MODE_ERRORED.
        //  - EnhancedConfirmationService.isRestricted: ECM gate introduced in
        //    Android 15 (API 35) and used by Android 16 to block "restricted
        //    settings" including granting install-from-unknown-sources.
        //  - UserManagerService.hasUserRestriction: the
        //    DISALLOW_INSTALL_UNKNOWN_SOURCES[_GLOBALLY] user restriction. This
        //    is the OTHER mechanism Android 16 Advanced Protection uses (a global
        //    user restriction, separate from the appop). Present since early
        //    versions for managed profiles / device-owner policies too.
        hookCanRequestPackageInstalls(cl)
        hookAppOpsService(cl)
        hookEnhancedConfirmation(cl)
        hookUserRestriction(cl)
    }

    /**
     * Hooks `canRequestPackageInstalls(...)` so it returns `true` for selected
     * callers.
     *
     * This is the highest-level, most reliable gate: apps that redirect users to
     * `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` (like Huawei AppGallery)
     * decide by calling `PackageManager.canRequestPackageInstalls()`, which is
     * this exact Binder method. Hooking here bypasses any OEM-specific internals
     * that may not funnel through the standard `AppOpsService.checkOperation`.
     *
     * The method lives on DIFFERENT classes depending on the Android version, so
     * a fixed class list scanned with `declaredMethods` is not enough:
     *  - Legacy: declared directly on `PackageManagerService`
     *    (`boolean canRequestPackageInstalls(String, int)`).
     *  - Android 13+ (incl. 14/15/16): the 2-arg Binder entry point is declared
     *    on the abstract base class `IPackageManagerBase` and is `final`, so the
     *    concrete Binder stub `PackageManagerService$IPackageManagerImpl` does
     *    NOT re-declare it — scanning only the concrete class finds nothing.
     *  - The real worker is `ComputerEngine.canRequestPackageInstalls(String,
     *    int callingUid, int userId, boolean throwIfPermNotDeclared)` (4 args).
     *
     * We therefore start from several seed classes and walk UP each superclass
     * chain, hooking every distinct `canRequestPackageInstalls` method that
     * returns `boolean` and takes a package-name `String`. Hooking both the
     * 2-arg Binder entry and the 4-arg worker is belt-and-suspenders and keeps
     * us version-independent. The package name is always the first `String`
     * argument in every known signature.
     */
    private fun hookCanRequestPackageInstalls(cl: ClassLoader) {
        val seeds = listOf(
            "com.android.server.pm.PackageManagerService\$IPackageManagerImpl",
            "com.android.server.pm.IPackageManagerImpl",
            "com.android.server.pm.IPackageManagerBase",
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.ComputerEngine",
        )
        val seen = HashSet<java.lang.reflect.Method>()
        var hooked = 0
        for (name in seeds) {
            var clazz: Class<*>? = runCatching { cl.loadClass(name) }.getOrNull()
            // Walk up the superclass chain so methods declared on a base class
            // (e.g. IPackageManagerBase) are found even when we seed from the
            // concrete Binder impl.
            while (clazz != null && clazz != Any::class.java) {
                val current = clazz
                for (m in current.declaredMethods) {
                    if (m.name != "canRequestPackageInstalls") continue
                    if (m.returnType != Boolean::class.javaPrimitiveType) continue
                    val params = m.parameterTypes
                    val pkgIndex = params.indexOfFirst { it == String::class.java }
                    if (pkgIndex < 0) continue
                    if (!seen.add(m)) continue

                    runCatching {
                        hook(m).intercept { chain ->
                            val pkg = chain.getArg(pkgIndex) as? String
                            if (pkg != null && pkg != OWN_PACKAGE && isSelected(pkg)) {
                                log(Log.INFO, TAG, "canRequestPackageInstalls($pkg) -> forced true")
                                return@intercept true
                            }
                            chain.proceed()
                        }
                        hooked++
                        log(Log.INFO, TAG, "Hooked ${current.name}.canRequestPackageInstalls(${params.size} args)")
                    }.onFailure {
                        log(Log.WARN, TAG, "${current.name}.canRequestPackageInstalls hook failed", it)
                    }
                }
                clazz = current.superclass
            }
        }
        if (hooked == 0) {
            log(Log.WARN, TAG, "No canRequestPackageInstalls method hooked")
        }
    }

    /**
     * Hooks `com.android.server.appop.AppOpsService` operation checks so that
     * `OP_REQUEST_INSTALL_PACKAGES` reports `MODE_ALLOWED` for selected callers.
     *
     * We only hook the server-side methods that RETURN AN INT MODE
     * (`checkOperation`, `checkOperationRaw`). This is exactly what
     * `PackageManager.canRequestPackageInstalls()` and
     * `AppOpsManager.checkOpNoThrow(...)` funnel into inside system_server.
     *
     * IMPORTANT: we deliberately do NOT hook `noteOperation`/`noteProxyOperation`
     * here — on Android 12+ those return a `SyncNotedAppOp` object, so returning
     * an int `MODE_ALLOWED` would cause a ClassCastException. The permission
     * gate that Huawei AppGallery (and others) hit is the `checkOperation` path.
     *
     * These methods share a leading `(int code, int uid, String packageName,
     * ...)` shape, so we locate the op-code int and the package/uid positionally.
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

            // Only hook methods that return an int op MODE. On Android 12+
            // noteOperation/noteProxyOperation return a SyncNotedAppOp object, so
            // returning an int MODE_ALLOWED there would throw a ClassCastException.
            // The `canRequestPackageInstalls()` gate that Huawei AppGallery hits
            // funnels into checkOperation (int return), so this is sufficient and
            // remains version-safe on older releases where note* also returns int.
            if (m.returnType != Int::class.javaPrimitiveType) continue

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
                log(Log.INFO, TAG, "Hooked AppOpsService.${m.name}(${params.size} args)")
            }.onFailure { log(Log.WARN, TAG, "AppOpsService.${m.name} hook failed", it) }
        }
        log(Log.INFO, TAG, "Hooked AppOpsService op checks ($hooked methods)")
        if (hooked == 0) {
            log(Log.ERROR, TAG, "No AppOpsService op-check methods hooked (method names mismatch?)")
        }
    }

    /**
     * Hooks the Enhanced Confirmation Mode (ECM) gate introduced in Android 15
     * (API 35) and used on Android 16.
     *
     * `com.android.server.ecm.EnhancedConfirmationService.isRestricted(String
     * packageName, String settingIdentifier)` returns `true` when a package is
     * blocked from toggling a "restricted setting" — including granting
     * install-from-unknown-sources. Sideloaded apps get flagged and the
     * "Allow from this source" toggle is greyed out ("Restricted setting").
     *
     * The `settingIdentifier` for install permission is either the permission
     * name `android.permission.REQUEST_INSTALL_PACKAGES` or the appop string
     * `android:request_install_packages`, depending on the caller. We force
     * `false` (not restricted) for selected apps on install-related identifiers.
     *
     * The class may not exist on Android < 15; that's fine (runCatching). We
     * walk the superclass chain in case `isRestricted` is declared on a base.
     */
    private fun hookEnhancedConfirmation(cl: ClassLoader) {
        val seeds = listOf(
            "com.android.server.ecm.EnhancedConfirmationService",
            "com.android.server.pm.EnhancedConfirmationService",
        )
        val seen = HashSet<java.lang.reflect.Method>()
        var hooked = 0
        for (name in seeds) {
            var clazz: Class<*>? = runCatching { cl.loadClass(name) }.getOrNull()
            while (clazz != null && clazz != Any::class.java) {
                val current = clazz
                for (m in current.declaredMethods) {
                    if (m.name != "isRestricted") continue
                    if (m.returnType != Boolean::class.javaPrimitiveType) continue
                    val params = m.parameterTypes
                    // isRestricted(String packageName, String settingIdentifier)
                    if (params.size < 2) continue
                    if (params[0] != String::class.java || params[1] != String::class.java) continue
                    if (!seen.add(m)) continue

                    runCatching {
                        hook(m).intercept { chain ->
                            val pkg = chain.getArg(0) as? String
                            val setting = chain.getArg(1) as? String
                            if (bypassEcmEnabled() &&
                                pkg != null && pkg != OWN_PACKAGE && isSelected(pkg) &&
                                setting != null && setting in INSTALL_SETTING_IDENTIFIERS
                            ) {
                                log(Log.INFO, TAG, "ECM isRestricted($pkg, $setting) -> forced false")
                                return@intercept false
                            }
                            chain.proceed()
                        }
                        hooked++
                        log(Log.INFO, TAG, "Hooked ${current.name}.isRestricted(${params.size} args)")
                    }.onFailure {
                        log(Log.WARN, TAG, "${current.name}.isRestricted hook failed", it)
                    }
                }
                clazz = current.superclass
            }
        }
        if (hooked == 0) {
            log(Log.INFO, TAG, "No ECM isRestricted method hooked (pre-Android 15?)")
        }
    }

    /**
     * Hooks the `DISALLOW_INSTALL_UNKNOWN_SOURCES` user restriction, the OTHER
     * mechanism Android 16 Advanced Protection uses to block sideloading (in
     * addition to force-setting appop 66 to MODE_ERRORED, which our
     * AppOpsService hook counters).
     *
     * On enable, Advanced Protection calls
     * `DevicePolicyManager.addUserRestrictionGlobally(...,
     * DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)`. That funnels into
     * `com.android.server.pm.UserManagerService.hasUserRestriction(String
     * restrictionKey, int userId)` (and `hasBaseUserRestriction`) which every
     * install flow consults.
     *
     * CAVEAT: these methods carry NO package/uid argument — only the restriction
     * key and userId — so we CANNOT scope the override to a specific selected
     * app. Overriding therefore relaxes the install-unknown-sources restriction
     * user-wide. To limit blast radius we only do so when the user has selected
     * at least one app (`hasAnySelection()`), i.e. the module is actively in use.
     * We restrict the override to install-related keys only; all other
     * restrictions proceed untouched.
     */
    private fun hookUserRestriction(cl: ClassLoader) {
        val clazz = runCatching {
            cl.loadClass("com.android.server.pm.UserManagerService")
        }.getOrElse {
            runCatching { cl.loadClass("com.android.server.pm.UserManagerServiceImpl") }.getOrNull()
        } ?: run {
            log(Log.WARN, TAG, "UserManagerService class not found")
            return
        }

        val seen = HashSet<java.lang.reflect.Method>()
        var hooked = 0
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val c = current
            for (m in c.declaredMethods) {
                if (m.name != "hasUserRestriction" && m.name != "hasBaseUserRestriction") continue
                if (m.returnType != Boolean::class.javaPrimitiveType) continue
                val params = m.parameterTypes
                // (String restrictionKey, int userId)
                val keyIndex = params.indexOfFirst { it == String::class.java }
                if (keyIndex < 0) continue
                if (!seen.add(m)) continue

                runCatching {
                    hook(m).intercept { chain ->
                        val key = chain.getArg(keyIndex) as? String
                        if (key != null && key in INSTALL_USER_RESTRICTIONS &&
                            bypassUserRestrictionEnabled() && hasAnySelection()
                        ) {
                            log(Log.INFO, TAG, "${m.name}($key) -> forced false")
                            return@intercept false
                        }
                        chain.proceed()
                    }
                    hooked++
                    log(Log.INFO, TAG, "Hooked ${c.name}.${m.name}(${params.size} args)")
                }.onFailure {
                    log(Log.WARN, TAG, "${c.name}.${m.name} hook failed", it)
                }
            }
            current = c.superclass
        }
        if (hooked == 0) {
            log(Log.WARN, TAG, "No UserManagerService restriction method hooked")
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

    private fun systemContext(): Context? {
        cachedCtx?.let { return it }
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val systemThread = atClass.getMethod("currentActivityThread").invoke(null)
            val ctx = atClass.getMethod("getSystemContext")
                .invoke(systemThread) as Context
            cachedCtx = ctx
            ctx
        }.getOrNull()
    }

    private fun systemPackageManager(): PackageManager? =
        systemContext()?.packageManager

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
                            val intentArg =
                                if (intentIndex >= 0) chain.getArg(intentIndex) as? Intent else null
                            val selected = caller != null && caller != OWN_PACKAGE && isSelected(caller)
                            // Only install intents matter here; logging every
                            // startActivity of a selected app is far too noisy.
                            if (intentArg?.action == ACTION_INSTALL_PACKAGE && !selected) {
                                log(
                                    Log.INFO, TAG,
                                    "startActivity($mName): install intent from non-selected caller=$caller, ignored"
                                )
                            }
                            if (selected) {
                                val installer = installerPackage()
                                intentArg
                                    ?.takeIf { it.action == ACTION_INSTALL_PACKAGE }
                                    ?.let { rewriteInstallIntent(it, installer, caller) }
                                if (intentArrayIndex >= 0) {
                                    (chain.getArg(intentArrayIndex) as? Array<*>)?.forEach { i ->
                                        (i as? Intent)
                                            ?.takeIf { it.action == ACTION_INSTALL_PACKAGE }
                                            ?.let { rewriteInstallIntent(it, installer, caller) }
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
        log(
            Log.INFO, TAG,
            "rewriteInstallIntent: caller=$caller action=${intent.action} uri=${intent.data} " +
                "pkg=${intent.`package`} component=${intent.component} " +
                "returnResult=${intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)} " +
                "extraPkg=${intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)}"
        )
        if (intent.action != ACTION_INSTALL_PACKAGE) {
            log(Log.INFO, TAG, "rewriteInstallIntent: skip (action != ACTION_INSTALL_PACKAGE)")
            return
        }
        if (installer.isBlank()) {
            log(Log.INFO, TAG, "rewriteInstallIntent: skip (installer blank)")
            return
        }
        val explicit = intent.`package` != null || intent.component != null
        if (explicit && !hijackExplicitEnabled()) {
            log(
                Log.INFO, TAG,
                "rewriteInstallIntent: skip (explicit package=${intent.`package`} " +
                    "component=${intent.component}; hijack-explicit is off)"
            )
            return
        }
        if (explicit) {
            log(
                Log.INFO, TAG,
                "rewriteInstallIntent: hijacking explicit target " +
                    "(package=${intent.`package`} component=${intent.component})"
            )
        }
        val uri = intent.data
        if (uri == null) {
            // An ACTION_INSTALL_PACKAGE intent without a data URI carries no APK
            // to hand off. Repackaging it at the installer would just make the
            // launch fail silently, so leave it untouched.
            log(Log.INFO, TAG, "rewriteInstallIntent: skip (no data uri to install)")
            return
        }
        intent.component = PROXY_COMPONENT
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra(EXTRA_ABP_INSTALLER, installer)
        intent.putExtra(EXTRA_ABP_CALLER, caller)
        log(
            Log.INFO, TAG,
            "rewriteInstallIntent: -> proxy ${PROXY_COMPONENT.className} " +
                "(installer=$installer, uri=${uri.scheme}://...)"
        )
    }

    // ---------------------------------------------------------------------
    // Hook 3 (system_server): PackageInstaller.Session installs for a selected
    // app -> extract staged APK, ACTION_VIEW to the installer, poll, report.
    //
    // Apps that don't use ACTION_INSTALL_PACKAGE create an install session via
    // PackageInstaller and call Session.commit(IntentSender). Under Advanced
    // Protection the session's confirmation flow ends in the "restricted"
    // dialog, so we intercept commit centrally in system_server, copy the
    // staged APK into our app's cache, hand it to the configured installer via
    // ACTION_VIEW, then poll PackageManager and synthesize the terminal status
    // back through the session's IntentSender status receiver.
    // ---------------------------------------------------------------------

    private fun hookPackageInstallerSession(cl: ClassLoader) {
        val clazz = runCatching {
            cl.loadClass("com.android.server.pm.PackageInstallerSession")
        }.getOrNull() ?: run {
            log(Log.WARN, TAG, "PackageInstallerSession class not found")
            return
        }

        var hooked = 0
        for (m in clazz.declaredMethods) {
            if (m.name != "commit") continue
            val params = m.parameterTypes
            if (params.size != 2) continue
            if (params[0] != IntentSender::class.java) continue
            if (params[1] != Boolean::class.javaPrimitiveType) continue
            if (m.returnType != Void.TYPE) continue

            runCatching {
                hook(m).intercept { chain ->
                    var handled = false
                    try {
                        val session = chain.getThisObject()
                        // The status receiver is the FIRST argument of
                        // commit(IntentSender, boolean). We must read it from
                        // here: the session's own mRemoteStatusReceiver field is
                        // only populated by markAsSealed(), which never runs
                        // because we don't proceed().
                        val statusReceiver = chain.getArg(0) as? IntentSender
                        if (session != null) {
                            val installer = sessionInstallerPackage(session)
                            val sid = sessionId(session)
                            val selected = installer != null && installer != OWN_PACKAGE && isSelected(installer)
                            log(Log.INFO, TAG, "Session.commit: sid=$sid installer=$installer selected=$selected receiver=${statusReceiver != null}")
                            if (selected) {
                                handled = true
                                log(Log.INFO, TAG, "Session.commit: INTERCEPTING commit for selected app $installer (sid=$sid)")
                                proxySessionInstall(session, installer, statusReceiver)
                            }
                        } else {
                            log(Log.WARN, TAG, "Session.commit: thisObject is null")
                        }
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "PackageInstallerSession.commit intercept failed", t)
                    }
                    if (handled) null else chain.proceed()
                }
                hooked++
                log(Log.INFO, TAG, "Hooked PackageInstallerSession.commit")
            }.onFailure {
                log(Log.WARN, TAG, "PackageInstallerSession.commit hook failed", it)
            }
        }
        if (hooked == 0) {
            log(Log.WARN, TAG, "No PackageInstallerSession.commit method hooked")
        }

        // Swallow requestUserPreapproval for selected installers so the
        // pre-approval dialog never appears.
        for (m in clazz.declaredMethods) {
            if (m.name != "requestUserPreapproval") continue
            runCatching {
                hook(m).intercept { chain ->
                    var handled = false
                    try {
                        val session = chain.getThisObject()
                        if (session != null) {
                            val installer = sessionInstallerPackage(session)
                            val selected = installer != null && installer != OWN_PACKAGE && isSelected(installer)
                            log(Log.INFO, TAG, "requestUserPreapproval: installer=$installer selected=$selected")
                            if (selected) {
                                handled = true
                                log(Log.INFO, TAG, "Swallowing requestUserPreapproval from $installer")
                            }
                        }
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "requestUserPreapproval intercept failed", t)
                    }
                    if (handled) null else chain.proceed()
                }
                log(Log.INFO, TAG, "Hooked PackageInstallerSession.requestUserPreapproval")
            }.onFailure {
                log(Log.WARN, TAG, "requestUserPreapproval hook failed", it)
            }
        }
    }

    /**
     * Replaces a selected app's session install with an ACTION_VIEW hand-off to
     * the configured installer. The fast path runs on the Binder thread (read the
     * staged APK path + receiver); the copy/launch/wait/report runs on a
     * background coroutine so the commit Binder call returns immediately.
     */
    private fun proxySessionInstall(
        session: Any,
        sessionInstaller: String,
        statusReceiver: IntentSender?,
    ) {
        // NOTE: `sessionInstaller` is the app that OWNS the session (the hooked
        // app, e.g. AppGallery). The app that actually performs the install is
        // the user-configured installer from preferences.
        val targetInstaller = installerPackage()
        log(
            Log.INFO, TAG,
            "[B] proxySessionInstall start: sessionInstaller=$sessionInstaller " +
                "targetInstaller=$targetInstaller"
        )
        if (targetInstaller.isBlank()) {
            log(Log.WARN, TAG, "[B] no target installer configured")
            sendSessionStatus(session, statusReceiver, null, false, "no installer configured")
            return
        }
        val ctx = systemContext()
        val baseApk = sessionBaseApk(session)
        // Prefer the IntentSender handed to commit(); fall back to the session
        // field for the pre-approval path where it is already set.
        val receiver = statusReceiver ?: sessionRemoteStatusReceiver(session)
        log(
            Log.INFO, TAG,
            "[B] probe: ctx=${ctx != null} receiver=${receiver != null} " +
                "(fromCommitArg=${statusReceiver != null}) baseApk=$baseApk"
        )
        if (ctx == null || receiver == null || baseApk == null) {
            log(Log.WARN, TAG, "[B] Cannot proxy session install (ctx=$ctx receiver=$receiver apk=$baseApk)")
            sendSessionStatus(session, receiver, null, false, "unable to stage apk")
            return
        }
        val archive = runCatching {
            ctx.packageManager.getPackageArchiveInfo(baseApk.absolutePath, 0)
        }.getOrNull()
        val pkg = archive?.packageName
        val version = archive?.longVersionCode ?: 0L
        val sid = sessionId(session)
        log(Log.INFO, TAG, "[B] parsed staged apk: baseApk=${baseApk.absolutePath} pkg=$pkg version=$version sid=$sid")
        if (pkg == null) {
            sendSessionStatus(session, receiver, null, false, "could not parse staged apk")
            return
        }

        installScope.launch {
            // Route InstallHandoff's progress lines into the Xposed log.
            val handoffLog: (String) -> Unit = { msg -> log(Log.INFO, TAG, "[B] $msg") }
            try {
                val copied = withContext(Dispatchers.IO) { copyApkForInstaller(ctx, baseApk, sid) }
                log(Log.INFO, TAG, "[B] copyApkForInstaller -> $copied")
                if (copied == null) {
                    sendSessionStatus(session, receiver, pkg, false, "failed to stage apk")
                    return@launch
                }
                val launched =
                    InstallHandoff.launchInstaller(ctx, copied, targetInstaller, handoffLog)
                log(Log.INFO, TAG, "[B] launchInstaller($targetInstaller) -> $launched")
                if (!launched) {
                    sendSessionStatus(session, receiver, pkg, false, "installer $targetInstaller unavailable")
                    return@launch
                }
                // Release the original staged session; our copy is independent.
                sessionAbandon(session)
                log(Log.INFO, TAG, "[B] session abandoned; waiting pkg=$pkg targetVersion=$version")
                val success = InstallHandoff.awaitInstalled(ctx, pkg, version, handoffLog)
                log(Log.INFO, TAG, "[B] wait result: $success")
                sendSessionStatus(
                    session, receiver, pkg, success,
                    if (success) "Success" else "Install failed or timed out"
                )
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "[B] proxySessionInstall failed", t)
                sendSessionStatus(session, receiver, pkg, false, "internal error")
            }
        }
    }

    private fun sessionInstallerPackage(session: Any): String? {
        val direct = runCatching {
            val m = session.javaClass.getDeclaredMethod("getInstallerPackageName")
            m.isAccessible = true
            m.invoke(session) as? String
        }.getOrNull()
        if (direct != null) return direct
        // Fallback: getInstallSource().mInstallerPackageName
        return runCatching {
            val g = session.javaClass.getDeclaredMethod("getInstallSource")
            g.isAccessible = true
            val src = g.invoke(session) ?: return@runCatching null
            val f = src.javaClass.getDeclaredField("mInstallerPackageName")
            f.isAccessible = true
            f.get(src) as? String
        }.getOrNull()
    }

    private fun sessionStageDir(session: Any): File? = runCatching {
        val f = session.javaClass.getDeclaredField("stageDir")
        f.isAccessible = true
        f.get(session) as? File
    }.getOrNull()

    private fun sessionBaseApk(session: Any): File? {
        val dir = sessionStageDir(session) ?: return null
        if (!dir.isDirectory) return null
        val base = File(dir, "base.apk")
        if (base.isFile) return base
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?.maxByOrNull { it.length() }
    }

    private fun sessionId(session: Any): Int = runCatching {
        val f = session.javaClass.getDeclaredField("sessionId")
        f.isAccessible = true
        f.getInt(session)
    }.getOrDefault(-1)

    private fun sessionRemoteStatusReceiver(session: Any): IntentSender? = runCatching {
        val m = session.javaClass.getDeclaredMethod("getRemoteStatusReceiver")
        m.isAccessible = true
        m.invoke(session) as? IntentSender
    }.getOrNull()

    private fun sessionAbandon(session: Any) {
        runCatching {
            val m = session.javaClass.getDeclaredMethod("abandon")
            m.isAccessible = true
            m.invoke(session)
        }
    }

    /**
     * Stages a copy of the intercepted APK into our app's cache so the
     * FileProvider can serve it to the installer via a content:// URI.
     *
     * system_server cannot write into the app's private data dir itself (SELinux
     * denies `system_server -> app_data_file` writes; making the dir world
     * writable only fixes the DAC layer), and a file descriptor cannot be handed
     * over through an Intent (ActivityStarter rejects intents carrying FDs). So
     * the descriptor is sent through the app's [ApkStageProvider] over Binder and
     * the app's own process performs the copy.
     */
    private fun copyApkForInstaller(ctx: Context, src: File, sid: Int): File? = try {
        log(Log.INFO, TAG, "[B] stageApk: calling provider src=${src.absolutePath} sid=$sid")
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        val result = try {
            val args = Bundle().apply {
                putParcelable(ApkStageProvider.KEY_PFD, pfd)
                putInt(ApkStageProvider.KEY_SESSION_ID, sid)
            }
            ctx.contentResolver.call(STAGE_URI, ApkStageProvider.METHOD_STAGE_APK, null, args)
        } finally {
            pfd.close()
        }
        // The provider runs in the app process, so its own Log.* output never
        // reaches the Xposed log; it returns a human-readable message instead
        // and we log it here.
        val message = result?.getString(ApkStageProvider.KEY_MESSAGE)
        val ok = result?.getBoolean(ApkStageProvider.KEY_OK) == true
        val path = result?.getString(ApkStageProvider.KEY_PATH)
        if (result == null) {
            log(Log.WARN, TAG, "[B] stageApk: provider returned null (not installed or not callable?)")
        } else {
            log(Log.INFO, TAG, "[B] stageApk -> ok=$ok msg=$message")
        }
        if (ok && path != null) File(path) else null
    } catch (t: Throwable) {
        log(Log.WARN, TAG, "[B] stageApk failed", t)
        null
    }

    /**
     * Synthesizes the terminal status the system would normally deliver via
     * `sendOnPackageInstalled`, using the status receiver captured from the
     * `commit(IntentSender, ...)` call we intercepted.
     */
    private fun sendSessionStatus(
        session: Any,
        receiver: IntentSender?,
        pkg: String?,
        success: Boolean,
        msg: String,
    ) {
        log(Log.INFO, TAG, "[B] sendSessionStatus: pkg=$pkg success=$success msg=$msg")
        val ctx = systemContext()
        if (receiver == null) {
            log(Log.WARN, TAG, "[B] sendSessionStatus: no status receiver available")
            return
        }
        if (ctx == null) {
            log(Log.WARN, TAG, "[B] sendSessionStatus: no system context")
            return
        }
        val sid = sessionId(session)
        val fillIn = Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sid)
            putExtra(
                PackageInstaller.EXTRA_STATUS,
                if (success) PackageInstaller.STATUS_SUCCESS else PackageInstaller.STATUS_FAILURE
            )
            putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, msg)
            // PackageInstaller.EXTRA_LEGACY_STATUS is @hide; its stable value is
            // "android.content.pm.extra.LEGACY_STATUS". The legacy status is a
            // PackageManager.INSTALL_* code (INSTALL_SUCCEEDED=1,
            // INSTALL_FAILED_INTERNAL_ERROR=-110), also @hide in the SDK stubs.
            putExtra(
                EXTRA_LEGACY_STATUS,
                if (success) 1 else -110
            )
            if (pkg != null) putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, pkg)
        }
        runCatching {
            receiver.sendIntent(ctx, 0, fillIn, null, null, null, null)
        }.onFailure {
            log(Log.WARN, TAG, "sendSessionStatus failed", it)
        }.onSuccess {
            log(Log.INFO, TAG, "Session $sid status=${if (success) "SUCCESS" else "FAILURE"} ($msg)")
        }
    }

    companion object {
        private const val TAG = "ABP"
        private const val OWN_PACKAGE = "wonton.abp"

        private val PROXY_COMPONENT =
            ComponentName(OWN_PACKAGE, "wonton.abp.install.InstallProxyActivity")
        private val STAGE_URI = Uri.parse("content://${ApkStageProvider.AUTHORITY}")
        private const val EXTRA_ABP_INSTALLER = "wonton.abp.extra.INSTALLER"
        private const val EXTRA_ABP_CALLER = "wonton.abp.extra.CALLER"
        // PackageInstaller.EXTRA_LEGACY_STATUS (@hide): stable string value.
        private const val EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"

        // AppOpsManager.OP_REQUEST_INSTALL_PACKAGES is @hide; its stable value is 66.
        private const val OP_REQUEST_INSTALL_PACKAGES = 66

        // SERVER-side AppOpsService methods that RETURN AN INT MODE. We only hook
        // these (not note*/start*, which return SyncNotedAppOp on Android 12+ and
        // would throw a ClassCastException). canRequestPackageInstalls() ->
        // AppOpsManager.checkOpNoThrow() -> IAppOpsService.checkOperation() ->
        // AppOpsService.checkOperation(int, int, String) on the system_server side.
        private val APP_OPS_METHODS = setOf(
            "checkOperation",
            "checkOperationRaw",
        )

        // Enhanced Confirmation Mode (Android 15+) setting identifiers that gate
        // install-from-unknown-sources. ECM's isRestricted(pkg, settingId) may be
        // called with either the permission name or the appop string.
        private val INSTALL_SETTING_IDENTIFIERS = setOf(
            "android.permission.REQUEST_INSTALL_PACKAGES",
            // AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES
            "android:request_install_packages",
        )

        // UserManager restriction keys that block sideloading. Android 16
        // Advanced Protection sets DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY; the
        // per-user and legacy install-apps keys are included for completeness.
        // Values are @hide-stable string constants.
        private val INSTALL_USER_RESTRICTIONS = setOf(
            // UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
            "no_install_unknown_sources",
            // UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
            "no_install_unknown_sources_globally",
        )

        @Suppress("DEPRECATION")
        private val ACTION_INSTALL_PACKAGE = Intent.ACTION_INSTALL_PACKAGE
    }
}
