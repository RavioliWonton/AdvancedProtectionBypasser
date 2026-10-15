package wonton.abp.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks the framework to hot reload this module in the processes it has already
 * hooked, so an updated module version takes effect without rebooting.
 *
 * What the feature needs is in place: service API 102, exactly one Java entry
 * class (`wonton.abp.XposedEntry`), and a module that accepts the reload and
 * installs its hooks again (`onHotReloading` / `onHotReloaded`). A target the
 * framework cannot stage new code for answers `UNSUPPORTED`; a refusal or an
 * exception answers `FAILED`.
 *
 * `module.prop` also sets `autoHotReload`, so installing an update normally does
 * this by itself - this is for asking on demand.
 *
 * Hot reload is *not* how settings are propagated: the hooked processes read the
 * remote preferences live (see [ConfigStore]).
 */
object ModuleReload {

    private const val TAG = "ABP"

    /** What [reloadAll] found and what came of it. */
    data class Outcome(val targets: Int, val stale: Int, val reloaded: Int, val failed: Int)

    /**
     * Reloads every hooked target that is not already running the current module
     * code. [onDone] runs on the main thread with the outcome, or with `null` when
     * there is nothing to ask: no framework connection, or a service that does not
     * speak API 102.
     */
    fun reloadAll(service: XposedService?, onDone: (Outcome?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        if (service == null) {
            handler.post { onDone(null) }
            return
        }
        val targets = runCatching { service.runningTargets }.getOrElse {
            Log.w(TAG, "hot reload: cannot list running targets", it)
            handler.post { onDone(null) }
            return
        }
        // Only processes that are still running old code are worth reloading: an
        // up-to-date one would be reloaded with what it already has.
        val stale = targets.filter { it.state != HookedTarget.State.UP_TO_DATE }
        if (stale.isEmpty()) {
            handler.post { onDone(Outcome(targets.size, 0, 0, 0)) }
            return
        }

        val reloaded = AtomicInteger()
        val failed = AtomicInteger()
        val done = AtomicInteger()
        fun complete(succeeded: Boolean) {
            if (succeeded) reloaded.incrementAndGet() else failed.incrementAndGet()
            if (done.incrementAndGet() == stale.size) {
                val outcome = Outcome(targets.size, stale.size, reloaded.get(), failed.get())
                Log.i(TAG, "hot reload finished: $outcome")
                handler.post { onDone(outcome) }
            }
        }

        for (target in stale) {
            runCatching {
                service.hotReloadModule(target, null) { t, result ->
                    Log.i(TAG, "hot reload ${t.processName}: ${result.status} ${result.message}")
                    complete(result.status == HotReloadResult.Status.SUCCEEDED)
                }
            }.onFailure {
                Log.w(TAG, "hot reload request for ${target.processName} failed", it)
                complete(false)
            }
        }
    }
}
