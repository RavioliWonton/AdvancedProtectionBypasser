package wonton.abp.install

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * The install hand-off, always executed by [InstallProxyActivity] in the app's
 * own process. Both entry points of the module converge here: path A arrives
 * with an `ACTION_INSTALL_PACKAGE` intent the module redirected to the proxy,
 * path B with the APK the module staged after intercepting a
 * `PackageInstaller` session commit.
 *
 * The two things both paths do: hand the APK in `cache/apks/` to the configured
 * installer through our FileProvider, then wait until the package shows up in
 * `PackageManager` - or until the installer's UI is gone, which means the user
 * left it without installing.
 *
 * The installer is always started by *this* app, never by system_server: the
 * FileProvider is not exported, so only its owner may hand its URIs out. A read
 * grant attached to an intent that system_server starts never reaches the
 * installer, and the installer then only reports a faceless "there was a problem
 * parsing the package".
 */
object InstallHandoff {

    private const val TAG = "ABP"

    /** Authority of the FileProvider that serves the staged APKs. */
    const val FILEPROVIDER_AUTHORITY = "wonton.abp.fileprovider"

    private const val INSTALLER_VIEW_MIME = "application/vnd.android.package-archive"
    private const val POLL_INTERVAL_MS = 2_000L

    /** Passed as `requestCode` when the caller does not want an activity result. */
    const val NO_REQUEST = -1

    /**
     * How long to keep checking `PackageManager` after the installer's UI closed:
     * the package broadcast and the activity result can arrive in either order.
     */
    private const val FLOW_END_GRACE_TICKS = 6
    private const val FLOW_END_GRACE_INTERVAL_MS = 500L

    /** Default sink: logcat only. Callers inside a hooked process pass their own. */
    private val LOGCAT: (String) -> Unit = { Log.i(TAG, it) }

    /** Overall wait budget for an install, shared by both paths. */
    const val POLL_TIMEOUT_MS = 120_000L

    /** The `content://` URI the installer receives for the APK at [apk]. */
    fun apkUri(apk: File): Uri =
        Uri.parse("content://$FILEPROVIDER_AUTHORITY/apks/${apk.name}")

    /**
     * Hands the APK at [apk] to [installer].
     *
     * [apk] must live in the app's `cache/apks/` so the FileProvider can serve
     * it. The read grant is issued *explicitly* on top of
     * `FLAG_GRANT_READ_URI_PERMISSION`, because a grant that travels with an
     * intent is only applied by the activity starter while an explicit grant
     * always lands - and because this app is the provider's owner, it is the only
     * one allowed to hand these URIs out at all.
     *
     * When [requestCode] is not [NO_REQUEST] the installer is started **for
     * result**, which is what tells the caller that the user closed the installer
     * without installing anything. That is why `FLAG_ACTIVITY_NEW_TASK` is only
     * added when the context is not an activity: it would break result delivery.
     */
    fun launchInstaller(
        context: Context,
        apk: File,
        installer: String,
        requestCode: Int = NO_REQUEST,
        log: (String) -> Unit = LOGCAT,
    ): Boolean = try {
        val uri = apkUri(apk)
        val granted = grantRead(context, uri, installer, log)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, INSTALLER_VIEW_MIME)
            .setPackage(installer)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val forResult = requestCode != NO_REQUEST && context is Activity
        if (!forResult) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved =
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        log(
            "launchInstaller: installer=$installer uri=$uri granted=$granted " +
                "forResult=$forResult resolved=$resolved"
        )
        if (resolved == null) {
            log("installer $installer has no activity for ACTION_VIEW($INSTALLER_VIEW_MIME)")
            return false
        }
        if (forResult) {
            context.startActivityForResult(intent, requestCode)
        } else {
            context.startActivity(intent)
        }
        true
    } catch (t: Throwable) {
        log("launchInstaller failed for $installer: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * Grants [installer] read access to [uri] and reports whether
     * `UriGrantsManager` actually honors it.
     *
     * The read-back is logged on purpose: when the installer cannot read the APK
     * it only shows an opaque "problem parsing the package" dialog, so this is
     * the only place where the real reason is visible.
     */
    fun grantRead(
        context: Context,
        uri: Uri,
        installer: String,
        log: (String) -> Unit = LOGCAT,
    ): Boolean {
        val uid = runCatching {
            context.packageManager.getApplicationInfo(installer, 0).uid
        }.getOrNull()
        if (uid == null) {
            log("grantRead: $installer is not installed")
            return false
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { context.grantUriPermission(installer, uri, flags) }.onFailure {
            log("grantRead: grantUriPermission($installer) failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        val readable = runCatching {
            context.checkUriPermission(uri, Process.myPid(), uid, flags) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        log("grantRead: installer=$installer uid=$uid readable=$readable uri=$uri")
        return readable
    }

    /** Drops the grant [grantRead] handed out; grants are not reference counted. */
    fun revokeRead(context: Context, apk: File, log: (String) -> Unit = LOGCAT) {
        runCatching {
            context.revokeUriPermission(apkUri(apk), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            log("revokeRead: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * Waits until [pkg] is installed at [targetVersion] (a [targetVersion] of 0
     * matches any version).
     *
     * Three things end the wait:
     *  - the package appears in `PackageManager` (checked every tick and
     *    immediately when a package broadcast arrives),
     *  - [endSignal] fires - the caller does that when the installer's activity
     *    finished, so there is nothing left to wait for and the answer is "not
     *    installed" unless the package still shows up during the grace period,
     *  - [POLL_TIMEOUT_MS] elapses, for installers that never report back.
     */
    suspend fun awaitInstalled(
        context: Context,
        pkg: String,
        targetVersion: Long,
        endSignal: Channel<Unit>? = null,
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
                if (endSignal?.tryReceive()?.isSuccess == true) {
                    return confirmAfterFlowEnd(context, pkg, targetVersion, log)
                }
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

    /**
     * The installer's UI is gone: the user either installed the package or closed
     * the installer. `PackageManager` may lag the activity result, so keep
     * looking for a moment before calling it a failure.
     */
    private suspend fun confirmAfterFlowEnd(
        context: Context,
        pkg: String,
        targetVersion: Long,
        log: (String) -> Unit,
    ): Boolean {
        log("installer flow ended; re-checking $pkg")
        repeat(FLOW_END_GRACE_TICKS) {
            if (isInstalled(context, pkg, targetVersion)) return true
            delay(FLOW_END_GRACE_INTERVAL_MS.milliseconds)
        }
        log("installer flow ended without installing $pkg")
        return false
    }

    private suspend fun isInstalled(context: Context, pkg: String, targetVersion: Long): Boolean {
        val info = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        }
        val current = info?.longVersionCode
        return info != null && (targetVersion == 0L || current != null && current >= targetVersion)
    }
}
