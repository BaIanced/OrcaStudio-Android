package app.orcaandroid.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.Vendor
import app.orcaandroid.core.printerKey
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Phase
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.WIDE_LAYOUT

/**
 * Printer selection in the spirit of the desktop setup wizard: vendor → model → nozzle sizes.
 * Only the profiles of the chosen vendors are installed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrinterSetupScreen(state: UiState, vm: AppViewModel) {
    val canCancel = state.phase == Phase.READY
    var chosen by remember(state.selectedPrinters) { mutableStateOf(state.selectedPrinters) }
    var query by remember { mutableStateOf("") }
    val all = state.vendors.filter { !it.required }
    val q = query.trim()
    val visible = all.filter { v -> q.isEmpty() || v.name.contains(q, true) || v.models.any { it.name.contains(q, true) } }
    var current by remember { mutableStateOf<String?>(null) }
    val vendor = visible.firstOrNull { it.id == current }

    fun countFor(v: Vendor) = v.models.sumOf { m -> m.nozzles.count { printerKey(m.name, it) in chosen } }

    BackHandler(enabled = canCancel || current != null) { if (current != null) current = null else vm.closePrinterSetup() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT
        Column(Modifier.fillMaxSize().padding(if (wide) 24.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!wide && current != null) IconButton(onClick = { current = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(if (!wide && vendor != null) vendor.name else stringResource(R.string.select_printers),
                    style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
            }
            if (wide || current == null) Text(stringResource(R.string.select_printers_text), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_printer)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            val vendorList: @Composable (Modifier) -> Unit = { m ->
                LazyColumn(m) {
                    items(visible, key = { it.id }) { v ->
                        val isCurrent = wide && v.id == (vendor ?: visible.firstOrNull())?.id
                        val count = countFor(v)
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                                .clickable { current = v.id }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(v.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (count > 0) Text("$count", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            if (!wide) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                }
            }
            val modelList: @Composable (Vendor?, Modifier) -> Unit = { v, m ->
                LazyColumn(m) {
                    val models = v?.models.orEmpty().filter { q.isEmpty() || v!!.name.contains(q, true) || it.name.contains(q, true) }
                    items(models, key = { it.name }) { model ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(model.name, style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                model.nozzles.forEach { nozzle ->
                                    val key = printerKey(model.name, nozzle)
                                    FilterChip(key in chosen, { chosen = if (key in chosen) chosen - key else chosen + key }, label = { Text("$nozzle mm") })
                                }
                            }
                            HorizontalDivider(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    vendorList(Modifier.width(260.dp).fillMaxHeight())
                    modelList(vendor ?: visible.firstOrNull(), Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp))
                }
            } else if (vendor == null) {
                vendorList(Modifier.weight(1f).fillMaxWidth())
            } else {
                modelList(vendor, Modifier.weight(1f).fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (canCancel) TextButton(onClick = vm::closePrinterSetup) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { vm.applyPrinterSelection(chosen) }, enabled = chosen.isNotEmpty()) {
                    Text(stringResource(R.string.apply_n_printers, chosen.size))
                }
            }
        }
    }
}
