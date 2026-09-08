package wonton.abp.install

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File

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
 */
class ApkStageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_STAGE_APK) return null
        // Only the system may stage APKs here; anyone else gets nothing.
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID) {
            Log.w(TAG, "stageApk denied for uid $caller")
            return null
        }
        @Suppress("DEPRECATION")
        val source = extras?.getParcelable<ParcelFileDescriptor>(KEY_PFD) ?: return null
        val sid = extras.getInt(KEY_SESSION_ID, -1)
        val dir = File(requireNotNull(context).cacheDir, "apks")
        val out = File(dir, "session-$sid.apk")
        val ok = runCatching {
            dir.mkdirs()
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                out.outputStream().use { target -> input.copyTo(target) }
            }
            out.length() > 0
        }.getOrElse {
            Log.w(TAG, "stageApk failed for sid=$sid", it)
            false
        }
        Log.i(TAG, "stageApk: sid=$sid ok=$ok bytes=${if (ok) out.length() else 0L} -> $out")
        return Bundle().apply {
            putBoolean(KEY_OK, ok)
            if (ok) putString(KEY_PATH, out.absolutePath)
        }
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
        const val KEY_OK = "ok"
        const val KEY_PATH = "path"
    }
}
