package wonton.abp

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Application entry. Binds to the Xposed framework service (if the module is
 * activated) so the UI can read/write remote preferences shared with the module.
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
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
        private val _service = MutableStateFlow<XposedService?>(null)

        /** Emits the bound [XposedService], or null when the module is not active. */
        val service: StateFlow<XposedService?> get() = _service
    }
}
