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
        // The module (system_server) stages APKs into our cache; make sure the
        // cache subtree is traversable/writable for it. This also persists.
        ensureCacheAccessible()

        val intent = intent
        val apkUri = intent.data
        val installer = intent.getStringExtra(EXTRA_ABP_INSTALLER)
            ?: readInstallerFromPrefs()
        if (apkUri == null || installer.isBlank()) {
            finishWithResult(pkg = null, success = false)
            return
        }

        // 1. Copy the APK into our own cache (we hold the caller's read grant).
        val copied = copyToCache(apkUri)
        if (copied == null) {
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
        if (targetPkg == null) {
            finishWithResult(pkg = null, success = false)
            return
        }

        // 3. Hand the APK to the configured installer.
        if (!launchInstaller(copied, installer)) {
            finishWithResult(targetPkg, success = false)
            return
        }

        // 4. Stay alive (invisible) and poll for the install result.
        pollThread = Thread {
            val success = pollUntilInstalled(targetPkg, targetVersion)
            runOnUiThread { finishWithResult(targetPkg, success) }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun ensureCacheAccessible() {
        runCatching {
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
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: return null
        out
    } catch (t: Throwable) {
        Log.w(TAG, "copyToCache failed", t)
        null
    }

    private fun launchInstaller(apk: File, installer: String): Boolean = try {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, INSTALLER_VIEW_MIME)
            .setPackage(installer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "launchInstaller failed for $installer", t)
        false
    }

    private fun pollUntilInstalled(pkg: String, targetVersion: Long): Boolean {
        val pm = packageManager
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (finished.get()) return false
            Thread.sleep(POLL_INTERVAL_MS)
            val info = try {
                pm.getPackageInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            if (info != null && (targetVersion == 0L || info.longVersionCode >= targetVersion)) {
                return true
            }
        }
        return false
    }

    private fun finishWithResult(pkg: String?, success: Boolean) {
        if (!finished.compareAndSet(false, true)) return
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
