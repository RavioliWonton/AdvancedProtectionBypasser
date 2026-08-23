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
}
