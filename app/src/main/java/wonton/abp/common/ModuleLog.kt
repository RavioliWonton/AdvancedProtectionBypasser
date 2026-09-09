package wonton.abp.common

/**
 * Channel the app process uses to forward its own log lines to the module.
 *
 * LSPosed only captures the Xposed log API inside hooked processes, so anything
 * logged from the app (e.g. `InstallProxyActivity` or `ApkStageProvider`) never
 * shows up there. The module registers a receiver in system_server and re-logs
 * what it receives.
 *
 * [PERMISSION] is signature-protected, so only builds signed with this app's key
 * can send to that receiver.
 */
object ModuleLog {

    /** Signature-level permission guarding the module's log receiver. */
    const val PERMISSION = "wonton.abp.permission.INTERNAL"

    /** Broadcast action carrying a single log line. */
    const val ACTION = "wonton.abp.action.PROXY_LOG"

    /** String extra with the log line. */
    const val EXTRA_LINE = "wonton.abp.extra.LOG_LINE"
}
