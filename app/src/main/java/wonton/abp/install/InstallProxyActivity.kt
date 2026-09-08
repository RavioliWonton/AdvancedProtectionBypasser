package wonton.abp.install

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
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
    private val copiedFiles = mutableListOf<File>()
    private var pollThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val received = intent
        Log.i(
            TAG, "[2A] Proxy onCreate: caller=${received.getStringExtra(EXTRA_ABP_CALLER)} " +
                "action=${received.action} uri=${received.data} " +
                "installerExtra=${received.getStringExtra(EXTRA_ABP_INSTALLER)} " +
                "returnResult=${received.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)} " +
                "extraPkg=${received.getStringExtra(Intent.EXTRA_PACKAGE_NAME)}"
        )
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

        // 1. Copy the APK into our own cache (we hold the caller's read grant).
        val copied = copyToCache(apkUri)
        Log.i(TAG, "[2A] copyToCache -> $copied")
        if (copied == null) {
            Log.w(TAG, "[2A] abort: failed to copy APK")
            finishWithResult(pkg = null, success = false)
            return
        }
        copiedFiles += copied

        // 2. Parse target package + versionCode from the APK itself.
        val archive = runCatching {
            packageManager.getPackageArchiveInfo(copied.absolutePath, 0)
        }.getOrNull()
        val targetPkg = archive?.packageName
        val targetVersion = archive?.longVersionCode ?: 0L
        Log.i(TAG, "[2A] parsed apk: pkg=$targetPkg version=$targetVersion")
        if (targetPkg == null) {
            Log.w(TAG, "[2A] abort: could not parse APK")
            finishWithResult(pkg = null, success = false)
            return
        }

        // 3. Hand the APK to the configured installer.
        val launched = launchInstaller(copied, installer)
        Log.i(TAG, "[2A] launchInstaller($installer) -> $launched")
        if (!launched) {
            finishWithResult(targetPkg, success = false)
            return
        }

        // 4. Stay alive (invisible) and poll for the install result.
        pollThread = Thread {
            val success = pollUntilInstalled(targetPkg, targetVersion)
            Log.i(TAG, "[2A] poll finished: success=$success")
            runOnUiThread { finishWithResult(targetPkg, success) }
        }.also {
            it.isDaemon = true
            it.start()
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
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: run {
            Log.w(TAG, "[2A] openInputStream returned null for $uri (no read grant?)")
            return null
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

    private fun pollUntilInstalled(pkg: String, targetVersion: Long): Boolean {
        val pm = packageManager
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var i = 0
        while (System.currentTimeMillis() < deadline) {
            if (finished.get()) {
                Log.i(TAG, "[2A] poll aborted (activity finished)")
                return false
            }
            Thread.sleep(POLL_INTERVAL_MS)
            val info = try {
                pm.getPackageInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val current = info?.longVersionCode
            Log.i(
                TAG,
                "[2A] poll #${i++}: installed=${info != null} currentVersion=$current targetVersion=$targetVersion"
            )
            if (info != null && (targetVersion == 0L || current != null && current >= targetVersion)) {
                return true
            }
        }
        Log.i(TAG, "[2A] poll timed out for $pkg")
        return false
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
        Log.i(TAG, "[2A] Proxy onDestroy (cleaning ${copiedFiles.size} staged file(s))")
        finished.set(true)
        pollThread?.interrupt()
        for (f in copiedFiles) runCatching { f.delete() }
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
