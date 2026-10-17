package app.orcaandroid.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.orcaandroid.R
import app.orcaandroid.net.PrinterControl
import app.orcaandroid.net.PrinterModel
import app.orcaandroid.net.PrinterStatus
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.prepare.SwitchRow
import java.util.Locale
import kotlin.math.roundToInt

/** The nozzle must be at least this hot (°C) before the extruder moves; the printer refuses colder moves. */
private const val EXTRUDE_MIN_TEMP = 170f

private enum class TempTarget { NOZZLE, BED }

/** Bambu: manual printer controls like the desktop's and Bambu Handy's Control page: temperatures, extruder, speed, light, fan, XYZ. */
@Composable
internal fun PrinterControlCard(status: PrinterStatus, serial: String, vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<TempTarget?>(null) }
    val send: (PrinterControl) -> Unit = { vm.device.sendControl(it) }
    PanelCard(stringResource(R.string.control_title)) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.control_tab_main)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.control_tab_xyz)) })
        }
        if (tab == 0) MainControls(status, send) { editing = it } else MoveControls(status, serial, send)
    }
    editing?.let { target ->
        val (label, current) = when (target) {
            TempTarget.NOZZLE -> stringResource(R.string.device_nozzle) to status.nozzleTarget
            TempTarget.BED -> stringResource(R.string.device_bed) to status.bedTarget
        }
        TempDialog(label, current, onDismiss = { editing = null }) { celsius ->
            editing = null
            send(if (target == TempTarget.NOZZLE) PrinterControl.nozzleTemp(celsius) else PrinterControl.bedTemp(celsius))
        }
    }
}

@Composable
private fun MainControls(status: PrinterStatus, send: (PrinterControl) -> Unit, edit: (TempTarget) -> Unit) {
    val canExtrude = !status.isActive && (status.nozzleTemp ?: 0f) >= EXTRUDE_MIN_TEMP
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TempTile(stringResource(R.string.device_nozzle), status.nozzleTemp, status.nozzleTarget, Modifier.weight(1f)) { edit(TempTarget.NOZZLE) }
            IconButton({ send(PrinterControl.extrude(-10.0)) }, enabled = canExtrude) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.control_retract))
            }
            IconButton({ send(PrinterControl.extrude(10.0)) }, enabled = canExtrude) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.control_extrude))
            }
        }
        if (!canExtrude) Text(
            stringResource(if (status.isActive) R.string.control_busy_print else R.string.control_cold_nozzle),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TempTile(stringResource(R.string.device_bed), status.bedTemp, status.bedTarget, Modifier.fillMaxWidth()) { edit(TempTarget.BED) }

        Text(stringResource(R.string.control_speed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                PrinterControl.SPEED_SILENT to R.string.control_speed_silent,
                PrinterControl.SPEED_STANDARD to R.string.control_speed_standard,
                PrinterControl.SPEED_SPORT to R.string.control_speed_sport,
                PrinterControl.SPEED_LUDICROUS to R.string.control_speed_ludicrous,
            ).forEach { (level, label) ->
                FilterChip(
                    status.speedLevel == level, { send(PrinterControl.speed(level)) },
                    label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        status.light?.let { on -> SwitchRow(stringResource(R.string.control_light), on) { send(PrinterControl.light(it)) } }

        val reported = status.partFan
        var fan by remember(reported) { mutableFloatStateOf((reported ?: 0).toFloat()) }
        Text(stringResource(R.string.control_part_fan, fan.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(
            fan, { fan = it }, enabled = reported != null, valueRange = 0f..100f,
            onValueChangeFinished = { send(PrinterControl.partFan(fan.roundToInt())) },
        )
    }
}

@Composable
private fun MoveControls(status: PrinterStatus, serial: String, send: (PrinterControl) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(10) }
    val coreXy = PrinterModel.isCoreXy(serial)
    val enabled = !status.isActive && coreXy != null
    // The values are the desktop's (StatusPanel.cpp on_axis_ctrl_*): Y up +, X left -, Z up -.
    fun jog(axis: Char, sign: Int): () -> Unit = { coreXy?.let { send(PrinterControl.jog(axis, sign * step.toDouble(), it)) } }
    val button = Modifier.width(88.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 10).forEach { mm ->
                FilterChip(step == mm, { step = mm }, label = { Text(stringResource(R.string.control_step, mm.toString())) })
            }
        }
        OutlinedButton(jog('Y', 1), button, enabled = enabled) { Text("Y+") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(jog('X', -1), button, enabled = enabled) { Text("X−") }
            OutlinedButton({ send(PrinterControl.home()) }, button, enabled = enabled) { Text(stringResource(R.string.control_home)) }
            OutlinedButton(jog('X', 1), button, enabled = enabled) { Text("X+") }
        }
        OutlinedButton(jog('Y', -1), button, enabled = enabled) { Text("Y−") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(jog('Z', -1), button, enabled = enabled) { Text("Z+") }
            OutlinedButton(jog('Z', 1), button, enabled = enabled) { Text("Z−") }
        }
        val hint = when {
            status.isActive -> R.string.control_busy_print
            coreXy == null -> R.string.control_unknown_model
            else -> null
        }
        hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** "215° / 220°" (current / target); a tap opens the target-temperature dialog. */
@Composable
private fun TempTile(label: String, current: Float?, target: Float?, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text((current?.let { "%.0f°".format(Locale.ROOT, it) } ?: "—") +
            (target?.takeIf { it > 0f }?.let { " / %.0f°".format(Locale.ROOT, it) } ?: ""),
            style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TempDialog(label: String, current: Float?, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(current?.roundToInt()?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.control_set_temp, label)) },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(3) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton({ onSet(text.toIntOrNull() ?: 0) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
