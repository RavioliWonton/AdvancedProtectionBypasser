package wonton.abp

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import wonton.abp.install.InstallHandoff

/**
 * Application entry. Binds to the Xposed framework service (if the module is
 * activated) so the UI can read/write remote preferences shared with the module.
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        // A staged APK only matters while its install runs, and a fresh process
        // means none is: this is where the leftovers of a killed proxy (or of an
        // install the user abandoned on the installer's confirmation screen) get
        // reaped. Silent by design, exactly like the module's own start-up clear.
        runCatching {
            val purge = InstallHandoff.purgeApks(this)
            if (purge.total > 0) Log.i(TAG, "startup: cleared ${purge.message} staged apks")
        }
        // Will call back on onServiceBind if an Xposed framework is present.
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        _service.value = service
    }

    override fun onServiceDied(service: XposedService) {
        _service.value = null
    }

    companion object {
        private const val TAG = "ABP"

        private val _service = MutableStateFlow<XposedService?>(null)

        /** Emits the bound [XposedService], or null when the module is not active. */
        val service: StateFlow<XposedService?> get() = _service
    }
}
