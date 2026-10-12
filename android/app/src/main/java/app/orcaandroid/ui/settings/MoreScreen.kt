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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.orcaandroid.R
import app.orcaandroid.core.AppBackup
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
    // The passphrase is asked first for a backup (then the file is created), after picking the file for a restore.
    var backupPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = backupPassphrase
        backupPassphrase = null
        if (uri != null && pass != null) vm.presets.exportBackup(uri, pass) else pass?.fill(' ')
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { restoreFrom = uri; dialog = "backup_import" } }
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

            SectionTitle(stringResource(R.string.misc))
            SwitchRow(stringResource(R.string.double_back_quit), state.doubleBackToQuit, onChange = vm::setDoubleBackToQuit)
            UndoLimitRow(state.undoLimit, vm::setUndoLimit)
            AutoSaveRow(state.autoSaveSeconds, vm::setAutoSave)

            SectionTitle(stringResource(R.string.ai_tools))
            AiToolsSection(state, vm)

            SectionTitle(stringResource(R.string.printers_and_presets))
            Entry(stringResource(R.string.manage_printers), stringResource(R.string.n_printers_selected, state.selectedPrinters.size)) { vm.presets.openPrinterSetup() }
            Entry(stringResource(R.string.filament_vendors), stringResource(R.string.filament_vendors_text)) { dialog = "vendors" }
            if (state.supportsBedTypes) Entry(stringResource(R.string.plate_types), stringResource(R.string.plate_types_text)) { dialog = "plates" }
            SwitchRow(stringResource(R.string.prefer_my_filaments), state.preferMyFilaments, onChange = vm.presets::setPreferMyFilaments)
            Text(stringResource(R.string.prefer_my_filaments_text), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(stringResource(R.string.match_nozzle), state.matchNozzle, onChange = vm.presets::setMatchNozzle)
            Text(stringResource(R.string.match_nozzle_text) + state.installedNozzles.entries.joinToString("") { "\n${it.key}: %.1f mm".format(it.value) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Entry(stringResource(R.string.import_presets), stringResource(R.string.import_presets_text)) { importPresets.launch(arrayOf("*/*")) }
            Entry(stringResource(R.string.sync_cloud_presets), stringResource(R.string.sync_cloud_presets_text)) { vm.presets.syncCloudPresets() }
            Entry(stringResource(R.string.compare_presets), null) { vm.presets.openEditor(EditorTarget.Preset(PresetType.PRINT, pickCompare = true)) }
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

            SectionTitle(stringResource(R.string.orca_cloud))
            OrcaCloudSection()
            Entry(stringResource(R.string.sync_orca_cloud_presets), stringResource(R.string.sync_orca_cloud_presets_text)) { vm.presets.syncOrcaCloudPresets() }
            Entry(stringResource(R.string.upload_orca_cloud_presets), stringResource(R.string.upload_orca_cloud_presets_text)) { vm.presets.uploadOrcaCloudPresets() }

            SectionTitle(stringResource(R.string.backup))
            Entry(stringResource(R.string.backup_export), stringResource(R.string.backup_export_text)) { dialog = "backup_export" }
            Entry(stringResource(R.string.backup_import), stringResource(R.string.backup_import_text)) { restoreBackup.launch(arrayOf("*/*")) }

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
        "plates" -> PlateTypesDialog(state, vm) { dialog = null }
        "backup_export" -> PassphraseDialog(stringResource(R.string.backup_export), stringResource(R.string.backup_export_warning), confirm = true,
            onDismiss = { dialog = null }) { pass ->
            dialog = null
            backupPassphrase = pass
            saveBackup.launch("orcastudio-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date()) + ".osbk")
        }
        "backup_import" -> PassphraseDialog(stringResource(R.string.backup_import), stringResource(R.string.backup_import_warning), confirm = false,
            onDismiss = { dialog = null; restoreFrom = null }) { pass ->
            dialog = null
            restoreFrom?.let { vm.presets.importBackup(it, pass) }
            restoreFrom = null
        }
    }
}

/** Asks for the backup passphrase ([confirm]: twice, for a new backup). */
@Composable
private fun PassphraseDialog(title: String, text: String, confirm: Boolean, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val tooShort = pass.length < AppBackup.MIN_PASSPHRASE
    val mismatch = confirm && again != pass
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (confirm && tooShort) { { Text(stringResource(R.string.passphrase_min, AppBackup.MIN_PASSPHRASE)) } } else null)
                if (confirm) OutlinedTextField(again, { again = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.passphrase_repeat)) }, visualTransformation = PasswordVisualTransformation(), isError = again.isNotEmpty() && mismatch)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(pass.toCharArray()) }, enabled = pass.isNotEmpty() && !(confirm && (tooShort || mismatch))) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Undo steps to keep; applied as soon as the field holds a valid number. */
@Composable
private fun UndoLimitRow(limit: Int, onChange: (Int) -> Unit) {
    var text by remember(limit) { mutableStateOf(limit.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 1..app.orcaandroid.core.MAX_UNDO_LIMIT }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.undo_limit), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            text,
            { t ->
                text = t.filter(Char::isDigit).take(4)
                text.toIntOrNull()?.takeIf { it in 1..app.orcaandroid.core.MAX_UNDO_LIMIT && it != limit }?.let(onChange)
            },
            Modifier.width(110.dp),
            singleLine = true,
            isError = value == null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        )
    }
}

/** The MCP server for AI clients: switch, address, token and a ready-made `claude mcp add` command. */
@Composable
private fun AiToolsSection(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val port = app.orcaandroid.core.AI_TOOLS_PORT
    SwitchRow(stringResource(R.string.ai_tools_switch), state.aiTools, onChange = vm::setAiTools)
    Text(stringResource(R.string.ai_tools_text, port), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!state.aiTools) return
    SwitchRow(stringResource(R.string.ai_tools_lan), state.aiToolsLan, onChange = { vm.setAiTools(true, it) })
    if (state.aiToolsLan) Text(stringResource(R.string.ai_tools_lan_text, state.aiAddresses.joinToString { "http://$it:$port/mcp" }.ifEmpty { "-" }),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.aiError?.let { Text(stringResource(R.string.ai_tools_error, it), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error) }
    var shown by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ai_token) + ": " + if (shown) state.aiToken else "••••" + state.aiToken.takeLast(4),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { shown = !shown }) { Text(stringResource(if (shown) R.string.ai_token_hide else R.string.ai_token_show)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            val cmd = "claude mcp add --transport http orca http://127.0.0.1:$port/mcp --header \"Authorization: Bearer ${state.aiToken}\""
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("MCP setup", cmd))
            vm.toast(context.getString(R.string.ai_command_copied))
        }) { Text(stringResource(R.string.ai_copy_command)) }
        TextButton(onClick = vm::newAiToken) { Text(stringResource(R.string.ai_new_token)) }
    }
}

/** Desktop Preferences > Backup: a switch and the period in seconds. */
@Composable
private fun AutoSaveRow(seconds: Int, onChange: (Int) -> Unit) {
    val range = app.orcaandroid.core.AUTO_SAVE_RANGE
    var last by remember { mutableStateOf(if (seconds > 0) seconds else app.orcaandroid.core.DEFAULT_AUTO_SAVE_SECONDS) }
    var text by remember(seconds) { mutableStateOf(last.toString()) }
    SwitchRow(stringResource(R.string.auto_save), seconds > 0, onChange = { on -> onChange(if (on) last else 0) })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.auto_save_interval), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            text,
            { t ->
                text = t.filter(Char::isDigit).take(4)
                text.toIntOrNull()?.takeIf { it in range }?.let { last = it; if (seconds > 0 && it != seconds) onChange(it) }
            },
            Modifier.width(110.dp),
            enabled = seconds > 0,
            singleLine = true,
            isError = text.toIntOrNull()?.takeIf { it in range } == null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        )
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

/** Which filament brands' system presets appear in the filament lists. */
@Composable
private fun FilamentVendorsDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    val vendors = remember(state.setup) {
        state.setup?.filaments.orEmpty().filter { it.system }.map { it.brandName }.distinct().sortedBy { it.lowercase() }
    }
    var hidden by remember { mutableStateOf(state.hiddenFilamentVendors) }
    var query by remember { mutableStateOf("") }
    val shown = vendors.filter { it.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filament_vendors)) },
        text = {
            Column {
                if (vendors.size > 8)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.search)) })
                Row {
                    // Applies to the brands the search shows.
                    TextButton(onClick = { hidden = hidden - shown.toSet() }) { Text(stringResource(R.string.show_all)) }
                    TextButton(onClick = { hidden = hidden + shown }) { Text(stringResource(R.string.hide_all)) }
                }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(shown) { v ->
                        Row(Modifier.fillMaxWidth().clickable { hidden = if (v in hidden) hidden - v else hidden + v }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(v !in hidden, { hidden = if (it) hidden - v else hidden + v })
                            Text(v)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); vm.presets.setHiddenFilamentVendors(hidden) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Which plate types the plate pickers offer (checkbox) and which one plates without their own use (radio). */
@Composable
private fun PlateTypesDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    val types = state.setup?.bedTypes.orEmpty()
    var hidden by remember { mutableStateOf(state.hiddenBedTypes) }
    var preferred by remember { mutableStateOf(state.preferredBedType.takeIf { it in types }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plate_types)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item {
                    Text(stringResource(R.string.plate_types_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().clickable { preferred = "" }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(preferred.isEmpty(), { preferred = "" })
                        Text(stringResource(R.string.plate_type_printer_default))
                    }
                    HorizontalDivider()
                }
                items(types) { t ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // The default stays visible.
                        Checkbox(t !in hidden || t == preferred, { hidden = if (it) hidden - t else hidden + t }, enabled = t != preferred)
                        Text(vm.translator.tr(t), Modifier.weight(1f))
                        RadioButton(t == preferred, { preferred = t; hidden = hidden - t })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); vm.presets.setPlateTypes(hidden, preferred) }) { Text(stringResource(R.string.ok)) } },
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
