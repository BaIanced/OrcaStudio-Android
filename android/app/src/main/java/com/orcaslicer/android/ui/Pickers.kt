package com.orcaslicer.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** An entry in a [PickerField]; [group] is shown as a section header (e.g. the vendor). */
data class PickerItem(val id: String, val label: String = id, val group: String = "")

/** A labelled field that opens a searchable single-choice dialog. */
@Composable
fun PickerField(
    label: String,
    selected: String?,
    items: List<PickerItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedCard(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled && items.isNotEmpty()) { open = true },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                items.firstOrNull { it.id == selected }?.label ?: selected ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (open) {
        PickerDialog(label, selected, items, onDismiss = { open = false }) {
            open = false
            onSelect(it)
        }
    }
}

@Composable
fun PickerDialog(
    title: String,
    selected: String?,
    items: List<PickerItem>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        items.filter { item -> words.all { w -> item.label.lowercase().contains(w) || item.group.lowercase().contains(w) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Suchen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 480.dp).padding(top = 8.dp)) {
                    var lastGroup: String? = null
                    filtered.forEach { item ->
                        if (item.group.isNotEmpty() && item.group != lastGroup) {
                            lastGroup = item.group
                            item(key = "g:${item.group}") {
                                Text(
                                    item.group,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                                HorizontalDivider()
                            }
                        }
                        item(key = "i:${item.id}") {
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(item.id) }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = item.id == selected, onClick = { onPick(item.id) })
                                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
