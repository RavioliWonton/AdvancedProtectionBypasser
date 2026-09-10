package wonton.abp.install

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Binder staging endpoint for the Xposed module (which runs in system_server).
 *
 * The module cannot copy the APK it intercepts into this app's private data
 * directory itself: SELinux denies `system_server -> app_data_file` writes, and
 * making the directory world-writable only fixes the DAC layer (that is why the
 * module used to fail with `EACCES` on `cache/apks/session-*.apk`).
 *
 * It also cannot hand us a file descriptor through an Intent, because
 * ActivityStarter refuses any intent that carries one:
 *
 * ```
 * if (intent.hasFileDescriptors()) {
 *     throw new IllegalArgumentException("File descriptors passed in Intent");
 * }
 * ```
 *
 * A `ContentProvider.call()` Bundle is not an Intent, so it *can* carry a
 * `ParcelFileDescriptor` over Binder. The module opens the staged APK (which it
 * can read), passes the descriptor here, and this provider - running in the
 * app's own process - copies the bytes into `cache/apks/`, where the FileProvider
 * serves them to the configured installer.
 *
 * Staging a file the installer then rejects is the worst outcome of this whole
 * hand-off, because the installer can only answer with an opaque "there was a
 * problem parsing the package". The copy is therefore checked here: its size
 * against the size the module measured, and - through the very same FileProvider
 * URI the installer gets - that the archive can be opened and still contains a
 * manifest.
 */
class ApkStageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_STAGE_APK) return null
        // Only the system may stage APKs here; anyone else gets nothing.
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID) {
            Log.w(TAG, "stageApk denied for uid $caller")
            return result(false, "denied for uid $caller")
        }
        @Suppress("DEPRECATION")
        val source = extras?.getParcelable<ParcelFileDescriptor>(KEY_PFD)
            ?: return result(false, "no file descriptor in extras")
        val sid = extras.getInt(KEY_SESSION_ID, -1)
        // Size the module measured on the session APK, to detect a short copy.
        val expected = extras.getLong(KEY_EXPECTED_BYTES, -1L)
        val ctx = requireNotNull(context)
        val out = File(File(ctx.cacheDir, "apks").apply { mkdirs() }, "session-$sid.apk")
        val staged = runCatching {
            // Drop the copies of earlier sessions: they are tens of megabytes and
            // nothing can come back for them.
            out.parentFile?.listFiles { f -> f.name.startsWith("session-") && f.name != out.name }
                ?.forEach { runCatching { it.delete() } }
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                out.outputStream().use { target -> input.copyTo(target) }
            }
            out.length()
        }
        val bytes = staged.getOrNull()
        val message = when {
            bytes == null -> "copy failed: " + (
                staged.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: "empty file"
                )
            bytes <= 0L -> "copy failed: empty file"
            expected >= 0L && expected != bytes -> "copy is short: $bytes of $expected bytes"
            else -> "staged $bytes bytes -> $out (${verifyHandoff(ctx, out)})"
        }
        val ok = bytes != null && bytes > 0L && (expected < 0L || expected == bytes)
        Log.i(TAG, "stageApk: sid=$sid ok=$ok $message")
        // The result Bundle doubles as the log channel: this provider runs in
        // the app process, so its Log.* output never reaches the LSPosed log.
        // The module logs [KEY_MESSAGE] through the Xposed logger instead.
        return result(ok, message).apply {
            if (ok) putString(KEY_PATH, out.absolutePath)
        }
    }

    /**
     * Reproduces what the installer is about to do: open the staged APK through
     * the FileProvider URI it receives and look for the manifest entry.
     *
     * This runs in the app process on purpose. When the installer cannot read the
     * URI - a mistaken path mapping, a missing read grant - it only shows an
     * opaque "there was a problem parsing the package", and both the provider and
     * the installer are one call away from this log line.
     */
    private fun verifyHandoff(ctx: Context, out: File): String = runCatching {
        val uri = InstallHandoff.apkUri(out)
        val afd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: return@runCatching "no descriptor for $uri"
        afd.use { descriptor ->
            val manifest = ZipFile(out).use { it.getEntry("AndroidManifest.xml") != null }
            "uri=$uri length=${descriptor.length} declared=${descriptor.declaredLength} " +
                "manifest=$manifest"
        }
    }.getOrElse {
        "verify failed: ${it.javaClass.simpleName}: ${it.message}"
    }

    private fun result(ok: Boolean, message: String): Bundle = Bundle().apply {
        putBoolean(KEY_OK, ok)
        putString(KEY_MESSAGE, message)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "ABP"

        /** Authority declared in the manifest. */
        const val AUTHORITY = "wonton.abp.stage"

        const val METHOD_STAGE_APK = "stageApk"
        const val KEY_PFD = "pfd"
        const val KEY_SESSION_ID = "sessionId"

        /** Size of the source APK; a short copy is reported as a failure. */
        const val KEY_EXPECTED_BYTES = "expectedBytes"
        const val KEY_OK = "ok"
        const val KEY_PATH = "path"

        /** Human-readable outcome; the module logs this to the Xposed log. */
        const val KEY_MESSAGE = "message"
    }
}
