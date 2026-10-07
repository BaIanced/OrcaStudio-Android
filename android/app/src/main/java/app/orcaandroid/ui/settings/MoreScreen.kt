package app.orcaandroid.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.BuildConfig
import app.orcaandroid.R
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.ThemeMode
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.EditorTarget
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.SectionTitle
import app.orcaandroid.ui.prepare.CalibrationDialog
import app.orcaandroid.ui.prepare.SwitchRow

/** App settings, preset management, calibration, recent files and about. */
@Composable
fun MoreScreen(state: UiState, vm: AppViewModel) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val importPresets = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.presets.importPresets(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.appearance))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)
                    .forEachIndexed { i, (mode, label) ->
                        SegmentedButton(state.themeMode == mode, { vm.setThemeMode(mode) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
                    }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SwitchRow(stringResource(R.string.dynamic_color), state.dynamicColor, onChange = vm::setDynamicColor)

            SectionTitle(stringResource(R.string.printers_and_presets))
            Entry(stringResource(R.string.manage_printers), stringResource(R.string.n_printers_selected, state.selectedPrinters.size)) { vm.presets.openPrinterSetup() }
            Entry(stringResource(R.string.filament_vendors), stringResource(R.string.filament_vendors_text)) { dialog = "vendors" }
            Entry(stringResource(R.string.import_presets), stringResource(R.string.import_presets_text)) { importPresets.launch(arrayOf("*/*")) }
            Entry(stringResource(R.string.sync_cloud_presets), stringResource(R.string.sync_cloud_presets_text)) { vm.presets.syncCloudPresets() }
            Entry(stringResource(R.string.compare_presets), null) { vm.presets.openEditor(EditorTarget.Preset(PresetType.PRINT)) }
            Entry(stringResource(R.string.check_profile_updates), null) { vm.presets.checkProfileUpdates() }
            state.profileUpdates?.takeIf { it.isNotEmpty() }?.let { updates ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        updates.forEach { Text("${it.vendor}: ${it.installed} → ${it.available}", style = MaterialTheme.typography.bodySmall) }
                        val progress = state.profileUpdateProgress
                        if (progress != null) {
                            Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        } else {
                            TextButton(onClick = vm.presets::installProfileUpdates) { Text(stringResource(R.string.install_updates)) }
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.calibration))
            Entry(stringResource(R.string.calibration_tests), stringResource(R.string.calibration_tests_text)) { dialog = "calib" }

            if (state.recents.isNotEmpty()) {
                SectionTitle(stringResource(R.string.recent))
                state.recents.take(10).forEach { r -> Entry(r.name, if (r.isProject) stringResource(R.string.project) else null) { vm.files.openRecent(r) } }
            }

            SectionTitle(stringResource(R.string.about))
            About()
        }
    }
    when (dialog) {
        "calib" -> CalibrationDialog(vm) { dialog = null }
        "vendors" -> FilamentVendorsDialog(state, vm) { dialog = null }
    }
}

@Composable
private fun Entry(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

/** Which filament vendors' system presets appear in the filament lists. */
@Composable
private fun FilamentVendorsDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    val vendors = remember(state.setup) { state.setup?.filaments.orEmpty().filter { it.system }.map { it.vendor.ifEmpty { "Generic" } }.distinct().sorted() }
    var hidden by remember { mutableStateOf(state.hiddenFilamentVendors) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filament_vendors)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(vendors) { v ->
                    Row(Modifier.fillMaxWidth().clickable { hidden = if (v in hidden) hidden - v else hidden + v }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(v !in hidden, { hidden = if (it) hidden - v else hidden + v })
                        Text(v)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); vm.presets.setHiddenFilamentVendors(hidden) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun About() {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_based_on, BuildConfig.ORCA_VERSION, BuildConfig.ORCA_COMMIT.take(10)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_credit), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_ai), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { open("https://github.com/SoftFever/OrcaSlicer") }) { Text("OrcaSlicer") }
                TextButton(onClick = { open(BuildConfig.SOURCE_URL) }) { Text(stringResource(R.string.source_code)) }
                TextButton(onClick = { open("https://www.gnu.org/licenses/agpl-3.0.html") }) { Text("AGPL-3.0") }
            }
        }
    }
}
