package app.orcaandroid.ui.prepare

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.Plate
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.showPlate
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider

/** Opacity of the control backgrounds over the 3D view (Layout lab). */
val LocalOverlayAlpha = compositionLocalOf { 0.85f }

/** Scales the plate switcher alone (Layout lab), on top of the interface and overlay sizes. */
@Composable
fun PlateScale(scale: Float, content: @Composable () -> Unit) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
        androidx.compose.ui.unit.Density(base.density * scale, base.fontScale), content = content)
}

/** A short label for an icon-only control: shown on long press (touch) and on mouse hover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) { content() }
}

/** Auto-orient: a turning arrow around a tilted square (instead of Material's "3D" rotation icon). */
val AutoOrientIcon: ImageVector by lazy {
    fun ImageVector.Builder.stroke(d: String) = addPath(
        addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
    ImageVector.Builder("AutoOrient", 24.dp, 24.dp, 24f, 24f).apply {
        stroke("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8")
        stroke("M21 3v5h-5")
        stroke("M13.45 15.99L8.01 13.45L10.55 8.01L15.99 10.55Z")
    }.build()
}

/**
 * Plates picked for deleting together (user, 2026-10-13): long press an inactive plate in the tabs or
 * filmstrip, then tap more; the active plate can't be picked. Empty = not picking.
 */
@Stable
class PlatePicks(private val haptic: HapticFeedback) {
    var picked by mutableStateOf(emptySet<Int>())
    val active get() = picked.isNotEmpty()
    fun toggle(plate: Int) { picked = if (plate in picked) picked - plate else picked + plate }
    fun longPress(plate: Int, activePlate: Int) {
        if (plate == activePlate) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        toggle(plate)
    }
}

/** Tap focuses a plate (the active one opens [menu]); while picking, taps pick or drop inactive plates. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.plateClicks(state: UiState, vm: AppViewModel, view: PlateView?, picks: PlatePicks, plate: Int, menu: () -> Unit) =
    combinedClickable(onLongClick = { picks.longPress(plate, state.activePlate) }) {
        when {
            picks.active -> if (plate != state.activePlate) picks.toggle(plate)
            plate == state.activePlate -> menu()
            else -> { vm.scene.selectPlate(plate); view?.showPlate(plate) }
        }
    }

/** Plates as text tabs with an underline under the active one (taskbar style); tap the active one for its menu. */
@Composable
fun PlateTabs(state: UiState, vm: AppViewModel, view: PlateView?, modifier: Modifier = Modifier) {
    var plateMenu by remember { mutableStateOf<Int?>(null) }
    PlateRow(state, vm, modifier, 0.dp, Alignment.CenterVertically, add = {
        Tip(stringResource(R.string.add_plate)) {
            IconButton(onClick = vm.scene::addPlate) { Icon(Icons.Default.Add, stringResource(R.string.add_plate)) }
        }
    }) { plate, picks ->
            val active = plate.index == state.activePlate
            val picked = plate.index in picks.picked
            val name = stringResource(R.string.plate_n, plate.index + 1)
            val scheme = MaterialTheme.colorScheme
            Box {
                // Just the number, like the filmstrip (user, 2026-10-13); "Plate n" for TalkBack.
                // 2 dp each side keeps picked neighbours' backgrounds apart (they merged, user 2026-10-14);
                // the tab stays 44 dp wide.
                Column(
                    Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (picked) scheme.primaryContainer else Color.Transparent)
                        .plateClicks(state, vm, view, picks, plate.index) { plateMenu = plate.index }
                        .semantics(mergeDescendants = true) { contentDescription = name; selected = picked }
                        .widthIn(min = 40.dp).padding(horizontal = 6.dp).height(44.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${plate.index + 1}", style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = when { picked -> scheme.onPrimaryContainer; active -> scheme.onSurface; else -> scheme.onSurfaceVariant })
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(28.dp).height(2.dp).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(1.dp)))
                }
                if (plateMenu == plate.index) PlateMenu(state, vm, plate.index) { plateMenu = null }
            }
    }
}

/**
 * Plates in a scrolling row that keeps the active one in view (a new plate, or one picked on the canvas),
 * with the add button pinned after it so it never scrolls away.
 */
@Composable
internal fun PlateRow(state: UiState, vm: AppViewModel, modifier: Modifier, spacing: Dp, align: Alignment.Vertical,
                     add: @Composable () -> Unit, item: @Composable (Plate, PlatePicks) -> Unit) {
    val list = rememberLazyListState()
    val plates = state.scene.plates
    val haptic = LocalHapticFeedback.current
    val picks = remember { PlatePicks(haptic) }
    // Plate numbers shift when plates come or go: start over.
    LaunchedEffect(plates.size) { picks.picked = emptySet() }
    LaunchedEffect(state.activePlate) { picks.picked -= state.activePlate }
    BackHandler(picks.active) { picks.picked = emptySet() }
    LaunchedEffect(state.activePlate, plates.size) {
        val info = list.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == state.activePlate }
        if (state.activePlate in plates.indices &&
            (seen == null || seen.offset < info.viewportStartOffset || seen.offset + seen.size > info.viewportEndOffset))
            list.animateScrollToItem(state.activePlate)
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = align) {
        LazyRow(Modifier.weight(1f, fill = false), state = list, horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = align) {
            items(plates.size) { item(plates[it], picks) }
        }
        // While picking, the add slot turns into a stop button of the same size, so the plates don't shift
        // under the finger; the delete button floats beside the row (above the filmstrip, right of the tabs).
        Box(contentAlignment = Alignment.Center) {
            Box(if (picks.active) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier) { add() }
            if (picks.active) {
                IconButton(onClick = { picks.picked = emptySet() }, Modifier.matchParentSize()) {
                    Icon(Icons.Default.Close, stringResource(R.string.cancel))
                }
                PickDelete(vm, picks, above = align == Alignment.Bottom)
            }
        }
    }
}

/** "Delete n plates" next to the plate row's end; their objects stay as floaters. */
@Composable
private fun PickDelete(vm: AppViewModel, picks: PlatePicks, above: Boolean) {
    val title = pluralStringResource(R.plurals.delete_plates, picks.picked.size, picks.picked.size)
    val delete = { vm.scene.deletePlates(picks.picked); picks.picked = emptySet() }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val below = with(LocalDensity.current) { 12.dp.roundToPx() }
    val position = remember(above, gap, below) {
        object : PopupPositionProvider {
            // Filmstrip: above the row's end. Tabs (top row): under the stop button, reaching right, so it covers
            // neither the tool column on the left (it hid Undo, WSA r158) nor the top bar (beside the row it met
            // the File button, phone r178).
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                if (above) IntOffset((anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    anchorBounds.top - popupContentSize.height - gap)
                else IntOffset(anchorBounds.left.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    anchorBounds.bottom + below)
        }
    }
    Popup(popupPositionProvider = position) {
        // Dark grey with a small red bin in both themes (user, 2026-10-14).
        FilledTonalButton(onClick = delete,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2B2C32), contentColor = Color(0xFFEDEEF0)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Color(0xFFFF5A5F))
            Spacer(Modifier.width(6.dp))
            Text(title)
        }
    }
}

/** "Plate n of m" with the plate menu (filmstrip layout's optional label). */
@Composable
fun PlateLabel(state: UiState, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }) {
            Text(stringResource(R.string.plate_n_of_m, state.activePlate + 1, state.scene.plates.size), style = MaterialTheme.typography.titleSmall)
            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        if (menu) PlateMenu(state, vm, state.activePlate) { menu = false }
    }
}

/** Small plate thumbnails along the bottom; the active one is taller and outlined; tap it again for its menu. */
@Composable
fun Filmstrip(state: UiState, vm: AppViewModel, view: PlateView?, modifier: Modifier = Modifier) {
    var plateMenu by remember { mutableStateOf<Int?>(null) }
    val alpha = LocalOverlayAlpha.current
    val scheme = MaterialTheme.colorScheme
    PlateRow(state, vm, modifier, 8.dp, Alignment.Bottom, add = {
        Tip(stringResource(R.string.add_plate)) {
            Surface(onClick = vm.scene::addPlate, shape = RoundedCornerShape(8.dp), color = scheme.surfaceContainerHigh.copy(alpha = alpha * 0.6f),
                border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.size(52.dp, 44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, stringResource(R.string.add_plate), tint = scheme.onSurfaceVariant) }
            }
        }
    }) { plate, picks ->
            val active = plate.index == state.activePlate
            val picked = plate.index in picks.picked
            val shape = RoundedCornerShape(8.dp)
            Box {
                Surface(
                    shape = shape,
                    color = if (picked) scheme.primaryContainer else scheme.surfaceContainerHigh.copy(alpha = alpha),
                    border = BorderStroke(if (active || picked) 2.dp else 1.dp, if (active || picked) scheme.primary else scheme.outlineVariant),
                    modifier = Modifier.size(52.dp, if (active) 52.dp else 44.dp).clip(shape)
                        .plateClicks(state, vm, view, picks, plate.index) { plateMenu = plate.index }
                        .semantics { selected = picked },
                ) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        if (picked) Icon(Icons.Default.Check, null, Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp), tint = scheme.onPrimaryContainer)
                        Text("${plate.index + 1}", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                            color = when { picked -> scheme.onPrimaryContainer; active -> scheme.onSurface; else -> scheme.onSurfaceVariant })
                    }
                }
                if (plateMenu == plate.index) PlateMenu(state, vm, plate.index) { plateMenu = null }
            }
    }
}

/** The project menu behind a labelled File button (top right, like desktop programs). */
@Composable
fun FileButton(state: UiState, vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.file))
        }
        ProjectMenu(state, vm, open) { open = false }
    }
}

/** Found profile updates (daily check): tap installs them; shows progress meanwhile. */
@Composable
fun UpdatesChip(state: UiState, vm: AppViewModel) {
    val updates = state.profileUpdates?.takeIf { it.isNotEmpty() } ?: return
    val busy = state.profileUpdateProgress != null
    AssistChip(
        onClick = vm.presets::installProfileUpdates,
        enabled = !busy,
        label = { Text(if (busy) state.profileUpdateProgress.orEmpty() else pluralStringResource(R.plurals.profile_updates_chip, updates.size, updates.size), maxLines = 1) },
        leadingIcon = {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Download, null, Modifier.size(18.dp))
        },
    )
}
