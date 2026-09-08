package wonton.abp.data

import android.content.Context

/**
 * App-private UI preferences.
 *
 * These are deliberately NOT stored in the remote Xposed preferences: they only
 * affect this app's own screens and must persist even when the module is not
 * active (i.e. when no Xposed service is bound and remote writes are no-ops).
 */
class UiPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Whether the "no Google Play services" notice on the status page has been
     * closed. Once dismissed it never shows again.
     */
    fun isNoGmsNoticeDismissed(): Boolean =
        prefs.getBoolean(KEY_NO_GMS_NOTICE_DISMISSED, false)

    fun dismissNoGmsNotice() {
        prefs.edit().putBoolean(KEY_NO_GMS_NOTICE_DISMISSED, true).apply()
    }

    private companion object {
        const val NAME = "abp_ui"
        const val KEY_NO_GMS_NOTICE_DISMISSED = "no_gms_notice_dismissed"
    }
}
