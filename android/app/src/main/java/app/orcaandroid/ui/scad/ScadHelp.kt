package app.orcaandroid.ui.scad

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.scad.ScadExamples
import app.orcaandroid.scad.ScadHint
import app.orcaandroid.scad.ScadSnippet
import app.orcaandroid.scad.ScadSnippets

/** Chips above the code: one menu per snippet group, plus "Add a slider". */
@Composable
fun InsertBar(onInsert: (ScadSnippet) -> Unit, onAddSetting: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onAddSetting,
            label = { Text(stringResource(R.string.scad_add_setting)) },
            leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
        ScadSnippets.GROUPS.forEach { group ->
            var open by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { open = true },
                    label = { Text(stringResource(group.title)) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (group.items.any { it.wrap != null }) {
                        Text(stringResource(R.string.scad_insert_hint), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 6.dp))
                        HorizontalDivider()
                    }
                    group.items.forEach { sn ->
                        DropdownMenuItem(
                            text = {
                                Column(Modifier.widthIn(max = 300.dp).padding(vertical = 2.dp)) {
                                    Text(stringResource(sn.title), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(sn.description), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { open = false; onInsert(sn) },
                        )
                    }
                }
            }
        }
    }
}

/** Asks for a new Customizer slider: a name and its range. */
@Composable
fun AddSettingDialog(onDismiss: () -> Unit, onAdd: (label: String, mm: Boolean, value: Double, min: Double, max: Double, step: Double) -> Unit) {
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("10") }
    var min by remember { mutableStateOf("1") }
    var max by remember { mutableStateOf("100") }
    var step by remember { mutableStateOf("1") }
    var mm by remember { mutableStateOf(true) }
    fun num(t: String) = t.replace(',', '.').toDoubleOrNull()
    val v = num(value); val lo = num(min); val hi = num(max); val st = num(step)
    val valid = label.isNotBlank() && v != null && lo != null && hi != null && st != null && st > 0 && hi > lo
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scad_add_setting)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.scad_setting_intro), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.scad_setting_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.scad_setting_value)) },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f))
                    OutlinedTextField(step, { step = it }, label = { Text(stringResource(R.string.scad_setting_step)) },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(min, { min = it }, label = { Text(stringResource(R.string.scad_setting_min)) },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f))
                    OutlinedTextField(max, { max = it }, label = { Text(stringResource(R.string.scad_setting_max)) },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().clickable { mm = !mm }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(mm, { mm = it })
                    Text(stringResource(R.string.scad_setting_mm), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(label, mm, v!!.coerceIn(lo!!, hi!!), lo, hi, st!!) }, enabled = valid) {
                Text(stringResource(R.string.scad_setting_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** "New from template": cards with a title and what the template makes. */
@Composable
fun TemplateGallery(onDismiss: () -> Unit, onPick: (ScadExamples.Example) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scad_templates_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scad_templates_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp),
                    modifier = Modifier.heightIn(max = 460.dp),
                ) {
                    items(ScadExamples.ALL) { ex ->
                        Card(
                            onClick = { onPick(ex) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Column(Modifier.padding(12.dp).heightIn(min = 64.dp)) {
                                Text(stringResource(ex.title), style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.size(2.dp))
                                Text(stringResource(ex.description), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Plain-language tips for the console's errors; "Go to line" jumps into the code. */
@Composable
fun HintCards(hints: List<ScadHint>, onLine: (Int) -> Unit) {
    if (hints.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hints.forEach { h ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Lightbulb, null, Modifier.size(16.dp).padding(top = 2.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(h.text, *h.args.toTypedArray()), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        h.line?.let { line ->
                            Text(stringResource(R.string.scad_hint_line, line.toString()), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onLine(line) }.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}
