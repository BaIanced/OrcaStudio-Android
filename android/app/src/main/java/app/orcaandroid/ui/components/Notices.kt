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
import androidx.compose.material3.TextButton
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
import app.orcaandroid.ui.EditorTarget
import app.orcaandroid.ui.settings.NoticeFix
import app.orcaandroid.ui.settings.engineErrorText
import app.orcaandroid.ui.settings.noticeFix
import app.orcaandroid.ui.prepare.Tip

/** One message group on the 3D view: [key] changes when its content does, so new messages show again after a close. */
private data class Notice(val key: String, val title: String, val lines: List<Line>, val fix: NoticeFix? = null, val buttons: List<Pair<Int, () -> Unit>> = emptyList())

/** One message; [fix] adds a "Fix" link that opens the setting (or screen) that resolves it. */
private data class Line(val text: String, val important: Boolean, val fix: NoticeFix? = null)

/**
 * Slicer errors / warnings and printer errors front and center on the 3D view (user 2026-10-19). Closing the card
 * leaves a red "!" in a circle at the same spot that brings it back; a new message shows the card again.
 */
@Composable
fun Notices(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val notices = buildList {
        // Fit first: an oversized object is usually the cause of the slice error below it, and its Fix
        // stayed below the card's scroll fold when listed second (WSA r524, 2026-11-04).
        state.slicing.fitIssue?.let { f ->
            fun mm(v: List<Float>) = v.joinToString(" x ") { kotlin.math.round(it).toInt().toString() }
            val hint = when (f.fix) {
                "turn" -> R.string.fit_turn
                "orient" -> R.string.fit_orient
                "lay" -> R.string.fit_lay
                else -> R.string.fit_none
            }
            add(Notice("fit:${f.obj}:${f.instance}:${f.size}", stringResource(R.string.fit_title), listOf(
                Line(stringResource(R.string.fit_size, f.name, mm(f.size), mm(f.bed)), true),
                Line(stringResource(hint), false, if (f.fix != null) NoticeFix.Fit(f) else null))))
        }
        if (state.chrome.sessionBad) {
            add(Notice("session", stringResource(R.string.session_bad_title), listOf(Line(stringResource(R.string.session_bad_text), true)),
                buttons = listOf(R.string.session_try_again to { vm.files.retryBadSession() }, R.string.session_discard to { vm.files.discardBadSession() })))
        }
        state.slicing.sliceError?.let { (plate, message) ->
            add(Notice("slice:$plate:$message", stringResource(R.string.notice_slice_failed, plate + 1),
                listOf(Line(engineErrorText(message, vm.translator), true, noticeFix(message)))))
        }
        val result = state.slicing.shownResult
        if (state.chrome.screen == Screen.PREVIEW && result != null && result.warnings.isNotEmpty()) {
            add(Notice("warn:${state.slicing.previewPlate}:${result.gcodeFile}:${result.warnings.hashCode()}", stringResource(R.string.notice_slicer_warnings),
                // Mixed temperatures the user has allowed are only a heads-up (as in the preview panel).
                result.warnings.map { Line(vm.translator.tr(it), !(state.allowMixedTemps && it.startsWith("Selected nozzle temperatures are incompatible")), noticeFix(it)) }))
        }
        val alerts = state.printerStatus?.alerts.orEmpty()
        if (alerts.isNotEmpty()) {
            add(Notice("printer:" + alerts.joinToString { it.code }, stringResource(R.string.notice_printer),
                alerts.map { Line(it.text ?: stringResource(R.string.printer_error_code, it.display), true) }, NoticeFix.Go(Screen.DEVICE)))
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
                    n.lines.forEach { line ->
                        Text(line.text, style = MaterialTheme.typography.bodyMedium,
                            color = if (line.important) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary)
                        line.fix?.let { FixLink(it, vm, R.string.notice_fix) }
                    }
                    n.fix?.let { FixLink(it, vm, R.string.notice_open_printer) }
                    if (n.buttons.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        n.buttons.forEach { (label, run) ->
                            TextButton(onClick = run, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                                Text(stringResource(label), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            // X closes the card; a slice error is then gone for good (nothing left to re-open), the other notices stay as the "!" badge.
            Tip(stringResource(R.string.close)) { IconButton(onClick = {
                closed = closed + open.map { it.key }
                if (open.any { it.key.startsWith("slice:") }) vm.dismissSliceError()
                if (open.any { it.key.startsWith("fit:") }) vm.dismissFit()
            }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } }
        }
    }
}

/** Opens the option the message points at (editor searched for its key) or the screen that handles it. */
@Composable
private fun FixLink(fix: NoticeFix, vm: AppViewModel, label: Int) {
    TextButton(onClick = {
        when (fix) {
            is NoticeFix.Option -> vm.presets.openEditor(EditorTarget.Preset(fix.type, focus = fix.key))
            is NoticeFix.Go -> vm.setScreen(fix.screen)
            is NoticeFix.Fit -> vm.scene.fitToPrinter(fix.issue)
        }
    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
        Text(stringResource(label), fontWeight = FontWeight.Bold)
    }
}
