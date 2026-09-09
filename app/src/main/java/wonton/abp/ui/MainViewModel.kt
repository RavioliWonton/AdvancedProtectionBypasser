package wonton.abp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wonton.abp.App
import wonton.abp.common.AdvancedProtection
import wonton.abp.common.Gms
import wonton.abp.common.Prefs
import wonton.abp.data.AppInfo
import wonton.abp.data.AppRepository
import wonton.abp.data.ConfigStore
import wonton.abp.data.UiPrefs

/**
 * UI state for the main screen.
 *
 * Note: the installed-app list is intentionally NOT kept here. It is exposed as
 * a separate [MainViewModel.apps] flow so the screen state stays small and the
 * selection is always derived live from the (remote) config store.
 */
data class UiState(
    val loading: Boolean = true,
    val moduleActive: Boolean = false,
    val selected: Set<String> = emptySet(),
    val installerPackage: String = Prefs.DEFAULT_INSTALLER,
    val bypassEcm: Boolean = Prefs.DEFAULT_BYPASS,
    val bypassUserRestriction: Boolean = Prefs.DEFAULT_BYPASS,
    /** Also hijack install intents that name an explicit target. Off by default. */
    val hijackExplicit: Boolean = Prefs.DEFAULT_HIJACK_EXPLICIT,
    /**
     * Whether Google Play services is installed. When it is not, the status page
     * shows a dismissible informational notice; nothing is gated on it.
     */
    val gmsAvailable: Boolean = true,
    /** User closed the "no Google Play services" notice on the status page. */
    val noGmsNoticeDismissed: Boolean = false,
    /** Bound framework name (e.g. "LSPosed"), or null when the module is inactive. */
    val frameworkName: String? = null,
    /** Bound framework version, or null when the module is inactive. */
    val frameworkVersion: String? = null,
    /** Framework internal version code (e.g. LSPosed build number), or null. */
    val frameworkVersionCode: Long? = null,
    /** Whether Advanced Protection's sideloading restriction is present. */
    val advancedProtectionEnabled: Boolean = false,
    /**
     * True when the restriction reads as absent while this module's own
     * unknown-sources relaxation is active, so the real state is unknown.
     */
    val advancedProtectionRelaxed: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = AppRepository(app)
    private val uiPrefs = UiPrefs(app)

    private val _state = MutableStateFlow(
        UiState(
            gmsAvailable = Gms.isAvailable(app),
            noGmsNoticeDismissed = uiPrefs.isNoGmsNoticeDismissed(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Installed apps, loaded once and kept separate from [state]. */
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    init {
        // React to the Xposed service binding (module activation). Whenever the
        // binding changes we re-read the current values straight from the store.
        viewModelScope.launch {
            App.service.collect { refreshFromStore() }
        }
        loadApps()
    }

    /** A fresh store bound to the currently available service. */
    private fun currentStore(): ConfigStore = ConfigStore(App.service.value)

    /** Reads module state, selection and installer live from the store. */
    private fun refreshFromStore() {
        val store = currentStore()
        val selected = store.getSelectedPackages()
        val bypassUserRestriction = store.getBypassUserRestriction()
        val advancedProtection = AdvancedProtection.isRestrictionPresent(getApplication())
        _state.value = _state.value.copy(
            moduleActive = store.isAvailable,
            selected = selected,
            installerPackage = store.getInstallerPackage(),
            bypassEcm = store.getBypassEcm(),
            bypassUserRestriction = bypassUserRestriction,
            hijackExplicit = store.getHijackExplicit(),
            frameworkName = store.getFrameworkName(),
            frameworkVersion = store.getFrameworkVersion(),
            frameworkVersionCode = store.getFrameworkVersionCode(),
            advancedProtectionEnabled = advancedProtection,
            // Our own relaxation makes the restriction read as absent user-wide,
            // so a false reading may not be the real state.
            advancedProtectionRelaxed =
                !advancedProtection && bypassUserRestriction && selected.isNotEmpty(),
        )
    }

    /** Re-reads the live module status (used when opening the status page). */
    fun refreshStatus() {
        refreshFromStore()
    }

    fun loadApps() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            _apps.value = repository.loadInstalledApps()
            _state.value = _state.value.copy(loading = false)
            // Re-sync selection in case the store changed while loading.
            refreshFromStore()
        }
    }

    /** Toggles a single package and applies the change immediately. */
    fun toggle(packageName: String, checked: Boolean) {
        val store = currentStore()
        val next = store.getSelectedPackages().toMutableSet().apply {
            if (checked) add(packageName) else remove(packageName)
        }
        store.setSelectedPackages(next)
        _state.value = _state.value.copy(selected = store.getSelectedPackages())
    }

    /**
     * Selects every non-system app that declares REQUEST_INSTALL_PACKAGES,
     * applied immediately. System apps are excluded from auto-selection.
     */
    fun selectAllInstallers() {
        val installers = _apps.value
            .filter { it.requestsInstallPermission && !it.isSystem }
            .map { it.packageName }
        val store = currentStore()
        val next = store.getSelectedPackages().toMutableSet().apply { addAll(installers) }
        store.setSelectedPackages(next)
        _state.value = _state.value.copy(selected = store.getSelectedPackages())
    }

    fun setInstallerPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isEmpty()) return
        val store = currentStore()
        store.setInstallerPackage(clean)
        _state.value = _state.value.copy(installerPackage = store.getInstallerPackage())
    }

    fun setBypassEcm(enabled: Boolean) {
        val store = currentStore()
        store.setBypassEcm(enabled)
        _state.value = _state.value.copy(bypassEcm = store.getBypassEcm())
    }

    fun setBypassUserRestriction(enabled: Boolean) {
        val store = currentStore()
        store.setBypassUserRestriction(enabled)
        _state.value = _state.value.copy(bypassUserRestriction = store.getBypassUserRestriction())
    }

    fun setHijackExplicit(enabled: Boolean) {
        val store = currentStore()
        store.setHijackExplicit(enabled)
        _state.value = _state.value.copy(hijackExplicit = store.getHijackExplicit())
    }

    /**
     * Closes the "no Google Play services" notice on the status page. The choice
     * is persisted locally and the notice never shows again.
     */
    fun dismissNoGmsNotice() {
        uiPrefs.dismissNoGmsNotice()
        _state.value = _state.value.copy(noGmsNoticeDismissed = true)
    }
}
