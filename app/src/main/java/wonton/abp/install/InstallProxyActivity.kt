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
import kotlinx.coroutines.channels.Channel
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
 * Invisible proxy activity that performs the install hand-off in the app's own
 * process. It is the only place that ever talks to the installer:
 *
 *  - path A: the module (system_server) redirects a selected app's
 *    `ACTION_INSTALL_PACKAGE` intent here, together with the APK URI it granted
 *    us read access to,
 *  - path B: the module intercepted a `PackageInstaller` session commit, copied
 *    the staged APK into our cache through [ApkStageProvider] and starts this
 *    activity with that path.
 *
 * Either way this activity:
 *
 *  1. Makes sure the APK is in our own `cache/apks/` (copying it for path A).
 *  2. Parses the target package name + versionCode from it.
 *  3. Hands it to the configured installer via `ACTION_VIEW` (FileProvider
 *     `content://` URI + explicit read grant) **for result**, so the installer
 *     performs the actual, unrestricted install.
 *  4. Waits until the package is installed, or until the installer's activity
 *     finished - which is how we learn that the user closed the installer
 *     without installing, instead of polling into the timeout.
 *  5. Answers the caller of path A with `Intent.EXTRA_INSTALL_RESULT`, and
 *     reports the outcome to the module for path B, which has no activity
 *     result to look at.
 *
 * It has no UI and is never shown in the launcher / recents.
 */
class InstallProxyActivity : Activity() {

    private val finished = AtomicBoolean(false)

    /** Session id when the module sent us here to finish a session install (path B). */
    private var sessionId = ModuleLog.NO_SESSION

    /** APK handed to the installer, so its URI grant can be dropped again. */
    private var handedApk: File? = null

    /**
     * Fires when the installer's activity finishes: the flow is over, so the wait
     * in [InstallHandoff.awaitInstalled] can stop instead of running into its
     * timeout. Conflated because the signal only ever carries "it is over".
     */
    private val flowEnded = Channel<Unit>(Channel.CONFLATED)

    /**
     * Scope for the install flow. Cancelled in [onDestroy] so the wait loop can
     * never outlive the activity.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Logs to logcat and forwards the line to the module in system_server, which
     * re-logs it through the Xposed logger. LSPosed only captures logs written
     * from hooked processes, so everything this process does happens between
     * those two sinks: logcat for the app, Xposed log for the module.
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
        sessionId = received.getIntExtra(EXTRA_ABP_SESSION_ID, ModuleLog.NO_SESSION)
        val stagedPath = received.getStringExtra(EXTRA_ABP_APK_PATH)
        log(
            "[proxy] onCreate: caller=${received.getStringExtra(EXTRA_ABP_CALLER)} " +
                "action=${received.action} uri=${received.data} apkPath=$stagedPath " +
                "installerExtra=${received.getStringExtra(EXTRA_ABP_INSTALLER)} " +
                "session=$sessionId " +
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
            log("[proxy] recreated after process death; not re-running the install flow")
            finishFlow(pkg = null, success = false)
            return
        }
        val installer = received.getStringExtra(EXTRA_ABP_INSTALLER)
            ?: readInstallerFromPrefs()
        log("[proxy] resolved: apkPath=$stagedPath apkUri=${received.data} installer=$installer")
        if ((stagedPath == null && received.data == null) || installer.isBlank()) {
            log("[proxy] abort: apkPath=$stagedPath apkUri=${received.data} installer=$installer")
            finishFlow(pkg = null, success = false)
            return
        }

        runInstallFlow(stagedPath, received.data, installer)
    }

    /**
     * Runs the whole install hand-off as a coroutine: the blocking parts (copying
     * the APK, parsing it, querying the package manager) hop to
     * [Dispatchers.IO], while everything touching activity state stays on the
     * main thread.
     *
     * Path A arrives with [apkUri] (readable because the caller granted us access
     * to it); path B arrives with [stagedPath] - the module already copied the
     * intercepted APK into our cache, so there is nothing left to copy.
     */
    private fun runInstallFlow(stagedPath: String?, apkUri: Uri?, installer: String) {
        scope.launch {
            // 1. Make sure the APK sits in our own cache/apks/.
            val copied = withContext(Dispatchers.IO) {
                if (stagedPath != null) stagedApk(stagedPath) else copyToCache(requireNotNull(apkUri))
            }
            log("[proxy] apk ready: $copied")
            if (copied == null) {
                log("[proxy] abort: no readable APK in the cache")
                finishFlow(pkg = null, success = false)
                return@launch
            }

            // 2. Parse target package + versionCode from the APK itself.
            val archive = withContext(Dispatchers.IO) {
                runCatching { packageManager.getPackageArchiveInfo(copied.absolutePath, 0) }
                    .getOrNull()
            }
            val targetPkg = archive?.packageName
            val targetVersion = archive?.longVersionCode ?: 0L
            log("[proxy] parsed apk: pkg=$targetPkg version=$targetVersion")
            if (targetPkg == null) {
                log("[proxy] abort: could not parse APK")
                finishFlow(pkg = null, success = false)
                return@launch
            }

            // 3. Hand the APK to the configured installer, for result: when the
            //    installer's activity finishes, this flow is over - whether it
            //    installed something or the user just left it.
            handedApk = copied
            val launched = InstallHandoff.launchInstaller(
                this@InstallProxyActivity, copied, installer, REQUEST_INSTALLER, ::log
            )
            log("[proxy] launchInstaller($installer) -> $launched")
            if (!launched) {
                finishFlow(targetPkg, success = false)
                return@launch
            }

            // 4. Stay alive (invisible) and wait for the install result. The wait
            //    also watches package broadcasts, so a successful install ends it
            //    right away instead of waiting for the user to dismiss the
            //    installer's success dialog.
            val success = InstallHandoff.awaitInstalled(
                this@InstallProxyActivity, targetPkg, targetVersion, flowEnded, ::log
            )
            log("[proxy] wait finished: success=$success")
            finishFlow(targetPkg, success)
        }
    }

    private fun readInstallerFromPrefs(): String = runCatching {
        ConfigStore(App.service.value).getInstallerPackage()
    }.getOrElse { Prefs.DEFAULT_INSTALLER }

    private fun copyToCache(uri: Uri): File? = try {
        val apks = File(cacheDir, "apks").apply { mkdirs() }
        // Clear stale copies from previous *URI* runs. Only install-*.apk is
        // touched: session-*.apk files are staged by the module and another flow
        // may still be reading its own.
        apks.listFiles { f -> f.name.startsWith("install-") }
            ?.forEach { runCatching { it.delete() } }
        val out = File(apks, "install-${System.currentTimeMillis()}.apk")
        log("[proxy] copying uri=$uri to ${out.absolutePath}")
        // file:// URIs cannot be opened through the content resolver, so read
        // the path directly as a fallback (best effort; it may be unreadable
        // when it belongs to another app).
        val input = when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile }?.inputStream()
            else -> contentResolver.openInputStream(uri)
        }
        if (input == null) {
            log("[proxy] could not open $uri (no read grant / file not readable?)")
            return null
        }
        input.use { source ->
            FileOutputStream(out).use { target -> source.copyTo(target) }
        }
        log("[proxy] copied ${out.length()} bytes -> ${out.absolutePath}")
        out
    } catch (t: Throwable) {
        log("[proxy] copyToCache failed: ${t.javaClass.simpleName}: ${t.message}", t)
        null
    }

    /**
     * Path B: the module already staged the intercepted APK into `cache/apks/`.
     *
     * Only files from that directory are accepted, because this activity is
     * exported and the extra therefore comes from an untrusted caller.
     */
    private fun stagedApk(path: String): File? = runCatching {
        val apks = File(cacheDir, "apks")
        val file = File(path).canonicalFile
        if (!file.path.startsWith(apks.canonicalPath + File.separator)) {
            log("[proxy] refusing apk path outside ${apks.absolutePath}: $path")
            return null
        }
        if (!file.isFile || file.length() == 0L) {
            log("[proxy] staged apk is missing or empty: $path")
            return null
        }
        log("[proxy] using staged apk ${file.absolutePath} (${file.length()} bytes)")
        file
    }.getOrElse {
        log("[proxy] stagedApk($path) failed: ${it.javaClass.simpleName}: ${it.message}")
        null
    }

    /**
     * Ends the flow. The caller of path A gets the synthesized result it waits
     * for; when this run belongs to a session the module intercepted (path B),
     * the outcome is reported back to system_server, which has no activity result
     * to look at.
     */
    private fun finishFlow(pkg: String?, success: Boolean) {
        if (!finished.compareAndSet(false, true)) {
            log("[proxy] finishFlow skipped (already finishing)")
            return
        }
        log("[proxy] finishFlow: pkg=$pkg success=$success session=$sessionId")
        reportToModule(success, pkg)
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

    private fun reportToModule(success: Boolean, pkg: String?) {
        if (sessionId == ModuleLog.NO_SESSION) return
        runCatching {
            sendBroadcast(
                Intent(ModuleLog.ACTION)
                    .putExtra(ModuleLog.EXTRA_SESSION_ID, sessionId)
                    .putExtra(ModuleLog.EXTRA_RESULT_OK, success)
                    .putExtra(
                        ModuleLog.EXTRA_RESULT_MESSAGE,
                        if (success) "Success" else "installer closed without installing $pkg"
                    )
            )
        }.onFailure {
            log("[proxy] reportToModule failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * The installer's activity finished, so the flow it was part of is over.
     *
     * This is the only reliable sign that the user left the installer without
     * installing anything: there is no package broadcast for that, and waiting
     * for the timeout would keep the caller (and, on path B, the app that
     * requested the install) hanging for minutes.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_INSTALLER) return
        log("[proxy] installer finished: resultCode=$resultCode -> stop waiting")
        flowEnded.trySend(Unit)
    }

    override fun onDestroy() {
        // Do NOT delete the staged APK here: the installer may still be reading
        // it through the FileProvider while the install is in flight. Stale
        // files are cleaned up by the next run instead.
        log("[proxy] onDestroy")
        finished.set(true)
        scope.cancel()
        flowEnded.close()
        handedApk?.let { InstallHandoff.revokeRead(this, it, ::log) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ABP"

        /**
         * Request code for the installer's activity: its result is how we learn
         * that the user closed the installer, installed or not.
         */
        private const val REQUEST_INSTALLER = 0x4150

        /** Extra set by the module when redirecting to this proxy. */
        const val EXTRA_ABP_INSTALLER = "wonton.abp.extra.INSTALLER"
        const val EXTRA_ABP_CALLER = "wonton.abp.extra.CALLER"

        /**
         * Path of an APK the module already staged into our cache. Set instead of
         * a `data` URI when the module intercepted a session install (path B).
         */
        const val EXTRA_ABP_APK_PATH = "wonton.abp.extra.APK_PATH"

        /**
         * Session id of the intercepted install, so this activity can report the
         * outcome back to the module.
         */
        const val EXTRA_ABP_SESSION_ID = "wonton.abp.extra.SESSION_ID"

        /** Intent.EXTRA_INSTALL_RESULT (@hide); stable string value. */
        private const val EXTRA_INSTALL_RESULT = "android.intent.extra.INSTALL_RESULT"
    }
}
