package app.orcaandroid.ui.prepare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.orcaandroid.R

/** Common filament colours (roughly a basic PLA range), light to dark per hue family. */
private val PALETTE = listOf(
    "#FFFFFF", "#D3D3D3", "#A6A9AA", "#808080", "#545454", "#202020", "#000000", "#C0C0C0",
    "#FFFF66", "#FFD700", "#F4A925", "#FF7F27", "#EF3F3F", "#C12E1F", "#8B0000", "#FF69B4",
    "#F5B6CD", "#E4BDD0", "#8F3FDF", "#5E43B7", "#00008B", "#0A2989", "#2F7FEF", "#56B7E6",
    "#40E0D0", "#00AE42", "#2FBF4F", "#006400", "#A3D86F", "#D2B48C", "#A0522D", "#5C3A21",
)

private val HEX = Regex("#[0-9a-fA-F]{6}")

private fun hsvOf(hex: String) = FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), it) }
private fun hexOf(h: Float, s: Float, v: Float) = "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF)
private fun colorOf(hex: String) = Color(android.graphics.Color.parseColor(hex))

/**
 * Filament colour picker: a saturation / brightness field and a hue bar (drag or tap), the old colour beside
 * the new one (tap the old half to go back), the colours already in use on other slots, common filament
 * colours and a hex field. [inUse] = the other slots' colours.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorDialog(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit, inUse: List<String> = emptyList()) {
    val start = current?.takeIf { HEX.matches(it) }?.uppercase() ?: "#FF7F27"
    var hex by remember { mutableStateOf(start) }
    var text by remember { mutableStateOf(start) }
    // Hue and saturation are kept apart from the hex so they survive black / grey (where hex loses them).
    val hsv0 = remember { hsvOf(start) }
    var h by remember { mutableFloatStateOf(hsv0[0]) }
    var s by remember { mutableFloatStateOf(hsv0[1]) }
    var v by remember { mutableFloatStateOf(hsv0[2]) }
    fun fromHsv() { hex = hexOf(h, s, v); text = hex }
    fun set(c: String) { hex = c.uppercase(); text = hex; hsvOf(hex).let { h = it[0]; s = it[1]; v = it[2] } }
    val outline = MaterialTheme.colorScheme.outline
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.width(88.dp).height(48.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, outline, RoundedCornerShape(10.dp))) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(colorOf(start)).clickable { set(start) })
                        Box(Modifier.weight(1f).fillMaxHeight().background(colorOf(hex)))
                    }
                    OutlinedTextField(text, { t ->
                        text = t
                        val c = if (t.startsWith("#")) t else "#$t"
                        if (HEX.matches(c)) { hex = c.uppercase(); hsvOf(hex).let { h = it[0]; s = it[1]; v = it[2] } }
                    }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.design_hex)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                }
                // Saturation left to right, brightness top to bottom.
                val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)))
                fun sv(p: Offset, w: Float, ht: Float) { s = (p.x / w).coerceIn(0f, 1f); v = 1f - (p.y / ht).coerceIn(0f, 1f); fromHsv() }
                Canvas(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp))
                    .pointerInput(Unit) { detectTapGestures { sv(it, size.width.toFloat(), size.height.toFloat()) } }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { sv(it, size.width.toFloat(), size.height.toFloat()) }) { c, _ ->
                            sv(c.position, size.width.toFloat(), size.height.toFloat())
                        }
                    }) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val p = Offset(s * size.width, (1f - v) * size.height)
                    drawCircle(Color.Black, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                }
                val hues = remember { (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) } }
                fun hue(x: Float, w: Float) { h = (x / w).coerceIn(0f, 1f) * 359.9f; fromHsv() }
                Canvas(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))
                    .pointerInput(Unit) { detectTapGestures { hue(it.x, size.width.toFloat()) } }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { hue(it.x, size.width.toFloat()) }) { c, _ -> hue(c.position.x, size.width.toFloat()) }
                    }) {
                    drawRect(Brush.horizontalGradient(hues))
                    val p = Offset(h / 360f * size.width, size.height / 2)
                    drawCircle(Color.Black, size.height / 2 - 2.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                }
                @Composable fun swatches(list: List<String>) = FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { c ->
                        val on = c.equals(hex, true)
                        Box(Modifier.size(30.dp).clip(CircleShape).background(colorOf(c))
                            .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else outline, CircleShape)
                            .clickable { set(c) })
                    }
                }
                val used = inUse.filter { HEX.matches(it) }.map { it.uppercase() }.distinct()
                if (used.isNotEmpty()) {
                    Text(stringResource(R.string.color_in_use), style = MaterialTheme.typography.labelMedium)
                    swatches(used)
                }
                swatches(PALETTE)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onPick(hex) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
