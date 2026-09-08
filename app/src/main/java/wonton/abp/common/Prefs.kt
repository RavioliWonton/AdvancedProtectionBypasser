package wonton.abp.common

/**
 * Shared constants for the preferences that are written by the UI app and read
 * (remotely) by the Xposed module inside hooked processes.
 */
object Prefs {
    /** Remote-preferences group name shared between the app and the module. */
    const val GROUP = "config"

    /** StringSet of package names the user selected for hooking. */
    const val KEY_SELECTED = "selected_packages"

    /** String: the installer package appended to ACTION_INSTALL_PACKAGE intents. */
    const val KEY_INSTALLER = "installer_package"

    /** Default installer package. */
    const val DEFAULT_INSTALLER = "moe.shizuku.installer"

    /**
     * Boolean (default false): whether to relax the Enhanced Confirmation Mode
     * (Android 15+) "restricted setting" gate for install-from-unknown-sources
     * of selected apps. Off by default.
     */
    const val KEY_BYPASS_ECM = "bypass_ecm"

    /**
     * Boolean (default false): whether to relax the
     * DISALLOW_INSTALL_UNKNOWN_SOURCES[_GLOBALLY] user restriction (the global
     * gate Advanced Protection uses). This applies to the whole user, not a
     * single app. Off by default.
     */
    const val KEY_BYPASS_USER_RESTRICTION = "bypass_user_restriction"

    /** Default for both advanced bypass toggles. */
    const val DEFAULT_BYPASS = false

    /**
     * Boolean (default false): also hijack `ACTION_INSTALL_PACKAGE` intents that
     * already name an explicit target activity or package (e.g. the system
     * installer). Off by default because it overrides an intentional choice by
     * the calling app.
     */
    const val KEY_HIJACK_EXPLICIT = "hijack_explicit"

    /** Default for [KEY_HIJACK_EXPLICIT]. */
    const val DEFAULT_HIJACK_EXPLICIT = false
}
