package wonton.abp.install

import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.core.content.FileProvider

/**
 * [FileProvider] that tells its clients how large the file is.
 *
 * The stock provider serves an [AssetFileDescriptor] whose length is
 * [AssetFileDescriptor.UNKNOWN_LENGTH]. Installers *use* that length: the
 * Shizuku installer parses the APK straight from the descriptor
 * (`ApkAssets.loadFromFd(fd, name, 0, length, ...)`) and sizes the session file
 * it copies the APK into with it (`Session.openWrite(name, 0, length)`).
 *
 * Reading an APK from a descriptor of unknown length is not a path the platform
 * itself relies on - `ApkLiteParseUtils` always resolves the size first with
 * `lseek(fd, 0, SEEK_END)` - and a negative length trips the bounds check in
 * `openWrite`. Since the size is known here, hand it out instead of `-1`: the
 * installer then only has one way to read the APK we staged.
 *
 * Only used as the provider implementation in the manifest; the authority and
 * the `file_paths` meta-data are unchanged.
 */
class ApkFileProvider : FileProvider() {

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val descriptor = super.openAssetFile(uri, mode) ?: return null
        // A descriptor that already declares a length (or a range) is fine.
        if (descriptor.declaredLength >= 0L) return descriptor
        val pfd = descriptor.parcelFileDescriptor ?: return descriptor
        val size = pfd.statSize
        if (size < 0L) return descriptor
        return AssetFileDescriptor(pfd, 0L, size)
    }
}
