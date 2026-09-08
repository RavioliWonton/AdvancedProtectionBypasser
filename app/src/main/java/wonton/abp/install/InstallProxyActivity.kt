package wonton.abp.install

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import wonton.abp.App
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
     * Wake-up signal for [awaitInstalled]. Conflated: the loop only needs to
     * know that *something* happened, and a signal sent while the loop is
     * checking is kept for the next iteration.
     */
    private val installEvents = Channel<Unit>(Channel.CONFLATED)

    private var receiverRegistered = false

    /**
     * System package add/replace/remove events. The module's installers go
     * through `PackageInstaller`, which broadcasts one of these as soon as the
     * package is on disk, so the wait loop can return immediately instead of
     * waiting for its next tick.
     */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(
                TAG,
                "[2A] package event: action=${intent?.action} " +
                    "pkg=${intent?.data?.schemeSpecificPart}"
            )
            installEvents.trySend(Unit)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val received = intent
        Log.i(
            TAG, "[2A] Proxy onCreate: caller=${received.getStringExtra(EXTRA_ABP_CALLER)} " +
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
            Log.w(TAG, "[2A] recreated after process death; not re-running the install flow")
            finishWithResult(pkg = null, success = false)
            return
        }
        // The module (system_server) stages APKs into our cache; make sure the
        // cache subtree is traversable/writable for it. This also persists.
        ensureCacheAccessible()

        val apkUri = received.data
        val installer = received.getStringExtra(EXTRA_ABP_INSTALLER)
            ?: readInstallerFromPrefs()
        Log.i(TAG, "[2A] resolved: apkUri=$apkUri installer=$installer")
        if (apkUri == null || installer.isBlank()) {
            Log.w(TAG, "[2A] abort: apkUri=$apkUri installer=$installer")
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
            Log.i(TAG, "[2A] copyToCache -> $copied")
            if (copied == null) {
                Log.w(TAG, "[2A] abort: failed to copy APK")
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
            Log.i(TAG, "[2A] parsed apk: pkg=$targetPkg version=$targetVersion")
            if (targetPkg == null) {
                Log.w(TAG, "[2A] abort: could not parse APK")
                finishWithResult(pkg = null, success = false)
                return@launch
            }

            // 3. Subscribe to the system's package events so the wait below can
            //    react immediately (the periodic tick stays as a fallback).
            registerPackageReceiver()

            // 4. Hand the APK to the configured installer (startActivity, main thread).
            val launched = launchInstaller(copied, installer)
            Log.i(TAG, "[2A] launchInstaller($installer) -> $launched")
            if (!launched) {
                finishWithResult(targetPkg, success = false)
                return@launch
            }

            // 5. Stay alive (invisible) and wait for the install result.
            val success = awaitInstalled(targetPkg, targetVersion)
            Log.i(TAG, "[2A] wait finished: success=$success")
            finishWithResult(targetPkg, success)
        }
    }

    private fun ensureCacheAccessible() {
        val ok = runCatching {
            val apks = File(cacheDir, "apks")
            apks.mkdirs()
            dataDir?.setReadable(true, false)
            dataDir?.setExecutable(true, false)
            cacheDir.setReadable(true, false)
            cacheDir.setExecutable(true, false)
            cacheDir.setWritable(true, false)
            apks.setReadable(true, false)
            apks.setExecutable(true, false)
            apks.setWritable(true, false)
            apks.exists() && cacheDir.exists()
        }.getOrDefault(false)
        Log.i(TAG, "[2A] ensureCacheAccessible -> $ok (cache=${cacheDir.absolutePath})")
    }

    private fun readInstallerFromPrefs(): String = runCatching {
        ConfigStore(App.service.value).getInstallerPackage()
    }.getOrElse { Prefs.DEFAULT_INSTALLER }

    private fun copyToCache(uri: Uri): File? = try {
        val apks = File(cacheDir, "apks").apply { mkdirs() }
        // Clear stale staged APKs from previous installs.
        apks.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(apks, "install-${System.currentTimeMillis()}.apk")
        Log.i(TAG, "[2A] copying uri=$uri to ${out.absolutePath}")
        // file:// URIs cannot be opened through the content resolver, so read
        // the path directly as a fallback (best effort; it may be unreadable
        // when it belongs to another app).
        val input = when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile }?.inputStream()
            else -> contentResolver.openInputStream(uri)
        }
        if (input == null) {
            Log.w(TAG, "[2A] could not open $uri (no read grant / file not readable?)")
            return null
        }
        input.use { source ->
            FileOutputStream(out).use { target -> source.copyTo(target) }
        }
        Log.i(TAG, "[2A] copied ${out.length()} bytes")
        out
    } catch (t: Throwable) {
        Log.w(TAG, "[2A] copyToCache failed", t)
        null
    }

    private fun launchInstaller(apk: File, installer: String): Boolean = try {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, INSTALLER_VIEW_MIME)
            .setPackage(installer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Log.i(TAG, "[2A] launching installer: intent=$i")
        startActivity(i)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "[2A] launchInstaller failed for $installer", t)
        false
    }

    private fun registerPackageReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // These are protected system broadcasts, so NOT_EXPORTED still delivers
        // them (other apps cannot spoof them either) while satisfying the
        // mandatory flag on targetSdk 34+.
        ContextCompat.registerReceiver(
            this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        Log.i(TAG, "[2A] package receiver registered")
    }

    /**
     * Waits until [pkg] is installed at [targetVersion], or [POLL_TIMEOUT_MS]
     * elapses.
     *
     * Rather than a fixed-interval poll, it blocks on [installEvents]: a package
     * add/replace broadcast wakes it up, so the result is noticed as soon as the
     * install lands. The [POLL_INTERVAL_MS] timeout is the safety net for a
     * signal that never arrives (install finished before the receiver was
     * registered, broadcast not delivered, ...), which keeps the worst case
     * identical to plain polling.
     */
    private suspend fun awaitInstalled(pkg: String, targetVersion: Long): Boolean {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var i = 0
        while (true) {
            val installed = isInstalled(pkg, targetVersion)
            Log.i(TAG, "[2A] wait #${i++}: installed=$installed")
            if (installed) return true
            if (System.currentTimeMillis() >= deadline) {
                Log.i(TAG, "[2A] wait timed out for $pkg")
                return false
            }
            // receiveCatching() makes a closed channel non-fatal.
            withTimeoutOrNull(POLL_INTERVAL_MS) { installEvents.receiveCatching() }
        }
    }

    private suspend fun isInstalled(pkg: String, targetVersion: Long): Boolean {
        val info = withContext(Dispatchers.IO) {
            runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        }
        val current = info?.longVersionCode
        return info != null && (targetVersion == 0L || current != null && current >= targetVersion)
    }

    private fun finishWithResult(pkg: String?, success: Boolean) {
        if (!finished.compareAndSet(false, true)) {
            Log.i(TAG, "[2A] finishWithResult skipped (already finishing)")
            return
        }
        Log.i(TAG, "[2A] finishWithResult: pkg=$pkg success=$success")
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
        Log.i(TAG, "[2A] Proxy onDestroy")
        finished.set(true)
        scope.cancel()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(packageReceiver) }
            receiverRegistered = false
        }
        installEvents.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ABP"

        /** Extra set by the module when redirecting to this proxy. */
        const val EXTRA_ABP_INSTALLER = "wonton.abp.extra.INSTALLER"
        const val EXTRA_ABP_CALLER = "wonton.abp.extra.CALLER"

        /** Intent.EXTRA_INSTALL_RESULT (@hide); stable string value. */
        private const val EXTRA_INSTALL_RESULT = "android.intent.extra.INSTALL_RESULT"

        private const val INSTALLER_VIEW_MIME = "application/vnd.android.package-archive"
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 120_000L
    }
}
