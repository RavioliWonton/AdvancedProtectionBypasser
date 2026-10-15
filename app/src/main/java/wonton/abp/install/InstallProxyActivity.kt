package wonton.abp.install

import android.app.Activity
import android.content.Intent
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

    /** When the installer was started, so an activity result can be timed. */
    private var handedOverAt = 0L

    /**
     * Fires when the installer's activity finishes, carrying how it ended: how
     * long it lived and the outcome the installer reported, if any. Conflated
     * because only the first ending matters.
     */
    private val flowEnded = Channel<InstallHandoff.FlowEnd>(Channel.CONFLATED)

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
            // 0. Reap what past flows had to leave behind (see finishFlow): an APK
            //    older than any install can still need it is not in use, and this
            //    flow is about to add its own.
            withContext(Dispatchers.IO) {
                val purge = InstallHandoff.purgeStaleApks(this@InstallProxyActivity)
                if (purge.total > 0) log("[proxy] reaped stale apks: ${purge.message}")
            }

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
            // Owned from here on: whatever ends this flow also cleans it up.
            handedApk = copied

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

            // Give the temp file a name that says which package it holds. Path B
            // already got that name from ApkStageProvider.
            val apk = withContext(Dispatchers.IO) {
                if (stagedPath == null) {
                    InstallHandoff.renameTempApkWithPackage(copied, targetPkg)
                } else {
                    copied
                }
            }
            handedApk = apk

            // 3. Hand the APK to the configured installer, for result: installers
            //    that honour the install-for-result contract report the real
            //    outcome, and for the rest the activity result is only a hint - it
            //    is cancelled at launch for singleInstance/singleTask targets, and
            //    trampoline installers finish before the install even starts.
            handedOverAt = System.currentTimeMillis()
            val launched = InstallHandoff.launchInstaller(
                this@InstallProxyActivity, apk, installer, REQUEST_INSTALLER, ::log
            )
            log("[proxy] launchInstaller($installer) -> $launched")
            if (!launched) {
                finishFlow(targetPkg, success = false)
                return@launch
            }

            // 4. Stay alive (invisible) and wait for the install result: the
            //    package broadcast ends the wait right away, so a successful
            //    install does not wait for the user to dismiss the installer's
            //    success dialog, and an installer that installs in the background
            //    is still caught.
            val result = InstallHandoff.awaitInstalled(
                this@InstallProxyActivity, targetPkg, targetVersion, flowEnded, ::log
            )
            log("[proxy] wait finished: $result")
            when (result) {
                InstallHandoff.WaitResult.INSTALLED ->
                    finishFlow(targetPkg, success = true)

                InstallHandoff.WaitResult.FAILED ->
                    // The installer told us it failed, so it is done with the APK.
                    finishFlow(targetPkg, success = false)

                InstallHandoff.WaitResult.NOT_INSTALLED -> {
                    // Its UI is gone, but installers that work from a background
                    // task (a second activity, a Shizuku/root session) may still be
                    // reading the file, so it is kept and reaped by age later.
                    log("[proxy] keeping the APK: the installer may still be reading it")
                    finishFlow(targetPkg, success = false, keepApk = true)
                }

                InstallHandoff.WaitResult.TIMED_OUT -> {
                    // The installer is still open and the user may yet confirm it,
                    // so this flow must not delete the APK it is serving.
                    log("[proxy] installer still open; leaving the APK in place")
                    finishFlow(targetPkg, success = false, keepApk = true)
                }
            }
        }
    }

    private fun readInstallerFromPrefs(): String = runCatching {
        ConfigStore(App.service.value).getInstallerPackage()
    }.getOrElse { Prefs.DEFAULT_INSTALLER }

    private fun copyToCache(uri: Uri): File? = try {
        val apks = File(cacheDir, "apks").apply { mkdirs() }
        val out = File(apks, InstallHandoff.tempApkName("install", packageName = null))
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
     *
     * The APK is deleted when the install is confirmed, and when the installer
     * reported a failure of its own - in both cases it is done with the file.
     * [keepApk] covers the endings where that cannot be known: the installer is
     * still open, or it closed its UI while another of its components may still be
     * reading the APK. Those leftovers are reaped by age (see
     * [InstallHandoff.purgeStaleApks]), never by deleting a file an install may
     * still need.
     */
    private fun finishFlow(pkg: String?, success: Boolean, keepApk: Boolean = false) {
        if (!finished.compareAndSet(false, true)) {
            log("[proxy] finishFlow skipped (already finishing)")
            return
        }
        log("[proxy] finishFlow: pkg=$pkg success=$success session=$sessionId keepApk=$keepApk")
        reportToModule(success, pkg)
        if (!keepApk) deleteHandedApk()
        val data = Intent().apply {
            // Reported in the platform's convention for install-for-result: the
            // result code carries success, EXTRA_INSTALL_RESULT carries
            // PackageManager's code (see InstallHandoff.INSTALL_RESULT_SUCCEEDED).
            // An app that follows the documented contract reads both, and would
            // read PackageInstaller.STATUS_* values backwards.
            putExtra(
                InstallHandoff.INSTALL_RESULT_EXTRA,
                if (success) InstallHandoff.INSTALL_RESULT_SUCCEEDED
                else InstallHandoff.INSTALL_RESULT_ABORTED
            )
            if (pkg != null) putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
        }
        setResult(if (success) RESULT_OK else RESULT_FIRST_USER, data)
        finish()
    }

    /** Deletes the APK this flow handed to the installer, grant included. */
    private fun deleteHandedApk() {
        val apk = handedApk ?: return
        val deleted = runCatching { apk.delete() }.getOrDefault(false)
        log("[proxy] apk ${if (deleted) "deleted" else "could not be deleted"}: $apk")
        // The grant goes with the file: a permission on a URI that no longer
        // resolves only keeps the installer's uid in way too many grant tables.
        InstallHandoff.revokeRead(this, apk, ::log)
        handedApk = null
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
     * The installer's activity finished. Its result is not the flow's verdict by
     * itself: with a `singleInstance`/`singleTask` installer the system cancels it
     * the moment the activity is started, and a trampoline installer finishes its
     * activity to continue in another one. [InstallHandoff.awaitInstalled] knows
     * both cases and decides what the timing and the reported code are worth.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_INSTALLER) return
        val elapsedMs = if (handedOverAt == 0L) 0L else System.currentTimeMillis() - handedOverAt
        // A code the installer did not set stays unset (Int.MIN_VALUE sentinel).
        val reported = data?.getIntExtra(InstallHandoff.INSTALL_RESULT_EXTRA, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        log(
            "[proxy] installer activity finished: resultCode=$resultCode " +
                "installResult=$reported elapsed=${elapsedMs}ms"
        )
        flowEnded.trySend(InstallHandoff.FlowEnd(elapsedMs, reported))
    }

    override fun onDestroy() {
        // Deliberate endings delete their APK - and its grant - in finishFlow(). A
        // flow that dies with the process leaves both behind on purpose: an
        // installer that already holds the URI may still be reading it, so the file
        // is reaped by age on the next flow instead.
        log("[proxy] onDestroy")
        finished.set(true)
        scope.cancel()
        flowEnded.close()
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
    }
}
