package wonton.abp.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import wonton.abp.R
import wonton.abp.data.AppInfo
import wonton.abp.ui.theme.ABPTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ABPTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()

    var menuExpanded by remember { mutableStateOf(false) }
    var showInstallerDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    // System apps are hidden by default on every launch; this state is
    // intentionally not persisted.
    var showSystemApps by remember { mutableStateOf(false) }

    val visibleApps = if (showSystemApps) apps else apps.filterNot { it.isSystem }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.menu_more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_configure_installer)) },
                            onClick = {
                                menuExpanded = false
                                showInstallerDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_select_all_installers)) },
                            onClick = {
                                menuExpanded = false
                                vm.selectAllInstallers()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (showSystemApps) R.string.menu_hide_system_apps
                                        else R.string.menu_show_system_apps
                                    )
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                showSystemApps = !showSystemApps
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_language)) },
                            onClick = {
                                menuExpanded = false
                                showLanguageDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) },
                            onClick = {
                                menuExpanded = false
                                showAboutDialog = true
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading -> LoadingView()
                else -> AppList(
                    apps = visibleApps,
                    selected = state.selected,
                    moduleActive = state.moduleActive,
                    selectedCount = state.selected.size,
                    onToggle = vm::toggle,
                )
            }
        }
    }

    if (showInstallerDialog) {
        InstallerConfigDialog(
            initialValue = state.installerPackage,
            onConfirm = {
                vm.setInstallerPackage(it)
                showInstallerDialog = false
            },
            onDismiss = { showInstallerDialog = false },
        )
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showLanguageDialog) {
        LanguageDialog(onDismiss = { showLanguageDialog = false })
    }
}

/**
 * In-app per-app language picker following the official Android guidance
 * (developer.android.com/guide/topics/resources/app-languages). Uses the
 * AndroidX AppCompat back-compat API [AppCompatDelegate.setApplicationLocales],
 * which syncs with the system per-app language setting on Android 13+ and is
 * persisted by AppCompat (autoStoreLocales) on Android 12 and lower. Selecting
 * a locale recreates the activity so Compose recomposes with the new resources.
 */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    // "" represents "follow system" (empty locale list).
    val options = listOf(
        "" to stringResource(R.string.language_system_default),
        "en" to stringResource(R.string.language_english),
        "zh-CN" to stringResource(R.string.language_zh_cn),
        "zh-TW" to stringResource(R.string.language_zh_tw),
    )
    val current = AppCompatDelegate.getApplicationLocales()
    val currentTag = current.get(0)?.toLanguageTag() ?: ""
    // Normalise e.g. "zh-Hans-CN" -> match by language+region prefix.
    val selectedTag = options.map { it.first }.firstOrNull { tag ->
        tag.isNotEmpty() && currentTag.startsWith(tag, ignoreCase = true)
    } ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_dialog_title)) },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = tag == selectedTag,
                                onClick = {
                                    val locales = if (tag.isEmpty()) {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(tag)
                                    }
                                    AppCompatDelegate.setApplicationLocales(locales)
                                    onDismiss()
                                },
                            )
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = tag == selectedTag,
                            onClick = null,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.loading))
    }
}

@Composable
private fun AppList(
    apps: List<AppInfo>,
    selected: Set<String>,
    moduleActive: Boolean,
    selectedCount: Int,
    onToggle: (String, Boolean) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (!moduleActive) {
            item { ModuleInactiveBanner() }
        }
        item {
            Text(
                text = stringResource(R.string.selected_count, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        items(apps, key = { it.packageName }) { app ->
            AppRow(app, app.packageName in selected, onToggle)
        }
    }
}

@Composable
private fun ModuleInactiveBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.module_inactive_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.module_inactive_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (app.requestsInstallPermission || app.isSystem) {
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (app.requestsInstallPermission) {
                        TagChip(
                            text = stringResource(R.string.badge_install_perm),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (app.isSystem) {
                        TagChip(
                            text = stringResource(R.string.badge_system),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle(app.packageName, it) },
        )
    }
}

@Composable
private fun TagChip(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(
        color = container,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AppIcon(app: AppInfo) {
    val drawable = app.icon
    if (drawable != null) {
        val bitmap = remember(app.packageName) {
            runCatching { drawable.toBitmap(96, 96) }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            return
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

// -------------------------------------------------------------------------
// Compose previews
//
// MainScreen() pulls its state from a ViewModel (and ultimately the Xposed
// remote service), which is unavailable in the preview renderer. We therefore
// preview the stateless building blocks with hand-crafted sample data so the
// layout can be inspected in the IDE without a device.
// -------------------------------------------------------------------------

private val sampleApps = listOf(
    AppInfo(
        packageName = "com.android.vending",
        label = "Google Play Store",
        icon = null,
        requestsInstallPermission = true,
        isSystem = true,
    ),
    AppInfo(
        packageName = "com.example.browser",
        label = "Sample Browser",
        icon = null,
        requestsInstallPermission = true,
        isSystem = false,
    ),
    AppInfo(
        packageName = "com.example.notes",
        label = "Sample Notes",
        icon = null,
        requestsInstallPermission = false,
        isSystem = false,
    ),
)

@Preview(name = "App list (module active)", showBackground = true)
@Composable
private fun AppListPreview() {
    ABPTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            AppList(
                apps = sampleApps,
                selected = setOf("com.example.browser"),
                moduleActive = true,
                selectedCount = 1,
                onToggle = { _, _ -> },
            )
        }
    }
}

@Preview(name = "App list (module inactive)", showBackground = true)
@Composable
private fun AppListInactivePreview() {
    ABPTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            AppList(
                apps = sampleApps,
                selected = emptySet(),
                moduleActive = false,
                selectedCount = 0,
                onToggle = { _, _ -> },
            )
        }
    }
}

@Preview(name = "App list (dark)", showBackground = true)
@Composable
private fun AppListDarkPreview() {
    ABPTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AppList(
                apps = sampleApps,
                selected = setOf("com.android.vending"),
                moduleActive = true,
                selectedCount = 1,
                onToggle = { _, _ -> },
            )
        }
    }
}
