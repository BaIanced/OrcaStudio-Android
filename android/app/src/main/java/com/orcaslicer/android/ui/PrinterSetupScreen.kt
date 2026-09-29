package com.orcaslicer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orcaslicer.android.core.Vendor
import com.orcaslicer.android.core.printerKey

/**
 * Printer selection in the spirit of the desktop setup wizard: pick models and nozzle sizes;
 * the vendors' profiles are installed as needed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrinterSetupScreen(
    vendors: List<Vendor>,
    selected: Set<String>,
    canCancel: Boolean,
    onApply: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var chosen by remember(selected) { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val all = vendors.filter { !it.required }
    val q = query.trim()
    val visible = all.filter { v -> q.isEmpty() || v.name.contains(q, true) || v.models.any { it.name.contains(q, true) } }
    var current by remember { mutableStateOf(all.firstOrNull { v -> v.models.any { m -> m.nozzles.any { printerKey(m.name, it) in selected } } }?.id) }
    val vendor = visible.firstOrNull { it.id == current } ?: visible.firstOrNull()

    fun countFor(v: Vendor) = v.models.sumOf { m -> m.nozzles.count { printerKey(m.name, it) in chosen } }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Drucker auswählen", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Wähle deine Drucker und Düsengrößen. Nur die Profile der gewählten Hersteller werden installiert – " +
                "das hält den Start auf dem Tablet schnell.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Hersteller oder Modell suchen") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.width(260.dp).fillMaxHeight()) {
                items(visible, key = { it.id }) { v ->
                    val isCurrent = v.id == vendor?.id
                    val count = countFor(v)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                MaterialTheme.shapes.medium,
                            )
                            .clickable { current = v.id }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(v.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (count > 0) Text("$count", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp)) {
                val models = vendor?.models.orEmpty().filter { q.isEmpty() || vendor!!.name.contains(q, true) || it.name.contains(q, true) }
                items(models, key = { it.name }) { model ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            model.nozzles.forEach { nozzle ->
                                val key = printerKey(model.name, nozzle)
                                FilterChip(
                                    selected = key in chosen,
                                    onClick = { chosen = if (key in chosen) chosen - key else chosen + key },
                                    label = { Text("$nozzle mm") },
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if (canCancel) TextButton(onClick = onCancel) { Text("Abbrechen") }
            Button(onClick = { onApply(chosen) }, enabled = chosen.isNotEmpty()) {
                Text("Übernehmen (${chosen.size} Drucker)")
            }
        }
    }
}
