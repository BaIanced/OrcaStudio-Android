package com.orcaslicer.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.orcaslicer.android.core.PresetRef
import com.orcaslicer.android.core.PresetType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.orcaslicer.android.render.PlateGeometry
import com.orcaslicer.android.render.PlateView
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SlicerScreen(state: UiState, vm: MainViewModel) {
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxSize()) {
                    PlatePane(state, vm, Modifier.weight(1f).fillMaxHeight())
                    SidePanel(state, vm, Modifier.width(400.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PlatePane(state, vm, Modifier.weight(1f).fillMaxWidth())
                    SidePanel(state, vm, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PlatePane(state: UiState, vm: MainViewModel, modifier: Modifier) {
    var view by remember { mutableStateOf<PlateView?>(null) }
    val done = state.slice as? SliceState.Done

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { ctx -> PlateView(ctx).also { view = it } }, modifier = Modifier.fillMaxSize())
            if (done != null) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    tonalElevation = 3.dp,
                ) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(!state.showToolpaths, { vm.setShowToolpaths(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Modell") }
                        SegmentedButton(state.showToolpaths, { vm.setShowToolpaths(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Vorschau") }
                    }
                }
            }
        }
        if (done != null && state.showToolpaths && done.result.layers.isNotEmpty()) {
            val layers = done.result.layers
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Layer ${state.layer + 1}/${layers.size}  ·  %.2f mm".format(Locale.ROOT, layers[state.layer].z),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(200.dp),
                )
                Slider(
                    value = state.layer.toFloat(),
                    onValueChange = { vm.setLayer(it.toInt()) },
                    valueRange = 0f..layers.lastIndex.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // Push scene changes to the GL thread.
    val v = view ?: return
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val grid = MaterialTheme.colorScheme.outline
    LaunchedEffect(v, background, grid) {
        v.onGl { setColors(floatArrayOf(background.red, background.green, background.blue), floatArrayOf(grid.red, grid.green, grid.blue)) }
    }
    LaunchedEffect(v, state.setup?.bed) { state.setup?.bed?.let { bed -> v.onGl { setBed(bed) } } }
    LaunchedEffect(v, state.model) {
        val mesh = state.model?.let { m -> withContext(Dispatchers.IO) { PlateGeometry.loadMesh(m.meshFile) } }
        v.onGl { setMesh(mesh) }
    }
    LaunchedEffect(v, done) {
        val paths = done?.let { d -> withContext(Dispatchers.IO) { PlateGeometry.loadToolpaths(d.result.previewFile) } }
        v.onGl { setToolpaths(paths) }
    }
    LaunchedEffect(v, state.showToolpaths, state.layer, done) {
        val layers = done?.result?.layers.orEmpty()
        val end = layers.getOrNull(state.layer + 1)?.firstSegment ?: Int.MAX_VALUE
        v.onGl {
            showToolpaths = state.showToolpaths
            visibleSegments = end
        }
    }
}

@Composable
private fun SidePanel(state: UiState, vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val openModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.openModels(it) }
    val saveGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x.gcode")) { uri ->
        uri?.let(vm::exportGcode)
    }
    val setup = state.setup
    val running = state.slice as? SliceState.Running
    val done = state.slice as? SliceState.Done

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OrcaSlicer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::openVendorSetup) { Text("Drucker verwalten …") }
        }

        PresetRow(
            label = "Drucker",
            selected = state.printer,
            items = state.visiblePrinters.map { PickerItem(it.name, it.name, if (it.system) it.vendor else "Eigene") },
            modified = !state.overrides[PresetType.PRINTER].isNullOrEmpty(),
            onSelect = vm::selectPrinter,
            onEdit = { vm.openSettings(PresetType.PRINTER) },
            enabled = running == null,
        )
        PresetRow(
            label = "Filament",
            selected = state.filament,
            items = setup?.filaments.orEmpty().toPickerItems(),
            modified = !state.overrides[PresetType.FILAMENT].isNullOrEmpty(),
            onSelect = vm::selectFilament,
            onEdit = { vm.openSettings(PresetType.FILAMENT) },
            enabled = running == null,
        )
        PresetRow(
            label = "Prozess",
            selected = state.print,
            items = setup?.prints.orEmpty().toPickerItems(),
            modified = !state.overrides[PresetType.PRINT].isNullOrEmpty(),
            onSelect = vm::selectPrint,
            onEdit = { vm.openSettings(PresetType.PRINT) },
            enabled = running == null,
        )

        HorizontalDivider()
        Text("Schnelleinstellungen", style = MaterialTheme.typography.titleMedium)
        val print = PresetType.PRINT
        state.value(print, "layer_height")?.let { lh ->
            QuickNumberRow("Schichthöhe", lh, "mm", state.isModified(print, "layer_height")) { vm.setOption(print, "layer_height", it) }
        }
        QuickSwitchRow("Stützstrukturen", state.value(print, "enable_support") == "1", state.isModified(print, "enable_support")) {
            vm.setOption(print, "enable_support", if (it) "1" else "0")
        }
        QuickSwitchRow("Brim", state.value(print, "brim_type").let { it != null && it != "no_brim" }, state.isModified(print, "brim_type")) {
            vm.setOption(print, "brim_type", if (it) "auto_brim" else "no_brim")
        }
        state.value(print, "sparse_infill_density")?.let { density ->
            val percent = density.removeSuffix("%").toFloatOrNull() ?: 15f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Infill", Modifier.weight(1f), color = modifiedColor(state.isModified(print, "sparse_infill_density")))
                Text("${percent.toInt()} %")
            }
            Slider(
                value = percent,
                onValueChange = { v -> vm.setOption(print, "sparse_infill_density", "${(v / 5).toInt() * 5}%") },
                valueRange = 0f..100f,
                steps = 19,
            )
        }

        HorizontalDivider()
        Text("Modell", style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(
            onClick = { openModels.launch(arrayOf("*/*")) },
            enabled = running == null && setup != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Modell öffnen (STL, 3MF, OBJ, STEP)") }
        if (state.modelNames.isNotEmpty()) {
            Text(state.modelNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
        state.model?.let { m ->
            Text(
                "%d Objekt(e) · %.1f × %.1f × %.1f mm".format(Locale.ROOT, m.objects, m.size[0], m.size[1], m.size[2]),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!m.fits) Text("Nicht alle Objekte passen auf das Druckbett.", color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kopien", Modifier.weight(1f))
                OutlinedButton(onClick = { vm.setCopies(state.copies - 1) }, enabled = state.copies > 1 && running == null) { Text("−") }
                Text("${state.copies}", Modifier.padding(horizontal = 12.dp))
                OutlinedButton(onClick = { vm.setCopies(state.copies + 1) }, enabled = running == null) { Text("+") }
            }
        }

        HorizontalDivider()
        if (running != null) {
            Text("${running.text} (${running.percent} %)", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { running.percent / 100f }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = vm::cancelSlice, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
        } else {
            Button(
                onClick = vm::slice,
                enabled = state.model != null && state.print != null && state.filament != null && state.busyMessage == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Slicen") }
        }

        done?.let { d ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ergebnis", style = MaterialTheme.typography.titleMedium)
                    Text("Druckzeit: ${formatDuration(d.result.printTimeSeconds)}")
                    Text("Filament: %.2f m · %.1f g".format(Locale.ROOT, d.result.filamentMm / 1000, d.result.filamentGrams))
                    if (d.result.cost > 0) Text("Kosten: %.2f".format(Locale.ROOT, d.result.cost))
                    Text("Layer: ${d.result.layers.size}")
                    d.result.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                }
            }
            UploadSection(state, vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { saveGcode.launch(vm.gcodeFileName()) }, modifier = Modifier.weight(1f)) { Text("G-Code speichern") }
                OutlinedButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(d.result.gcodeFile))
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/x.gcode")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "G-Code teilen"))
                }, modifier = Modifier.weight(1f)) { Text("Teilen") }
            }
        }
    }
}

/** Send-to-printer actions for the sliced plate. */
@Composable
private fun UploadSection(state: UiState, vm: MainViewModel) {
    val connection = state.connection
    when {
        connection == null -> OutlinedButton(onClick = { vm.setScreen(Screen.DEVICE) }, modifier = Modifier.fillMaxWidth()) {
            Text("Drucker verbinden, um direkt zu senden …")
        }
        !connection.type.canUpload -> Text(
            "Für „${connection.type.label}“ ist kein direktes Senden möglich – G-Code speichern oder teilen.",
            style = MaterialTheme.typography.bodySmall,
        )
        else -> when (val upload = state.upload) {
            is UploadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (upload.startPrint) "Sende und starte Druck … ${(upload.progress * 100).toInt()} %" else "Sende … ${(upload.progress * 100).toInt()} %")
                LinearProgressIndicator(progress = { upload.progress }, modifier = Modifier.fillMaxWidth())
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (upload as? UploadState.Done)?.let { Text("✓ ${it.message}", color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.uploadGcode(startPrint = true) }, modifier = Modifier.weight(1f)) { Text("Drucken") }
                    FilledTonalButton(onClick = { vm.uploadGcode(startPrint = false) }, modifier = Modifier.weight(1f)) { Text("Nur senden") }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    label: String,
    selected: String?,
    items: List<PickerItem>,
    modified: Boolean,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    enabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PickerField(
            label = if (modified) "$label (geändert)" else label,
            selected = selected,
            items = items,
            onSelect = onSelect,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onEdit, enabled = enabled && selected != null) { Icon(Icons.Default.Edit, "$label bearbeiten") }
    }
}

@Composable
private fun modifiedColor(modified: Boolean) =
    if (modified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface

@Composable
private fun QuickSwitchRow(label: String, checked: Boolean, modified: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = modifiedColor(modified))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QuickNumberRow(label: String, value: String, unit: String, modified: Boolean, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = modifiedColor(modified))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            suffix = { Text(unit) },
            isError = text.toDoubleOrNull() == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.toDoubleOrNull() != null) onChange(text) }),
            modifier = Modifier.width(140.dp).onFocusChanged { if (!it.isFocused && text != value && text.toDoubleOrNull() != null) onChange(text) },
        )
    }
}

private fun List<PresetRef>.toPickerItems() =
    map { PickerItem(it.name, it.name, if (it.system) it.vendor else "Eigene") }.sortedWith(compareBy({ it.group }, { it.label }))

private fun formatDuration(seconds: Double): String {
    val s = seconds.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h} h ${m} min" else "${m} min ${s % 60} s"
}
