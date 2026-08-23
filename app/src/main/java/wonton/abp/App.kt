package wonton.abp

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Application entry. Binds to the Xposed framework service (if the module is
 * activated) so the UI can read/write remote preferences shared with the module.
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Allow the module (running in system_server) to stage APKs into our
        // cache for delivery to the configured installer.
        makeCacheWorldAccessible()
        // Will call back on onServiceBind if an Xposed framework is present.
        XposedServiceHelper.registerListener(this)
    }

    /**
     * Makes our cache subtree traversable and writable so the Xposed module
     * (system_server) can drop staged APKs into `cache/apks/`. The permissions
     * persist across reboots; the FileProvider serves the files over a
     * content:// URI, so this never exposes anything to other apps' direct
     * filesystem access beyond the explicit grants.
     */
    private fun makeCacheWorldAccessible() {
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

    override fun onServiceBind(service: XposedService) {
        _service.value = service
    }

    override fun onServiceDied(service: XposedService) {
        _service.value = null
    }

    companion object {
        lateinit var instance: App
            private set

        private val _service = MutableStateFlow<XposedService?>(null)

        /** Emits the bound [XposedService], or null when the module is not active. */
        val service: StateFlow<XposedService?> get() = _service
    }
}
