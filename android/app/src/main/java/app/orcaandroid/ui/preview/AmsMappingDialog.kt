package app.orcaandroid.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.orcaandroid.net.PrinterTray
import app.orcaandroid.ui.AmsPrompt
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.prepare.ColorDot

/** The desktop print dialog's filament mapping: one tray per used filament, pre-filled automatically. */
@Composable
fun AmsMappingDialog(prompt: AmsPrompt, vm: AppViewModel) {
    var choice by remember(prompt) { mutableStateOf(prompt.choice) }
    AlertDialog(
        onDismissRequest = vm.device::cancelPrint,
        title = { Text(stringResource(R.string.ams_mapping_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.used.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorDot(f.color, 18)
                        Text("${f.id + 1} · ${f.type}", Modifier.weight(1f))
                        TrayPicker(choice[f.id], prompt.trays) { t -> choice = choice + (f.id to t) }
                    }
                }
                if (prompt.used.any { it.id !in choice })
                    Text(stringResource(R.string.ams_mapping_missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.device.confirmPrint(choice) }, enabled = prompt.used.all { it.id in choice }) {
                Text(stringResource(R.string.print))
            }
        },
        dismissButton = { TextButton(onClick = vm.device::cancelPrint) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TrayPicker(selected: PrinterTray?, trays: List<PrinterTray>, onPick: (PrinterTray) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            if (selected == null) Text(stringResource(R.string.ams_mapping_pick))
            else TrayLabel(selected)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            trays.forEach { t ->
                DropdownMenuItem(text = { TrayLabel(t) }, onClick = { open = false; onPick(t) })
            }
        }
    }
}

@Composable
private fun TrayLabel(t: PrinterTray) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    ColorDot(t.color)
    Text("${t.name} · ${t.subBrand.ifBlank { t.type }}")
}
