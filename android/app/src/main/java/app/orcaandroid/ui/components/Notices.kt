package app.orcaandroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Screen
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.settings.engineErrorText

/** One message group on the 3D view: [key] changes when its content does, so new messages show again after a close. */
private data class Notice(val key: String, val title: String, val lines: List<Pair<String, Boolean>>)

/**
 * Slicer errors / warnings and printer errors front and center on the 3D view (user 2026-10-19). Closing the card
 * leaves a red "!" in a circle at the same spot that brings it back; a new message shows the card again.
 */
@Composable
fun Notices(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val notices = buildList {
        state.sliceError?.let { (plate, message) ->
            add(Notice("slice:$plate:$message", stringResource(R.string.notice_slice_failed, plate + 1),
                listOf(engineErrorText(message, vm.translator) to true)))
        }
        val result = state.shownResult
        if (state.screen == Screen.PREVIEW && result != null && result.warnings.isNotEmpty()) {
            add(Notice("warn:${state.previewPlate}:${result.gcodeFile}:${result.warnings.hashCode()}", stringResource(R.string.notice_slicer_warnings),
                // Mixed temperatures the user has allowed are only a heads-up (as in the preview panel).
                result.warnings.map { vm.translator.tr(it) to !(state.allowMixedTemps && it.startsWith("Selected nozzle temperatures are incompatible")) }))
        }
        val alerts = state.printerStatus?.alerts.orEmpty()
        if (alerts.isNotEmpty()) {
            add(Notice("printer:" + alerts.joinToString { it.code }, stringResource(R.string.notice_printer),
                alerts.map { (it.text ?: stringResource(R.string.printer_error_code, it.display)) to true }))
        }
    }
    if (notices.isEmpty()) return
    var closed by remember { mutableStateOf(emptySet<String>()) }
    val open = notices.filter { it.key !in closed }
    if (open.isEmpty()) {
        // Tap re-opens; long-press clears the slice error for good (the other notices follow their own source).
        Surface(modifier = modifier.size(40.dp).clip(CircleShape).combinedClickable(onLongClick = { vm.dismissSliceError() }) { closed = emptySet() },
            shape = CircleShape, color = Color(0xFFD32F2F),
            contentColor = Color.White, border = BorderStroke(2.dp, Color.White), shadowElevation = 6.dp) {
            Icon(Icons.Default.PriorityHigh, stringResource(R.string.notice_show), Modifier.padding(6.dp))
        }
        return
    }
    val red = MaterialTheme.colorScheme.error
    Surface(modifier.widthIn(max = 520.dp).padding(horizontal = 8.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(2.dp, red), shadowElevation = 8.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f, fill = false).heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                open.forEach { n ->
                    Text(n.title, color = red, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    n.lines.forEach { (text, important) ->
                        Text(text, style = MaterialTheme.typography.bodyMedium,
                            color = if (important) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            // X closes the card; a slice error is then gone for good (nothing left to re-open), the other notices stay as the "!" badge.
            IconButton(onClick = {
                closed = closed + open.map { it.key }
                if (open.any { it.key.startsWith("slice:") }) vm.dismissSliceError()
            }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
    }
}
