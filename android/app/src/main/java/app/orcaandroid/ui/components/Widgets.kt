package app.orcaandroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import java.util.Locale

/**
 * An entry in a [PickerField]; [group] is shown as a section header (e.g. "Vendor presets"),
 * [subgroup] and [detail] as nested sections (e.g. the brand, then the material). When any item
 * is nested, sections become collapsible.
 */
data class PickerItem(val id: String, val label: String = id, val group: String = "", val subgroup: String = "", val detail: String = "") {
    val path: List<String> get() = listOf(group, subgroup, detail).filter { it.isNotEmpty() }
}

/** A compact labelled field that opens a searchable single-choice dialog. */
@Composable
fun PickerField(
    label: String,
    selected: String?,
    items: List<PickerItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    modified: Boolean = false,
    openGroups: Set<String> = emptySet(),
) {
    var open by remember { mutableStateOf(false) }
    OutlinedCard(modifier = modifier.fillMaxWidth().clickable(enabled = enabled && items.isNotEmpty()) { open = true }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                if (modified) "$label · ${stringResource(R.string.modified)}" else label,
                style = MaterialTheme.typography.labelSmall,
                color = if (modified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Text(
                items.firstOrNull { it.id == selected }?.label ?: selected ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (open) PickerDialog(label, selected, items, onDismiss = { open = false }, openGroups = openGroups) { open = false; onSelect(it) }
}

/**
 * Searchable single choice. With nested items, sections start collapsed except the top-level
 * [openGroups] and the ones holding the selection; while searching, every section with a match is open.
 */
@Composable
fun PickerDialog(
    title: String,
    selected: String?,
    items: List<PickerItem>,
    onDismiss: () -> Unit,
    openGroups: Set<String> = emptySet(),
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        // LazyColumn keys must be unique.
        items.filter { item -> words.all { w -> (item.path + item.label).any { it.lowercase().contains(w) } } }
            .distinctBy { it.path to it.id }
    }
    val nested = remember(items) { items.any { it.path.size > 1 } }
    val current = remember(items, selected) { items.firstOrNull { it.id == selected } }
    val toggled = remember { mutableStateMapOf<String, Boolean>() }
    val searching = query.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (items.size > 8) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text(stringResource(R.string.search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 460.dp).padding(top = 8.dp)) {
                    // One section per path element; callers' lists are not always sorted by group (synced
                    // user presets land between system ones), so each section gathers all its items, in
                    // the order of their first appearance.
                    fun androidx.compose.foundation.lazy.LazyListScope.level(prefix: List<String>, levelItems: List<PickerItem>) {
                        val depth = prefix.size
                        levelItems.groupBy { it.path.getOrNull(depth) }.forEach { (name, sectionItems) ->
                            if (name == null) {
                                sectionItems.forEach { item ->
                                    item(key = "i:${item.path.joinToString("/")}:${item.id}") {
                                        Row(Modifier.fillMaxWidth().clickable { onPick(item.id) }.padding(start = (depth * 12).dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = item.id == selected, onClick = { onPick(item.id) })
                                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                                return@forEach
                            }
                            val path = prefix + name
                            val key = "g:" + path.joinToString("/")
                            val holdsSelection = current != null && current.path.take(path.size) == path
                            val open = !nested || searching || (toggled[key] ?: (holdsSelection || (depth == 0 && name in openGroups)))
                            item(key = key) {
                                SectionHeader(name, sectionItems.size, nested, open,
                                    if (depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                                    Modifier.padding(start = (depth * 12).dp)) { toggled[key] = !open }
                                if (depth == 0) HorizontalDivider()
                            }
                            if (open) level(path, sectionItems)
                        }
                    }
                    level(emptyList(), filtered)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A [PickerDialog] section title; collapsible ones show an arrow and their item count. */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    collapsible: Boolean,
    open: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clickable(enabled = collapsible, onClick = onToggle).padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsible)
            Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.primary)
        Text(if (collapsible) "$title ($count)" else title, style = style, color = MaterialTheme.colorScheme.primary)
    }
}

/** Numeric text field that commits on "done" or focus loss (so typing does not trigger work). */
@Composable
fun NumberField(
    value: Float,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    suffix: String? = null,
    decimals: Int = 2,
    live: Boolean = false,
) {
    val formatted = "%.${decimals}f".format(Locale.ROOT, value + 0f)
        .let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it }
        .let { if (it == "-0") "0" else it }
    var text by remember { mutableStateOf(formatted) }
    // Follow outside changes of the value, but not the echo of an edit ("0." stays while typing "0.5").
    LaunchedEffect(value) { if (text.replace(',', '.').toFloatOrNull() != value) text = formatted }
    var focused by remember { mutableStateOf(false) }
    // Only real edits count: the field shows a rounded value, which must not be written back.
    val commit = { if (text != formatted) text.replace(',', '.').toFloatOrNull()?.let { if (it != value) onCommit(it) } }
    OutlinedTextField(
        value = text,
        // live: every valid edit is committed at once, for dialogs whose confirm button reads the
        // value (a tap on it does not reliably move the focus first).
        onValueChange = { text = it; if (live) commit() },
        label = label?.let { { Text(it, maxLines = 1) } },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        isError = text.replace(',', '.').toFloatOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged { if (focused && !it.isFocused) commit(); focused = it.isFocused },
    )
}

/** Three numeric fields in a row (x / y / z). */
@Composable
fun Vec3Fields(
    label: String, x: Float, y: Float, z: Float, suffix: String, onCommit: (Float, Float, Float) -> Unit,
    decimals: Int = 2, live: Boolean = false,
) {
    // The unit goes in the row label: three narrow fields cut off values like "115.09" next to a suffix.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label ($suffix)", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField(x, { onCommit(it, y, z) }, Modifier.weight(1f), "X", null, decimals, live)
            NumberField(y, { onCommit(x, it, z) }, Modifier.weight(1f), "Y", null, decimals, live)
            NumberField(z, { onCommit(x, y, it) }, Modifier.weight(1f), "Z", null, decimals, live)
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Formats seconds as "2 h 5 min" / "5 min 3 s". */
fun formatDuration(seconds: Double): String {
    val s = seconds.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "$h h $m min" else "$m min ${s % 60} s"
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
