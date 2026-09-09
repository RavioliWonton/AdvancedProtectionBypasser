package wonton.abp.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The install hand-off shared by both entry points of the module:
 *
 *  - [InstallProxyActivity] (path A) runs it in the app's own process after the
 *    module redirected an `ACTION_INSTALL_PACKAGE` intent to it, and
 *  - `XposedEntry.proxySessionInstall` (path B) runs it from system_server after
 *    intercepting a `PackageInstaller` session commit.
 *
 * Both end up doing the same two things: hand a staged APK from `cache/apks/` to
 * the configured installer through our FileProvider, then wait until the package
 * shows up in `PackageManager`.
 */
object InstallHandoff {

    private const val TAG = "ABP"
    private const val FILEPROVIDER_AUTHORITY = "wonton.abp.fileprovider"
    private const val INSTALLER_VIEW_MIME = "application/vnd.android.package-archive"
    private const val POLL_INTERVAL_MS = 2_000L

    /** Default sink: logcat only. Callers inside a hooked process pass their own. */
    private val LOGCAT: (String) -> Unit = { Log.i(TAG, it) }

    /** Overall wait budget for an install, shared by both paths. */
    const val POLL_TIMEOUT_MS = 120_000L

    /**
     * Hands the APK at [apk] to [installer] via `ACTION_VIEW`.
     *
     * [apk] must live in the app's `cache/apks/` so the FileProvider can serve
     * it. Works from an Activity context (path A) and from system_server's
     * context (path B); `FLAG_ACTIVITY_NEW_TASK` is required in the latter and
     * harmless in the former.
     *
     * [log] receives progress lines; the module passes a logger that forwards
     * them to the Xposed log.
     */
    fun launchInstaller(
        context: Context,
        apk: File,
        installer: String,
        log: (String) -> Unit = LOGCAT,
    ): Boolean = try {
        val uri = Uri.parse("content://$FILEPROVIDER_AUTHORITY/apks/${apk.name}")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, INSTALLER_VIEW_MIME)
            .setPackage(installer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resolved =
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        log("launchInstaller: installer=$installer uri=$uri resolved=$resolved")
        if (resolved == null) {
            log("installer $installer has no activity for ACTION_VIEW($INSTALLER_VIEW_MIME)")
            return false
        }
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        log("launchInstaller failed for $installer: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * Waits until [pkg] is installed at [targetVersion] (a [targetVersion] of 0
     * matches any version), or [POLL_TIMEOUT_MS] elapses.
     *
     * The wait is event driven: a package add/replace broadcast wakes it up
     * immediately, and a [POLL_INTERVAL_MS] tick is the safety net for signals
     * that never arrive (the install finished before the receiver was
     * registered, the broadcast was not delivered, ...), so the worst case is
     * the same as plain polling.
     */
    suspend fun awaitInstalled(
        context: Context,
        pkg: String,
        targetVersion: Long,
        log: (String) -> Unit = LOGCAT,
    ): Boolean {
        val events = Channel<Unit>(Channel.CONFLATED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                log(
                    "package event: action=${intent?.action} " +
                        "pkg=${intent?.data?.schemeSpecificPart}"
                )
                events.trySend(Unit)
            }
        }
        // These are protected system broadcasts, so NOT_EXPORTED still delivers
        // them (other apps cannot spoof them either) while satisfying the
        // mandatory flag on targetSdk 34+. Registration may still fail for a
        // context that cannot register receivers; the tick keeps the wait alive.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            true
        }.getOrElse {
            log("package receiver registration failed; falling back to polling: " +
                "${it.javaClass.simpleName}: ${it.message}")
            false
        }
        try {
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            var i = 0
            while (System.currentTimeMillis() < deadline) {
                if (isInstalled(context, pkg, targetVersion)) return true
                log("wait #${i++}: not installed yet")
                // receiveCatching() makes a closed channel non-fatal.
                withTimeoutOrNull(POLL_INTERVAL_MS) { events.receiveCatching() }
            }
            log("wait timed out for $pkg")
            return false
        } finally {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            events.close()
        }
    }

    private suspend fun isInstalled(context: Context, pkg: String, targetVersion: Long): Boolean {
        val info = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        }
        val current = info?.longVersionCode
        return info != null && (targetVersion == 0L || current != null && current >= targetVersion)
    }
}
