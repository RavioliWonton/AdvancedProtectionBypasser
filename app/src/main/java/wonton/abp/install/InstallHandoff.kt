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
import wonton.abp.common.ModuleLog
import java.io.File
import kotlin.random.Random

/**
 * The install hand-off, always executed by [InstallProxyActivity] in the app's
 * own process. Both entry points of the module converge here: path A arrives
 * with an `ACTION_INSTALL_PACKAGE` intent the module redirected to the proxy,
 * path B with the APK the module staged after intercepting a
 * `PackageInstaller` session commit.
 *
 * The two things both paths do: hand the APK in `cache/apks/` to the configured
 * installer through our FileProvider, then wait until the package shows up in
 * `PackageManager`.
 *
 * The wait deliberately does *not* trust the installer's activity alone. The three
 * installer families in the wild behave very differently:
 *
 *  - the single-activity installers ([moe.shizuku.installer] and friends) stay
 *    open until the install is done, so their activity result is a real signal,
 *  - installers whose activity is `singleInstance`/`singleTask` (InstallerX,
 *    InstallWithOptions, the Flutter Shizuku installers) get their activity
 *    result cancelled by the system the moment we start them, so a result that
 *    arrives immediately means nothing at all,
 *  - installers built as a trampoline (Universal Installer's
 *    `DialogInstallActivity`) finish their activity and hand the URI to a second
 *    activity in another task, which then reads the APK we already gave them.
 *
 * Because of the last two, an APK is only deleted when its install is confirmed,
 * and leftovers are reaped by age instead (see [purgeStaleApks]) - an installer
 * that still holds the URI would otherwise fail on a file we removed underneath it.
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
     * An installer activity that ends this quickly cannot have been an install
     * flow: the system cancels the activity result of a `singleInstance` /
     * `singleTask` target the moment it is started (possibly after a slow cold
     * start), and trampoline activities finish right after forwarding the URI to
     * the component that really installs. Such an end is ignored, and the wait
     * continues as if nothing had happened.
     *
     * The trade-off is deliberate: a user who really cancels within these seconds
     * is not believed either, and the caller waits out the budget instead - an
     * abandoned wait costs time, while a wrong verdict costs the install.
     */
    private const val MIN_TRUSTED_FLOW_MS = 4_000L

    /** How long to keep watching `PackageManager` after an installer reported its own outcome. */
    private const val RESULT_GRACE_MS = 5_000L

    /**
     * How long to keep watching `PackageManager` after the installer's UI is gone
     * without telling us anything. Installers that install from a background
     * task (Shizuku/root sessions, a second activity) need this: their UI may be
     * closed long before the package lands.
     */
    private const val FLOW_END_EXTRA_MS = 20_000L

    /** Default sink: logcat only. Callers inside a hooked process pass their own. */
    private val LOGCAT: (String) -> Unit = { Log.i(TAG, it) }

    /** Overall wait budget for an install, shared by both paths. */
    const val POLL_TIMEOUT_MS = 120_000L

    /**
     * Age after which a staged APK no longer counts as in use.
     *
     * A flow deletes its APK when the install is confirmed; when it cannot tell -
     * the installer may still be reading the file - it leaves it behind, and this
     * is what eventually reclaims it (see [purgeStaleApks]).
     */
    const val STALE_APK_AGE_MS = 30 * 60_000L

    /** `Intent.EXTRA_INSTALL_RESULT` (@hide in the platform): stable string value. */
    const val INSTALL_RESULT_EXTRA = "android.intent.extra.INSTALL_RESULT"

    /**
     * `PackageManager.INSTALL_SUCCEEDED`.
     *
     * Note the convention: the platform reports an install through
     * [INSTALL_RESULT_EXTRA] with *PackageManager* codes - success is 1 and a
     * failure is a negative `INSTALL_FAILED_*` (see AOSP's
     * `PackageInstallerActivity`). `PackageInstaller.STATUS_*` looks similar but
     * says the opposite for 1, so an app that reads the result the documented way
     * would read it backwards if we answered in that convention.
     */
    const val INSTALL_RESULT_SUCCEEDED = 1

    /** `PackageManager.INSTALL_FAILED_ABORTED`: the user cancelled, nothing was installed. */
    const val INSTALL_RESULT_ABORTED = -115

    /**
     * How the installer's activity ended.
     *
     * [elapsedMs] is measured from the moment the APK was handed over and is what
     * tells a real ending apart from a launch that never became a flow (see
     * [MIN_TRUSTED_FLOW_MS]).
     *
     * [installResult] is [INSTALL_RESULT_EXTRA] as the installer set it, or null
     * when it set nothing. Installers that honour `Intent.EXTRA_RETURN_RESULT`
     * (InstallerX-Revived does) put the real outcome there, which is worth far
     * more than "my activity finished".
     */
    data class FlowEnd(val elapsedMs: Long, val installResult: Int?)

    /** How [awaitInstalled] ended. */
    enum class WaitResult {
        /** The package is installed at the expected version. */
        INSTALLED,

        /**
         * The installer reported a failure code through [INSTALL_RESULT_EXTRA].
         * It is done with the APK, whoever else may still be running.
         */
        FAILED,

        /**
         * The installer's UI is gone and the package did not land. This is a
         * failure for the caller, but *not* proof that nothing will happen: an
         * installer can install from a background task it started, so the APK is
         * kept and reaped by age instead of deleted here.
         */
        NOT_INSTALLED,

        /**
         * The budget ran out while the installer was still open. The user may yet
         * confirm it, so the APK must be kept until it does.
         */
        TIMED_OUT,
    }

    /** The `content://` URI the installer receives for the APK at [apk]. */
    fun apkUri(apk: File): Uri =
        Uri.parse("content://$FILEPROVIDER_AUTHORITY/apks/${apk.name}")

    /**
     * Where staged APKs live - the directory the FileProvider serves from.
     *
     * It has to be this app's own cache: the module runs in system_server and
     * SELinux denies it writes into another app's data directory, which is why
     * [ApkStageProvider] does the copying in this process.
     */
    fun apkDir(context: Context): File = File(context.cacheDir, "apks")

    /** File-name prefix shared by the staged copies of one install session. */
    fun sessionPrefix(sessionId: Int): String = "session-$sessionId-"

    /** What [purgeApks] removed. */
    data class Purge(val deleted: Int, val total: Int, val bytes: Long) {
        val ok: Boolean get() = deleted == total

        val message: String get() = "$deleted/$total files ($bytes bytes)"
    }

    /**
     * Deletes staged APKs.
     *
     * [sessionId] narrows the deletion to the copies made for one intercepted
     * session (their names start with [sessionPrefix]), so cleaning up after an
     * install cannot take the APK of one that is still running. Passing
     * [ModuleLog.NO_SESSION] empties the directory, which is only safe when no
     * flow can be running: a fresh process, or a module start.
     */
    fun purgeApks(context: Context, sessionId: Int = ModuleLog.NO_SESSION): Purge =
        purge(context) { file ->
            sessionId == ModuleLog.NO_SESSION || file.name.startsWith(sessionPrefix(sessionId))
        }

    /**
     * Deletes staged APKs no flow can still be using, because they are older than
     * [olderThanMs].
     *
     * This is the backstop for the endings where a flow deliberately keeps its APK
     * (see [WaitResult.NOT_INSTALLED]): it runs at the start of the next flow, so
     * leftovers never pile up while a *live* APK - always much younger than the
     * cutoff - is never touched.
     */
    fun purgeStaleApks(
        context: Context,
        olderThanMs: Long = STALE_APK_AGE_MS,
    ): Purge = purge(context) { file ->
        System.currentTimeMillis() - file.lastModified() >= olderThanMs
    }

    private fun purge(context: Context, doomed: (File) -> Boolean): Purge {
        val files = apkDir(context).listFiles().orEmpty().toList().filter(doomed)
        val bytes = files.sumOf { it.length() }
        val deleted = files.count { runCatching { it.delete() }.getOrDefault(false) }
        return Purge(deleted, files.size, bytes)
    }

    /** Random token that keeps the names of two temp APKs apart. */
    fun tempId(): String = Random.nextInt(0x100000, 0x1000000).toString(16)

    /**
     * Short, file-name-safe hint of [packageName]: its last segment, so
     * `com.example.foo` becomes `foo`.
     */
    fun packageAbbrev(packageName: String?): String {
        val last = packageName?.substringAfterLast('.')?.filter { it.isLetterOrDigit() }
        return last?.lowercase()?.take(12)?.takeIf { it.isNotEmpty() } ?: "apk"
    }

    /**
     * Name for one of our temporary APKs: what it belongs to ([prefix] is
     * `session` for an intercepted install session and `install` for a
     * redirected one), the session id when there is one, a hint of the package
     * and a random token - unique per run and still readable in the Xposed log.
     */
    fun tempApkName(
        prefix: String,
        packageName: String?,
        sessionId: Int = ModuleLog.NO_SESSION,
        id: String = tempId(),
    ): String = buildList {
        add(prefix)
        if (sessionId != ModuleLog.NO_SESSION) add(sessionId.toString())
        if (packageName != null) add(packageAbbrev(packageName))
        add(id)
    }.joinToString("-") + ".apk"

    /**
     * Adds a hint of [packageName] to an existing temp APK name, for the flow
     * that only learns the package after copying the file. Answers [file]
     * unchanged when there is nothing to do.
     */
    fun renameTempApkWithPackage(file: File, packageName: String?): File {
        if (packageName == null) return file
        val base = file.name.removeSuffix(".apk")
        val renamed = File(file.parentFile, "$base-${packageAbbrev(packageName)}.apk")
        return if (runCatching { file.renameTo(renamed) }.getOrDefault(false)) renamed else file
    }

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
     * The intents it tries for the installer - an `ACTION_VIEW` one first, because
     * that is what the install apps in the wild declare, and the platform's own
     * `ACTION_INSTALL_PACKAGE` as a fallback - are built in [installIntent], see
     * [INSTALL_ACTIONS].
     *
     * When [requestCode] is not [NO_REQUEST] the installer is started **for
     * result**, which is what tells the caller that the user closed the installer
     * without installing anything - but only when the installer's launch mode
     * allows a result at all, see [MIN_TRUSTED_FLOW_MS].
     */
    fun launchInstaller(
        context: Context,
        apk: File,
        installer: String,
        requestCode: Int = NO_REQUEST,
        log: (String) -> Unit = LOGCAT,
    ): Boolean = runCatching {
        val uri = apkUri(apk)
        val granted = grantRead(context, uri, installer, log)
        val forResult = requestCode != NO_REQUEST && context is Activity
        // Every candidate is resolved, so the log says what each action was worth,
        // and the first one an installer answers is the one that gets started.
        val chosen = INSTALL_ACTIONS
            .mapNotNull { action ->
                val intent = installIntent(action, uri, installer, forResult)
                val target = context.packageManager
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.name
                log(
                    "launchInstaller: installer=$installer action=$action uri=$uri " +
                        "granted=$granted forResult=$forResult resolved=$target"
                )
                intent.takeIf { target != null }
            }
            .firstOrNull()
        if (chosen == null) {
            log(
                "installer $installer answers none of $INSTALL_ACTIONS " +
                    "for $INSTALLER_VIEW_MIME"
            )
            return@runCatching false
        }
        when {
            forResult -> context.startActivityForResult(chosen, requestCode)
            else -> context.startActivity(chosen)
        }
        true
    }.getOrElse { t ->
        log("launchInstaller failed for $installer: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * The actions an installer is asked with, in the order they are tried.
     *
     * `ACTION_VIEW` is what the install apps in the wild declare. The platform
     * deprecated `ACTION_INSTALL_PACKAGE` (Android 17), but some installers still
     * answer nothing else, and we only ever hand it to another app - we never
     * install anything ourselves - so it stays as the fallback.
     */
    @Suppress("DEPRECATION")
    private val INSTALL_ACTIONS = listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE)

    /**
     * One candidate for handing [uri] to [installer].
     *
     * `Intent.EXTRA_RETURN_RESULT` is set so an installer that honours the
     * install-for-result contract reports the real outcome back to us instead of
     * just finishing; the others ignore an extra that the platform's installer
     * also sends. `FLAG_ACTIVITY_NEW_TASK` is only added when the intent cannot be
     * started for result: it would break result delivery.
     */
    private fun installIntent(
        action: String,
        uri: Uri,
        installer: String,
        forResult: Boolean,
    ): Intent = Intent(action)
        .setDataAndType(uri, INSTALLER_VIEW_MIME)
        .setPackage(installer)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        .apply { if (!forResult) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

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
     * What ends the wait:
     *  - the package appears in `PackageManager` (checked every tick and
     *    immediately when a package broadcast arrives) -> [WaitResult.INSTALLED],
     *  - the installer reported its own outcome through [endSignal] and the package
     *    did not show up during the grace that follows -> [WaitResult.FAILED] when
     *    it reported a code, [WaitResult.NOT_INSTALLED] when it reported nothing,
     *  - [POLL_TIMEOUT_MS] elapses -> [WaitResult.TIMED_OUT].
     *
     * An [endSignal] that arrives too early to be an install flow ending is
     * ignored - see [MIN_TRUSTED_FLOW_MS] - and the wait then lasts until the
     * package appears or the budget runs out.
     */
    suspend fun awaitInstalled(
        context: Context,
        pkg: String,
        targetVersion: Long,
        endSignal: Channel<FlowEnd>? = null,
        log: (String) -> Unit = LOGCAT,
    ): WaitResult {
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
            // Set once the installer's activity ended in a way we trust: from then
            // on the wait is bounded by this instead of the full budget.
            var verdictAt: Long? = null
            var reportedFailure = false
            var i = 0
            while (true) {
                if (isInstalled(context, pkg, targetVersion)) return WaitResult.INSTALLED
                val end = endSignal?.tryReceive()?.getOrNull()
                if (end != null) {
                    if (end.installResult == null && end.elapsedMs < MIN_TRUSTED_FLOW_MS) {
                        log(
                            "installer activity ended after ${end.elapsedMs}ms with no result " +
                                "(singleInstance/singleTask launch or a trampoline); ignoring it"
                        )
                    } else {
                        val grace = if (end.installResult != null) {
                            RESULT_GRACE_MS
                        } else {
                            FLOW_END_EXTRA_MS
                        }
                        reportedFailure = end.installResult != null
                        verdictAt = System.currentTimeMillis() + grace
                        log(
                            "installer activity ended after ${end.elapsedMs}ms " +
                                "(result=${end.installResult}); giving $pkg ${grace}ms more"
                        )
                    }
                }
                val now = System.currentTimeMillis()
                val verdict = verdictAt
                if (verdict != null && now >= verdict) {
                    return if (reportedFailure) {
                        log("installer reported a failure and $pkg did not land")
                        WaitResult.FAILED
                    } else {
                        log("installer's UI is gone and $pkg did not land (it may still install)")
                        WaitResult.NOT_INSTALLED
                    }
                }
                if (now >= deadline) {
                    log("wait timed out for $pkg (installer may still be open)")
                    return WaitResult.TIMED_OUT
                }
                log("wait #${i++}: not installed yet")
                // receiveCatching() makes a closed channel non-fatal.
                withTimeoutOrNull(POLL_INTERVAL_MS) { events.receiveCatching() }
            }
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
