package com.orcaslicer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orcaslicer.android.core.OptionDef
import com.orcaslicer.android.core.PresetType
import com.orcaslicer.android.core.SettingsGroup
import com.orcaslicer.android.core.SettingsPage
import com.orcaslicer.android.core.Translator

private val TYPE_TITLES = mapOf(PresetType.PRINT to "Prozess", PresetType.FILAMENT to "Filament", PresetType.PRINTER to "Drucker")

/** Full settings editor for the selected process, filament and printer presets. */
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    val type = state.settingsType ?: return
    val tr = vm.translator
    var defs by remember(type) { mutableStateOf<Map<String, OptionDef>>(emptyMap()) }
    LaunchedEffect(type) {
        runCatching { vm.optionDefs(type) }.onSuccess { list -> defs = list.associateBy { it.key } }
            .onFailure { vm.showError(it.message ?: it.toString()) }
    }
    val pages = remember(type, defs) { if (defs.isEmpty()) emptyList() else buildPages(vm.settingsLayout(type), defs) }
    var mode by rememberSaveable { mutableIntStateOf(1) }
    var query by remember(type) { mutableStateOf("") }
    var pageIndex by remember(type) { mutableIntStateOf(0) }
    var saveDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val modifiedCount = state.overrides[type]?.size ?: 0

    Column(Modifier.fillMaxSize()) {
        // Header: navigation, preset name, actions.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeSettings) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Column(Modifier.weight(1f)) {
                Text("Einstellungen", style = MaterialTheme.typography.titleLarge)
                Text(
                    (state.presetName(type) ?: "—") + if (modifiedCount > 0) "  ·  $modifiedCount geändert" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (modifiedCount > 0) TextButton(onClick = { vm.resetAll(type) }) { Text("Alle zurücksetzen") }
            if (vm.isUserPreset(type)) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Preset löschen") }
            }
            Button(onClick = { saveDialog = true }, modifier = Modifier.padding(start = 8.dp)) { Text("Speichern als …") }
        }
        PrimaryTabRow(selectedTabIndex = type.ordinal) {
            PresetType.entries.forEach { t ->
                Tab(selected = t == type, onClick = { vm.openSettings(t) }, text = { Text(TYPE_TITLES.getValue(t)) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Einstellung suchen") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            SingleChoiceSegmentedButtonRow {
                listOf("Einfach", "Erweitert", "Experte").forEachIndexed { i, label ->
                    SegmentedButton(mode == i, { mode = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
        }
        HorizontalDivider()

        fun visible(def: OptionDef) = def.mode <= mode || (mode == 2 && def.mode == 3)

        Row(Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                LazyColumn(Modifier.width(220.dp).fillMaxHeight().padding(vertical = 8.dp)) {
                    itemsIndexed(pages) { i, page ->
                        val selected = i == pageIndex
                        Text(
                            tr.tr(page.title),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    MaterialTheme.shapes.medium,
                                )
                                .clickable { pageIndex = i }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            val groups: List<SettingsGroup> = if (query.isBlank()) {
                pages.getOrNull(pageIndex)?.groups.orEmpty()
            } else {
                val q = query.trim().lowercase()
                pages.flatMap { page ->
                    page.groups.mapNotNull { g ->
                        val hits = g.options.filter { key ->
                            val d = defs[key] ?: return@filter false
                            listOf(key, d.label, d.fullLabel, tr.tr(d.label), tr.tr(d.fullLabel), tr.tr(d.tooltip))
                                .any { it.lowercase().contains(q) }
                        }
                        if (hits.isEmpty()) null else SettingsGroup("${tr.tr(page.title)} › ${tr.tr(g.title)}", hits)
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp)) {
                groups.forEach { group ->
                    val options = group.options.mapNotNull { defs[it] }
                        .filter { (query.isNotBlank() || visible(it)) && state.value(type, it.key) != null }
                    if (options.isEmpty()) return@forEach
                    item(key = "g:${group.title}") {
                        Text(
                            if (query.isBlank()) tr.tr(group.title) else group.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                        )
                        HorizontalDivider()
                    }
                    items(options, key = { "o:${group.title}:${it.key}" }) { def ->
                        OptionRow(
                            def = def,
                            value = state.value(type, def.key).orEmpty(),
                            modified = state.isModified(type, def.key),
                            tr = tr,
                            onChange = { vm.setOption(type, def.key, it) },
                            onReset = { vm.resetOption(type, def.key) },
                        )
                    }
                }
                item { Spacer(Modifier.heightIn(min = 48.dp)) }
            }
        }
    }

    if (saveDialog) {
        SavePresetDialog(
            suggested = suggestUserName(state.presetName(type).orEmpty()),
            onDismiss = { saveDialog = false },
            onSave = { name ->
                saveDialog = false
                vm.savePresetAs(type, name)
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Preset löschen?") },
            text = { Text("„${state.presetName(type)}“ wird dauerhaft gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelectedPreset(type)
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

/** Desktop layout plus a trailing page for options the layout does not place. */
private fun buildPages(layout: List<SettingsPage>, defs: Map<String, OptionDef>): List<SettingsPage> {
    val placed = layout.flatMap { p -> p.groups.flatMap { it.options } }.toSet()
    val rest = defs.values.filter { it.key !in placed && !it.readonly }
    val extra = rest.groupBy { it.category.ifEmpty { "Others" } }.toSortedMap()
        .map { (category, list) -> SettingsGroup(category, list.map { it.key }) }
    return if (extra.isEmpty()) layout else layout + SettingsPage("Weitere Optionen", extra)
}

private fun suggestUserName(base: String): String {
    val stripped = base.substringBefore(" @").trim().ifEmpty { base }
    return "$stripped - Eigenes"
}

@Composable
private fun OptionRow(
    def: OptionDef,
    value: String,
    modified: Boolean,
    tr: Translator,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    var showTooltip by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).background(
                if (modified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface,
                CircleShape,
            ),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp)) {
            Text(tr.tr(def.label.ifEmpty { def.fullLabel.ifEmpty { def.key } }), style = MaterialTheme.typography.bodyLarge)
            Text(def.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Box(Modifier.width(320.dp)) {
            OptionEditor(def, value, tr, onChange)
        }
        IconButton(onClick = { showTooltip = true }, enabled = def.tooltip.isNotEmpty()) {
            Icon(Icons.Default.Info, "Info")
        }
        IconButton(onClick = onReset, enabled = modified) { Icon(Icons.Default.Refresh, "Zurücksetzen") }
    }
    if (showTooltip) {
        AlertDialog(
            onDismissRequest = { showTooltip = false },
            title = { Text(tr.tr(def.fullLabel.ifEmpty { def.label })) },
            text = { Text(tr.tr(def.tooltip)) },
            confirmButton = { TextButton(onClick = { showTooltip = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun OptionEditor(def: OptionDef, value: String, tr: Translator, onChange: (String) -> Unit) {
    // Vector options with several distinct entries (e.g. per-extruder values) are edited as raw text.
    val single = !def.isVector || !value.contains(',')
    when {
        def.readonly -> Text(value, style = MaterialTheme.typography.bodyMedium)
        def.baseType == "bool" && single -> Switch(checked = value == "1", onCheckedChange = { onChange(if (it) "1" else "0") })
        def.enumValues.isNotEmpty() && single -> EnumEditor(def, value, tr, onChange)
        def.isCode || def.multiline -> CodeEditor(tr.tr(def.label), value, onChange)
        else -> TextEditor(def, value, onChange)
    }
}

@Composable
private fun EnumEditor(def: OptionDef, value: String, tr: Translator, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val index = def.enumValues.indexOf(value)
    val label = if (index >= 0) tr.tr(def.enumLabels.getOrElse(index) { value }) else value
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            def.enumValues.forEachIndexed { i, v ->
                DropdownMenuItem(
                    text = { Text(tr.tr(def.enumLabels.getOrElse(i) { v })) },
                    onClick = {
                        open = false
                        onChange(v)
                    },
                )
            }
        }
    }
}

/** Text field that commits on "done" or when focus leaves, so each keystroke does not re-slice. */
@Composable
private fun TextEditor(def: OptionDef, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val numeric = def.baseType in setOf("int", "float", "percent", "float_or_percent")
    val commit = { if (text != value) onChange(text.trim()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        suffix = if (def.sidetext.isNotEmpty() && !def.sidetext.startsWith("%")) {
            { Text(def.sidetext, maxLines = 1) }
        } else null,
        isError = numeric && !isValidNumber(def, text.trim()),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric && !def.isVector) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
    )
}

private fun isValidNumber(def: OptionDef, text: String): Boolean {
    if (def.isVector) return true
    val number = text.removeSuffix("%").toDoubleOrNull() ?: return false
    if (text.endsWith("%") && def.baseType !in setOf("percent", "float_or_percent")) return false
    if (!text.endsWith("%")) {
        def.min?.let { if (number < it) return false }
        def.max?.let { if (number > it) return false }
    }
    return true
}

/** Multi-line values (G-code, notes, post-processing) open a full-size editor. */
@Composable
private fun CodeEditor(title: String, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(value.lineSequence().firstOrNull()?.ifEmpty { "(leer)" } ?: "(leer)", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) {
        // Values are stored escaped ("\n" as two characters); edit them as real lines.
        var text by remember { mutableStateOf(unescape(value)) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 600.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    onChange(escape(text))
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } },
        )
    }
}

/** Inverse of libslic3r's escape_string_cstyle for the characters G-code templates use. */
private fun unescape(s: String): String {
    val v = if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
    val out = StringBuilder()
    var i = 0
    while (i < v.length) {
        val c = v[i]
        if (c == '\\' && i + 1 < v.length) {
            when (v[i + 1]) {
                'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                '\\' -> out.append('\\'); '"' -> out.append('"')
                else -> out.append(c).append(v[i + 1])
            }
            i += 2
        } else {
            out.append(c); i++
        }
    }
    return out.toString()
}

private fun escape(s: String): String =
    s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"").replace("\t", "\\t")

@Composable
private fun SavePresetDialog(suggested: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Als eigenes Preset speichern") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
