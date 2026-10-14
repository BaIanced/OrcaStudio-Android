package app.orcaandroid.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import kotlin.math.roundToInt

/**
 * Design mode (user, 2026-10-14): a floating, draggable panel over the real app for picking a preset and
 * changing the colours of each part of the app live. Edits the current mode (light or dark); the panel itself
 * keeps the plain Prusa colours so it stays readable whatever is picked.
 */
@Composable
fun ThemeDesignPanel(state: UiState, vm: AppViewModel) {
    val colors = LocalAppColors.current
    val dark = colors.dark
    val spec = state.themeSpec
    var region by rememberSaveable { mutableStateOf(Region.GLOBAL) }
    var slotKey by rememberSaveable { mutableStateOf(Region.GLOBAL.slots.first().key) }
    val slot = region.slots.firstOrNull { it.key == slotKey } ?: region.slots.first()
    var minimized by rememberSaveable { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }

    fun set(c: Color?, persist: Boolean = true) = vm.setThemeSpec(spec.with(dark, region.key, slot.key, c?.toArgb()), persist)

    MaterialTheme(colorScheme = plainScheme(dark)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = (maxWidth - 16.dp).coerceAtMost(360.dp)
            val listHeight = maxHeight * 0.55f
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).padding(bottom = 72.dp)
                    .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }.width(width),
                // Plain container colour: tonal elevation would tint the panel with the accent.
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    // Header: drag it to move the panel out of the way.
                    Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectDragGestures { change, amount -> change.consume(); drag += amount }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DragIndicator, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Default.Palette, null, Modifier.padding(start = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(stringResource(R.string.design_mode), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(if (dark) R.string.design_editing_dark else R.string.design_editing_light),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ minimized = !minimized }) {
                            Icon(if (minimized) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(if (minimized) R.string.design_unfold else R.string.design_minimize))
                        }
                        IconButton({ vm.setDesignMode(false) }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    }
                    if (!minimized) Column(Modifier.heightIn(max = listHeight).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PresetChips(state, vm, dark)
                        RegionPicker(region, colors) { region = it; slotKey = it.slots.first().key }
                        // One row of colour chips keeps the sliders in view without scrolling.
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            region.slots.forEach { s ->
                                SlotChip(s, colors.color(region, s), colors.isCustom(region, s), s.key == slot.key) { slotKey = s.key }
                            }
                        }
                        if (slot.hint != 0) Text(stringResource(slot.hint), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (colors.lowContrast(region)) Text(stringResource(R.string.design_low_contrast),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        ColorEditor(
                            key = region.key + "/" + slot.key,
                            color = colors.color(region, slot),
                            custom = colors.isCustom(region, slot),
                            onChange = { c, done -> set(c, done) },
                            onReset = { set(null) },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton({ vm.setThemeSpec(spec.reset(dark, region.key)) }, enabled = colors.customCount(region) > 0) {
                                Text(stringResource(R.string.design_reset_part))
                            }
                            TextButton({ vm.setThemeSpec(spec.reset(dark)) }, enabled = spec.customCount(dark) > 0) {
                                Text(stringResource(R.string.design_reset_all))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChips(state: UiState, vm: AppViewModel, dark: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemePreset.entries.forEach { p ->
            FilterChip(
                selected = !state.dynamicColor && state.themeSpec.preset == p.key,
                onClick = { vm.setThemePreset(p.key) },
                label = { Text(stringResource(p.label)) },
                leadingIcon = { PresetSwatches(p, dark, 10) },
            )
        }
    }
}

/** A preset's main colours as a row of small dots. */
@Composable
fun PresetSwatches(p: ThemePreset, dark: Boolean, sizeDp: Int) {
    val s = remember(p, dark) { p.scheme(dark) }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(s.background, s.surfaceContainerHigh, s.onSurface, s.primary, s.tertiary).forEach {
            Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(it).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }
}

@Composable
private fun RegionPicker(region: Region, colors: AppColors, onPick: (Region) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.design_part), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(region.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            Region.entries.forEach { r ->
                val n = colors.customCount(r)
                DropdownMenuItem(
                    text = { Text(stringResource(r.label) + if (n > 0) "  •$n" else "") },
                    onClick = { open = false; onPick(r) },
                )
            }
        }
    }
}

/** A colour slot as a chip: its colour, its name, and "custom" when changed. */
@Composable
private fun SlotChip(slot: Slot, color: Color, custom: Boolean, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(stringResource(slot.label) + if (custom) " •" else "", maxLines = 1) },
        leadingIcon = {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(color).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)))
        },
    )
}

/** Ready-made colours: every preset's palette in both modes, for a quick start. */
private val SWATCHES: List<Color> by lazy {
    ThemePreset.entries.flatMap { p -> listOf(true, false).flatMap { d -> p.scheme(d).let { listOf(it.primary, it.tertiary, it.background, it.surfaceContainerHigh, it.onSurface) } } }
        .distinctBy { it.toArgb() } + listOf(Color.White, Color.Black, Color(0xFF00AE42), Color(0xFF009688), Color(0xFFE53935), Color(0xFF8E24AA))
}

/**
 * Hue / saturation / brightness sliders, a hex field and swatches. [onChange] gets done = false while a slider
 * moves (shown live, saved when let go).
 */
@Composable
private fun ColorEditor(key: String, color: Color, custom: Boolean, onChange: (Color, Boolean) -> Unit, onReset: () -> Unit) {
    fun hsvOf(c: Color) = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
    var h by remember(key) { mutableFloatStateOf(hsvOf(color)[0]) }
    var s by remember(key) { mutableFloatStateOf(hsvOf(color)[1]) }
    var v by remember(key) { mutableFloatStateOf(hsvOf(color)[2]) }
    fun hsv(hh: Float = h, ss: Float = s, vv: Float = v) = Color(android.graphics.Color.HSVToColor(floatArrayOf(hh, ss, vv)))
    // Follow changes from outside (reset, preset, hex, swatch); the sliders' own changes match already.
    LaunchedEffect(color) {
        if (hsv().toArgb() != color.toArgb()) hsvOf(color).let { h = it[0]; s = it[1]; v = it[2] }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(color).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)))
            var text by remember(color) { mutableStateOf(hex(color)) }
            OutlinedTextField(
                text, { t ->
                    text = t
                    parseHex(t)?.let { onChange(it, true) }
                },
                Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.design_hex)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            IconButton(onReset, enabled = custom) { Icon(Icons.Default.Restore, stringResource(R.string.design_reset_color)) }
        }
        val hues = remember { (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) } }
        GradientSlider(stringResource(R.string.design_hue), h / 360f, Brush.horizontalGradient(hues),
            { h = it * 360f; onChange(hsv(), false) }, { onChange(hsv(), true) })
        GradientSlider(stringResource(R.string.design_saturation), s, Brush.horizontalGradient(listOf(hsv(ss = 0f), hsv(ss = 1f))),
            { s = it; onChange(hsv(), false) }, { onChange(hsv(), true) })
        GradientSlider(stringResource(R.string.design_brightness), v, Brush.horizontalGradient(listOf(Color.Black, hsv(vv = 1f))),
            { v = it; onChange(hsv(), false) }, { onChange(hsv(), true) })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SWATCHES.forEach { c ->
                Box(Modifier.size(28.dp).clip(CircleShape).background(c).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onChange(c, true) })
            }
        }
    }
}

@Composable
private fun GradientSlider(label: String, value: Float, brush: Brush, onValue: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)))
            Slider(value, onValue, Modifier.fillMaxWidth(), onValueChangeFinished = onDone,
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
        }
    }
}

private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

/** "#RRGGBB" or "RRGGBB" (opaque only: the UI has no use for see-through colours). */
private fun parseHex(t: String): Color? = t.trim().removePrefix("#").takeIf { it.length == 6 }
    ?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
