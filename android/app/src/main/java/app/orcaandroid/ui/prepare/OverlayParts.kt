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
import app.orcaandroid.ui.components.framePlate
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Opacity of the control backgrounds over the 3D view (Layout lab). */
val LocalOverlayAlpha = compositionLocalOf { 0.85f }

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

/** Plates as text tabs with an underline under the active one (taskbar style); tap the active one for its menu. */
@Composable
fun PlateTabs(state: UiState, vm: AppViewModel, view: PlateView?, modifier: Modifier = Modifier) {
    var plateMenu by remember { mutableStateOf<Int?>(null) }
    PlateRow(state, modifier, 0.dp, Alignment.CenterVertically, add = {
        Tip(stringResource(R.string.add_plate)) {
            IconButton(onClick = vm.scene::addPlate) { Icon(Icons.Default.Add, stringResource(R.string.add_plate)) }
        }
    }) { plate ->
            val active = plate.index == state.activePlate
            val name = stringResource(R.string.plate_n, plate.index + 1)
            Box {
                // Just the number, like the filmstrip (user, 2026-10-13); "Plate n" for TalkBack.
                Column(
                    Modifier.clickable {
                        if (active) plateMenu = plate.index else { vm.scene.selectPlate(plate.index); view?.framePlate(plate.index) }
                    }.semantics(mergeDescendants = true) { contentDescription = name }
                        .widthIn(min = 44.dp).padding(horizontal = 8.dp).height(44.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${plate.index + 1}", style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun PlateRow(state: UiState, modifier: Modifier, spacing: Dp, align: Alignment.Vertical,
                     add: @Composable () -> Unit, item: @Composable (Plate) -> Unit) {
    val list = rememberLazyListState()
    val plates = state.scene.plates
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
            items(plates.size) { item(plates[it]) }
        }
        add()
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
    PlateRow(state, modifier, 8.dp, Alignment.Bottom, add = {
        Tip(stringResource(R.string.add_plate)) {
            Surface(onClick = vm.scene::addPlate, shape = RoundedCornerShape(8.dp), color = scheme.surfaceContainerHigh.copy(alpha = alpha * 0.6f),
                border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.size(52.dp, 44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, stringResource(R.string.add_plate), tint = scheme.onSurfaceVariant) }
            }
        }
    }) { plate ->
            val active = plate.index == state.activePlate
            Box {
                Surface(
                    onClick = { if (active) plateMenu = plate.index else { vm.scene.selectPlate(plate.index); view?.framePlate(plate.index) } },
                    shape = RoundedCornerShape(8.dp),
                    color = scheme.surfaceContainerHigh.copy(alpha = alpha),
                    border = BorderStroke(if (active) 2.dp else 1.dp, if (active) scheme.primary else scheme.outlineVariant),
                    modifier = Modifier.size(52.dp, if (active) 52.dp else 44.dp),
                ) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        Text("${plate.index + 1}", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                            color = if (active) scheme.onSurface else scheme.onSurfaceVariant)
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
