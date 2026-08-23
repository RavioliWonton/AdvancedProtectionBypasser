package wonton.abp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import wonton.abp.R

/** Success green used for the "enabled" status suffix. */
private val StatusEnabledColor = Color(0xFF2E7D32)

/**
 * Combined settings dialog: the default installer package on top, followed by
 * an "advanced settings" section with two opt-in switches that control the
 * version-specific install-restriction bypasses (both default OFF).
 *
 * The installer package is committed on confirm; the switches are applied
 * immediately via [onBypassEcmChange] / [onBypassUserRestrictionChange] so their
 * state survives even if the user dismisses the dialog.
 */
@Composable
fun SettingsDialog(
    initialInstaller: String,
    bypassEcm: Boolean,
    bypassUserRestriction: Boolean,
    moduleActive: Boolean,
    onBypassEcmChange: (Boolean) -> Unit,
    onBypassUserRestrictionChange: (Boolean) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialInstaller) }
    val default = stringResource(R.string.default_installer)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // --- Installer package ---
                Text(
                    text = stringResource(R.string.installer_dialog_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.installer_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.installer_dialog_hint)) },
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                // --- Advanced settings ---
                Text(
                    text = stringResource(R.string.settings_advanced_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))

                SwitchRow(
                    title = stringResource(R.string.settings_bypass_ecm_title),
                    desc = stringResource(R.string.settings_bypass_ecm_desc),
                    checked = bypassEcm,
                    moduleActive = moduleActive,
                    onCheckedChange = onBypassEcmChange,
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = stringResource(R.string.settings_bypass_user_restriction_title),
                    desc = stringResource(R.string.settings_bypass_user_restriction_desc),
                    checked = bypassUserRestriction,
                    moduleActive = moduleActive,
                    onCheckedChange = onBypassUserRestrictionChange,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { text = default }) {
                Text(stringResource(R.string.reset))
            }
        },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    moduleActive: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // When the switch is on we append a colored status suffix to the title:
    // green "：已成功启用" when the module is active, red "：启用失败" otherwise.
    // Nothing is appended when the switch is off.
    val errorColor = MaterialTheme.colorScheme.error
    val titleText = if (!checked) {
        buildAnnotatedString { append(title) }
    } else {
        val suffix: String
        val color: Color
        if (moduleActive) {
            suffix = stringResource(R.string.settings_status_enabled)
            color = StatusEnabledColor
        } else {
            suffix = stringResource(R.string.settings_status_failed)
            color = errorColor
        }
        buildAnnotatedString {
            append(title)
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) {
                append(suffix)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_name)) },
        text = { Text(stringResource(R.string.about_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
