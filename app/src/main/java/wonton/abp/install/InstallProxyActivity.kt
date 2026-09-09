package wonton.abp.install

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wonton.abp.App
import wonton.abp.common.ModuleLog
import wonton.abp.common.Prefs
import wonton.abp.data.ConfigStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Invisible proxy activity for `ACTION_INSTALL_PACKAGE` (Task 2-A).
 *
 * The Xposed module (running in system_server) redirects a selected app's
 * `ACTION_INSTALL_PACKAGE` intent to this component. This activity:
 *
 *  1. Copies the APK (readable via the caller's URI grant) into our own cache.
 *  2. Hands it to the configured installer via `ACTION_VIEW` (a FileProvider
 *     content:// URI + read grant), so the installer performs the actual,
 *     unrestricted install.
 *  3. Caches the target package name + versionCode (parsed from the APK), then
 *     polls `PackageManager` until the package is installed at the expected
 *     version (or a timeout).
 *  4. When the caller set `Intent.EXTRA_RETURN_RESULT`, synthesizes the
 *     `Intent.EXTRA_INSTALL_RESULT` + `Intent.EXTRA_PACKAGE_NAME` result and
 *     returns it through the original `resultTo` chain (the proxy was started
 *     in the caller's task as a for-result activity).
 *
 * It has no UI and is never shown in the launcher / recents.
 */
class InstallProxyActivity : Activity() {

    private val finished = AtomicBoolean(false)

    /**
     * Scope for the install flow. Cancelled in [onDestroy] so the wait loop can
     * never outlive the activity.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Logs to logcat and forwards the line to the module in system_server, which
     * re-logs it through the Xposed logger. LSPosed only captures logs written
     * from hooked processes, so without this the whole A path would be invisible
     * in the LSPosed log.
     */
    private fun log(message: String, t: Throwable? = null) {
        if (t == null) Log.i(TAG, message) else Log.w(TAG, message, t)
        runCatching {
            sendBroadcast(Intent(ModuleLog.ACTION).putExtra(ModuleLog.EXTRA_LINE, message))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val received = intent
        log(
            "[2A] Proxy onCreate: caller=${received.getStringExtra(EXTRA_ABP_CALLER)} " +
                "action=${received.action} uri=${received.data} " +
                "installerExtra=${received.getStringExtra(EXTRA_ABP_INSTALLER)} " +
                "returnResult=${received.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)} " +
                "extraPkg=${received.getStringExtra(Intent.EXTRA_PACKAGE_NAME)} " +
                "recreated=${savedInstanceState != null}"
        )
        if (savedInstanceState != null) {
            // Recreated after process death. The first run already handed the
            // APK to the installer; re-running would stage a second copy and
            // launch the installer again, and the caller would never get a
            // result. configChanges in the manifest keeps the common rotation
            // case from reaching this branch at all.
            log("[2A] recreated after process death; not re-running the install flow")
            finishWithResult(pkg = null, success = false)
            return
        }
        // The module (system_server) hands the APK over through
        // ApkStageProvider, so the cache only needs to be writable by this app
        // itself - no cross-process directory access is involved.
        val apkUri = received.data
        val installer = received.getStringExtra(EXTRA_ABP_INSTALLER)
            ?: readInstallerFromPrefs()
        log("[2A] resolved: apkUri=$apkUri installer=$installer")
        if (apkUri == null || installer.isBlank()) {
            log("[2A] abort: apkUri=$apkUri installer=$installer")
            finishWithResult(pkg = null, success = false)
            return
        }

        runInstallFlow(apkUri, installer)
    }

    /**
     * Runs the whole install hand-off as a coroutine: the blocking parts (copying
     * the APK, parsing it, querying the package manager) hop to
     * [Dispatchers.IO], while everything touching activity state stays on the
     * main thread.
     */
    private fun runInstallFlow(apkUri: Uri, installer: String) {
        scope.launch {
            // 1. Copy the APK into our own cache (we hold the caller's read grant).
            val copied = withContext(Dispatchers.IO) { copyToCache(apkUri) }
            log("[2A] copyToCache -> $copied")
            if (copied == null) {
                log("[2A] abort: failed to copy APK")
                finishWithResult(pkg = null, success = false)
                return@launch
            }

            // 2. Parse target package + versionCode from the APK itself.
            val archive = withContext(Dispatchers.IO) {
                runCatching { packageManager.getPackageArchiveInfo(copied.absolutePath, 0) }
                    .getOrNull()
            }
            val targetPkg = archive?.packageName
            val targetVersion = archive?.longVersionCode ?: 0L
            log("[2A] parsed apk: pkg=$targetPkg version=$targetVersion")
            if (targetPkg == null) {
                log("[2A] abort: could not parse APK")
                finishWithResult(pkg = null, success = false)
                return@launch
            }

            // 3. Hand the APK to the configured installer (startActivity, main thread).
            //    InstallHandoff also watches package broadcasts internally, so
            //    the wait below reacts immediately to the install landing.
            val launched = InstallHandoff.launchInstaller(
                this@InstallProxyActivity, copied, installer, ::log
            )
            log("[2A] launchInstaller($installer) -> $launched")
            if (!launched) {
                finishWithResult(targetPkg, success = false)
                return@launch
            }

            // 4. Stay alive (invisible) and wait for the install result.
            val success = InstallHandoff.awaitInstalled(
                this@InstallProxyActivity, targetPkg, targetVersion, ::log
            )
            log("[2A] wait finished: success=$success")
            finishWithResult(targetPkg, success)
        }
    }

    private fun readInstallerFromPrefs(): String = runCatching {
        ConfigStore(App.service.value).getInstallerPackage()
    }.getOrElse { Prefs.DEFAULT_INSTALLER }

    private fun copyToCache(uri: Uri): File? = try {
        val apks = File(cacheDir, "apks").apply { mkdirs() }
        // Clear stale staged APKs from previous installs.
        apks.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(apks, "install-${System.currentTimeMillis()}.apk")
        log("[2A] copying uri=$uri to ${out.absolutePath}")
        // file:// URIs cannot be opened through the content resolver, so read
        // the path directly as a fallback (best effort; it may be unreadable
        // when it belongs to another app).
        val input = when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile }?.inputStream()
            else -> contentResolver.openInputStream(uri)
        }
        if (input == null) {
            log("[2A] could not open $uri (no read grant / file not readable?)")
            return null
        }
        input.use { source ->
            FileOutputStream(out).use { target -> source.copyTo(target) }
        }
        log("[2A] copied ${out.length()} bytes")
        out
    } catch (t: Throwable) {
        log("[2A] copyToCache failed: ${t.javaClass.simpleName}: ${t.message}", t)
        null
    }

    private fun finishWithResult(pkg: String?, success: Boolean) {
        if (!finished.compareAndSet(false, true)) {
            log("[2A] finishWithResult skipped (already finishing)")
            return
        }
        log("[2A] finishWithResult: pkg=$pkg success=$success")
        val data = Intent().apply {
            // Intent.EXTRA_INSTALL_RESULT is @hide; its stable value is
            // "android.intent.extra.INSTALL_RESULT".
            putExtra(
                EXTRA_INSTALL_RESULT,
                if (success) PackageInstaller.STATUS_SUCCESS else PackageInstaller.STATUS_FAILURE
            )
            if (pkg != null) putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        // Do NOT delete the staged APK here: the installer may still be reading
        // it through the FileProvider while the install is in flight. Stale
        // files are cleaned up by copyToCache() on the next run instead.
        log("[2A] Proxy onDestroy")
        finished.set(true)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ABP"

        /** Extra set by the module when redirecting to this proxy. */
        const val EXTRA_ABP_INSTALLER = "wonton.abp.extra.INSTALLER"
        const val EXTRA_ABP_CALLER = "wonton.abp.extra.CALLER"

        /** Intent.EXTRA_INSTALL_RESULT (@hide); stable string value. */
        private const val EXTRA_INSTALL_RESULT = "android.intent.extra.INSTALL_RESULT"
    }
}
