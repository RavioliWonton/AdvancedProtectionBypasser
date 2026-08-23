package wonton.abp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wonton.abp.App
import wonton.abp.common.Prefs
import wonton.abp.data.AppInfo
import wonton.abp.data.AppRepository
import wonton.abp.data.ConfigStore

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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = AppRepository(app)

    private val _state = MutableStateFlow(UiState())
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
        _state.value = _state.value.copy(
            moduleActive = store.isAvailable,
            selected = store.getSelectedPackages(),
            installerPackage = store.getInstallerPackage(),
        )
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
}
