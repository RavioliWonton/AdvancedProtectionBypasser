package wonton.abp.common

/**
 * Channel the app process uses to talk back to the module.
 *
 * LSPosed only captures the Xposed log API inside hooked processes, so anything
 * logged from the app (e.g. `InstallProxyActivity` or `ApkStageProvider`) never
 * shows up there. The module registers a receiver in system_server and re-logs
 * what it receives.
 *
 * The same channel carries the *terminal* event of an intercepted install
 * session (path B): the app process owns that flow, so the module needs to be
 * told how it ended instead of guessing with a timeout.
 *
 * [PERMISSION] is signature-protected, so only builds signed with this app's key
 * can send to that receiver.
 */
object ModuleLog {

    /** Signature-level permission guarding the module's log receiver. */
    const val PERMISSION = "wonton.abp.permission.INTERNAL"

    /** Broadcast action carrying a single log line or install result. */
    const val ACTION = "wonton.abp.action.PROXY_LOG"

    /** String extra with the log line. */
    const val EXTRA_LINE = "wonton.abp.extra.LOG_LINE"

    /** Session id the app process is reporting on; [NO_SESSION] when standalone. */
    const val EXTRA_SESSION_ID = "wonton.abp.extra.SESSION_ID"

    /** Int extra marking a message as the terminal result of a session. */
    const val EXTRA_RESULT_OK = "wonton.abp.extra.RESULT_OK"

    /** String extra with the human-readable outcome of that session. */
    const val EXTRA_RESULT_MESSAGE = "wonton.abp.extra.RESULT_MESSAGE"

    /**
     * "Not part of an intercepted session": a proxy run that only exists to
     * answer the app that started it (path A).
     */
    const val NO_SESSION = -1
}
