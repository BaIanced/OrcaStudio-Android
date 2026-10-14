package app.orcaandroid.ui

import app.orcaandroid.ui.theme.LocalAppColors
import app.orcaandroid.ui.theme.NAV_BAR
import app.orcaandroid.ui.theme.NAV_ICONS
import app.orcaandroid.ui.theme.NAV_PILL
import app.orcaandroid.ui.theme.NAV_SELECTED
import app.orcaandroid.ui.theme.Region
import app.orcaandroid.ui.theme.ThemeDesignPanel
import app.orcaandroid.ui.theme.Themed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import app.orcaandroid.ui.scad.ScadPanel
import app.orcaandroid.ui.scad.ScadPreviewPane
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import app.orcaandroid.ui.scad.ScadPlateToggle
import app.orcaandroid.ui.scad.ScadReopenButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.components.Viewport
import app.orcaandroid.ui.device.DeviceScreen
import app.orcaandroid.ui.prepare.PrepareOverlay
import app.orcaandroid.ui.prepare.PreparePanel
import app.orcaandroid.ui.preview.PreviewOverlay
import app.orcaandroid.ui.preview.PreviewPanel
import app.orcaandroid.ui.models.ModelBrowser
import app.orcaandroid.ui.models.ModelsScreen
import app.orcaandroid.ui.settings.MoreScreen
import app.orcaandroid.ui.settings.PrinterSetupScreen
import app.orcaandroid.ui.settings.SettingsEditor

private data class Destination(val screen: Screen, val label: Int, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Destination(Screen.PREPARE, R.string.tab_prepare, Icons.Outlined.ViewInAr),
    Destination(Screen.PREVIEW, R.string.tab_preview, Icons.Outlined.Layers),
    Destination(Screen.DEVICE, R.string.tab_device, Icons.Outlined.Print),
    Destination(Screen.MODELS, R.string.tab_models, Icons.Outlined.Public),
    Destination(Screen.MORE, R.string.tab_more, Icons.Outlined.MoreHoriz),
)

/** Width from which the app uses the side-by-side tablet layout. */
val WIDE_LAYOUT = 840.dp

@Composable
fun AppScaffold(state: UiState, vm: AppViewModel) {
    // Interface and text size (Layout lab), on top of the system's display and font size.
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
        androidx.compose.ui.unit.Density(base.density * state.lab.uiScale, base.fontScale * state.lab.textScale)) {
        when {
            state.phase == Phase.LOADING -> Busy(state.busy ?: stringResource(R.string.starting))
            state.phase == Phase.SETUP || state.showPrinterSetup -> Themed(Region.EDITOR, Modifier.fillMaxSize(), fill = true) { PrinterSetupScreen(state, vm) }
            state.editor != null -> Themed(Region.EDITOR, Modifier.fillMaxSize(), fill = true) { SettingsEditor(state, vm) }
            else -> MainLayout(state, vm)
        }
        Themed(Region.DIALOGS) { GlobalDialogs(state, vm) }
        if (state.designMode) ThemeDesignPanel(state, vm)
    }
}

@Composable
private fun MainLayout(state: UiState, vm: AppViewModel) {
    // The Models tab's browser outlives the tab, so switching tabs keeps the page and its history.
    val context = androidx.compose.ui.platform.LocalContext.current
    // A theme change makes a new browser: the WebView takes light / dark from the context it is made with.
    val dark = app.orcaandroid.ui.components.appIsDark()
    val browser = remember(dark) { lazy { ModelBrowser(context, vm, dark) } }
    DisposableEffect(browser) { onDispose { if (browser.isInitialized()) browser.value.destroy() } }
    LaunchedEffect(browser, state.modelsUrl) {
        state.modelsUrl?.let { browser.value.openUrl(it); vm.modelsUrlOpened() }
    }
    // Back leads home: other tabs return to Prepare, and Prepare drops the tool and selection
    // first (as Esc does). Screens composed below (the Models browser) take back before this.
    // With nothing left to go back to, Back asks before quitting (or, as chosen under More, needs
    // a second press within 2 s), instead of Android's default of sending the app to the background.
    val clearable = state.screen == Screen.PREPARE && (state.tool != Tool.None || state.selectedItems.isNotEmpty() || state.selectMode)
    var askQuit by remember { mutableStateOf(false) }
    var lastBack by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler {
        when {
            state.screen != Screen.PREPARE -> vm.setScreen(Screen.PREPARE)
            clearable -> { vm.scene.setTool(Tool.None); vm.scene.select(null); vm.scene.setSelectMode(false) }
            !state.doubleBackToQuit -> askQuit = true
            android.os.SystemClock.uptimeMillis() - lastBack < 2000 -> (context as? android.app.Activity)?.finish()
            else -> {
                lastBack = android.os.SystemClock.uptimeMillis()
                android.widget.Toast.makeText(context, R.string.back_again_quit, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (askQuit) QuitDialog(onQuit = { askQuit = false; (context as? android.app.Activity)?.finish() }, onDismiss = { askQuit = false })
    // Tab bar / rail colours (design mode); the rail keeps its own default background unless changed.
    val colors = LocalAppColors.current
    val navBar = colors.color(Region.NAV, NAV_BAR)
    val navPill = colors.color(Region.NAV, NAV_PILL)
    val navSelected = colors.color(Region.NAV, NAV_SELECTED)
    val navIcons = colors.color(Region.NAV, NAV_ICONS)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT
        val content: @Composable (Modifier) -> Unit = { m ->
            Box(m.clipToBounds()) {
                // Fullscreen portrait leaves the camera strip at the top to the 3D view only; the rest keeps clear.
                val belowHole = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                when (state.screen) {
                    Screen.PREPARE, Screen.PREVIEW -> Workspace(state, vm, wide)
                    Screen.DEVICE -> Themed(Region.DEVICE, Modifier.fillMaxSize(), fill = true) { Box(belowHole) { DeviceScreen(state, vm) } }
                    Screen.MODELS -> Themed(Region.MODELS, Modifier.fillMaxSize(), fill = true) { Box(belowHole) { ModelsScreen(browser.value) } }
                    Screen.MORE -> Themed(Region.MORE, Modifier.fillMaxSize(), fill = true) { Box(belowHole) { MoreScreen(state, vm) } }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                // No insets of its own: MainActivity pads the bars, and in landscape the rail may use the cutout strip.
                NavigationRail(windowInsets = WindowInsets(0),
                    containerColor = if (colors.isCustom(Region.NAV, NAV_BAR)) navBar else androidx.compose.material3.NavigationRailDefaults.ContainerColor) {
                    CutoutAwareColumn {
                        DESTINATIONS.forEach { d ->
                            NavigationRailItem(
                                selected = state.screen == d.screen,
                                onClick = { vm.setScreen(d.screen) },
                                icon = { Icon(d.icon, null) },
                                label = { Text(stringResource(d.label)) },
                                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(selectedIconColor = navSelected,
                                    selectedTextColor = navSelected, indicatorColor = navPill, unselectedIconColor = navIcons, unselectedTextColor = navIcons),
                            )
                        }
                    }
                }
                // The rail may sit over the camera cutout (MainActivity); the content keeps clear of it.
                content(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)))
                NavigationBar(containerColor = navBar) {
                    DESTINATIONS.forEach { d ->
                        NavigationBarItem(
                            selected = state.screen == d.screen,
                            onClick = { vm.setScreen(d.screen) },
                            icon = { Icon(d.icon, null) },
                            label = { Text(stringResource(d.label)) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(selectedIconColor = navSelected,
                                selectedTextColor = navSelected, indicatorColor = navPill, unselectedIconColor = navIcons, unselectedTextColor = navIcons),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Rail items stacked from the top like NavigationRail's own column; an item that would overlap a
 * camera cutout under the rail moves below it (landscape lets the rail use the cutout strip).
 */
@Composable
private fun CutoutAwareColumn(content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    // Reading the inset recomposes on rotation; the hole rects come from the view.
    WindowInsets.displayCutout.getLeft(androidx.compose.ui.platform.LocalDensity.current, androidx.compose.ui.unit.LayoutDirection.Ltr)
    val holes = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.ui.layout.Layout(content, Modifier.fillMaxHeight().onGloballyPositioned { origin = it.positionInWindow() }) { ms, c ->
        val gap = 4.dp.roundToPx()
        val ps = ms.map { it.measure(c.copy(minHeight = 0)) }
        val w = ps.maxOfOrNull { it.width } ?: 0
        val x0 = origin.x.toInt()
        // Holes under the rail, in this layout's coordinates (top to bottom).
        val blocked = holes.filter { it.left < x0 + w && it.right > x0 }
            .map { (it.top - origin.y.toInt()) to (it.bottom - origin.y.toInt()) }.sortedBy { it.first }
        val ys = IntArray(ps.size)
        var y = 0
        ps.forEachIndexed { i, p ->
            blocked.forEach { (top, bottom) -> if (y < bottom && y + p.height > top) y = bottom + gap }
            ys[i] = y
            y += p.height + gap
        }
        layout(w, c.maxHeight) { ps.forEachIndexed { i, p -> p.placeRelative((w - p.width) / 2, ys[i]) } }
    }
}

/**
 * Prepare and Preview share one 3D view. Tablets show the settings/preview panel beside it,
 * phones in a bottom sheet so the view keeps the full screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Workspace(state: UiState, vm: AppViewModel, wide: Boolean) {
    var plateView by remember { mutableStateOf<PlateView?>(null) }
    val viewport: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            Viewport(state, vm, onView = { plateView = it }, modifier = Modifier.fillMaxSize())
            // Floating controls have their own size (Layout lab).
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(base.density * state.lab.overlayScale, base.fontScale)) {
                if (state.screen == Screen.PREPARE) Themed(Region.PREPARE_TOOLS) { PrepareOverlay(state, vm, plateView, wide) }
                else Themed(Region.PREVIEW_TOOLS) { PreviewOverlay(state, vm, plateView, wide) }
            }
        }
    }
    val panelRegion = if (state.screen == Screen.PREPARE) Region.PREPARE_PANEL else Region.PREVIEW_PANEL
    val panel: @Composable () -> Unit = {
        if (state.screen == Screen.PREPARE) PreparePanel(state, vm, wide) else PreviewPanel(state, vm, wide)
    }
    // The OpenSCAD editor takes the panel's place on Prepare. The design shows alone in the design view,
    // or (eye toggle, user 2026-10-13) as a ghost on the plate; the editor can be resized with the handle.
    val scad = state.scad?.takeIf { state.screen == Screen.PREPARE }
    // Fullscreen portrait: the 3D view uses the camera strip at the top, everything else keeps below it.
    val belowHole = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
    val scadView: @Composable (Modifier) -> Unit = { m ->
        if (scad != null && state.scadOnPlate) Box(m) {
            viewport(Modifier.fillMaxSize())
            ScadPlateToggle(onPlate = true, { vm.setScadOnPlate(false) }, Modifier.align(Alignment.TopEnd).then(belowHole).padding(top = 76.dp, end = 12.dp))
        } else if (scad != null) Themed(Region.OPENSCAD) { Surface(m) { ScadPreviewPane(scad, vm, Modifier.fillMaxSize().then(belowHole)) } } else viewport(m)
    }
    if (wide) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val total = maxWidth
            val width = (if (ScadEditorSize.width > 0f) ScadEditorSize.width.dp else (total * 0.45f).coerceIn(420.dp, 640.dp))
                .coerceIn(300.dp, (total - 240.dp).coerceAtLeast(300.dp))
            Row(Modifier.fillMaxSize()) {
                if (scad != null) {
                    scadView(Modifier.weight(1f).fillMaxHeight())
                    DragHandle(vertical = true) { d -> ScadEditorSize.width = (width - d).value }
                    Themed(Region.OPENSCAD) { Surface(Modifier.width(width).fillMaxHeight(), tonalElevation = 1.dp) { Box(belowHole) { ScadPanel(scad, vm, embeddedPreview = false) } } }
                } else {
                    viewport(Modifier.weight(1f).fillMaxHeight())
                    // The edge drags to resize the panel (like the OpenSCAD editor's); let go below the snap
                    // width and it tucks away. A tap tucks it away or brings it back.
                    val lab = state.lab
                    val maxPanel = (total - 240.dp).coerceAtLeast(PANEL_MIN)
                    val stored = (if (lab.panelWidth > 0f) lab.panelWidth.dp else PANEL_DEFAULT).coerceIn(PANEL_MIN, maxPanel)
                    var live by remember { mutableStateOf<androidx.compose.ui.unit.Dp?>(null) }
                    val shown = live ?: if (lab.panelTucked) 0.dp else stored
                    PanelEdge(
                        tucked = shown < PANEL_SNAP,
                        onClick = { vm.setLab(lab.copy(panelTucked = !lab.panelTucked)) },
                        onDrag = { d -> live = ((live ?: shown) - d).coerceIn(0.dp, maxPanel) },
                        onDragEnd = {
                            live?.let { w ->
                                vm.setLab(if (w < PANEL_SNAP) lab.copy(panelTucked = true)
                                          else lab.copy(panelTucked = false, panelWidth = w.coerceAtLeast(PANEL_MIN).value))
                            }
                            live = null
                        },
                    )
                    if (shown > 0.dp) Themed(panelRegion) { Surface(Modifier.width(shown).fillMaxHeight(), tonalElevation = 1.dp) {
                        // While dragging narrower than the panel can be, it keeps its layout and is clipped.
                        Box(Modifier.fillMaxHeight().clipToBounds()) {
                            Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(shown.coerceAtLeast(PANEL_MIN)).fillMaxHeight().then(belowHole)) { panel() }
                        }
                    } }
                }
            }
        }
    } else if (scad != null) {
        if (state.scadHidden) Box(Modifier.fillMaxSize()) {
            viewport(Modifier.fillMaxSize())
            ScadReopenButton({ vm.setScadHidden(false) }, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val total = maxHeight
            val height = (if (ScadEditorSize.height > 0f) ScadEditorSize.height.dp else total * 0.55f).coerceIn(200.dp, (total - 120.dp).coerceAtLeast(200.dp))
            Column(Modifier.fillMaxSize()) {
                scadView(Modifier.weight(1f).fillMaxWidth())
                DragHandle(vertical = false) { d -> ScadEditorSize.height = (height - d).value }
                Themed(Region.OPENSCAD) { Surface(Modifier.height(height).fillMaxWidth()) { ScadPanel(scad, vm, onHide = { vm.setScadHidden(true) }, embeddedPreview = false) } }
            }
        }
    } else {
        val sheet = rememberBottomSheetScaffoldState()
        val sheetColors = LocalAppColors.current.scheme(panelRegion)
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = 132.dp,
            sheetContainerColor = sheetColors.surfaceContainerLow,
            sheetContentColor = sheetColors.onSurface,
            sheetContent = { Themed(panelRegion) { Box(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) { panel() } } },
        ) { padding ->
            viewport(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()))
        }
    }
}

/** A grip between two panes; dragging it reports the move ([vertical] grip: sideways, else up/down). */
@Composable
private fun DragHandle(vertical: Boolean, onDrag: (androidx.compose.ui.unit.Dp) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) }
    Box(
        (if (vertical) Modifier.width(16.dp).fillMaxHeight() else Modifier.height(20.dp).fillMaxWidth())
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .draggable(drag, if (vertical) Orientation.Horizontal else Orientation.Vertical),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (vertical) 4.dp else 40.dp, if (vertical) 40.dp else 4.dp)
            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
    }
}

private val PANEL_DEFAULT = 380.dp
private val PANEL_MIN = 300.dp
/** Let go narrower than this and the panel tucks away. */
private val PANEL_SNAP = 200.dp

/** The OpenSCAD editor's dragged size in dp (0 = default); kept for the session, so leaving Prepare doesn't reset it. */
private object ScadEditorSize {
    var width by mutableStateOf(0f)
    var height by mutableStateOf(0f)
}

/** The settings panel's edge on wide layouts: drag to resize, tap to tuck the panel away or bring it back. */
@Composable
private fun PanelEdge(tucked: Boolean, onClick: () -> Unit, onDrag: (androidx.compose.ui.unit.Dp) -> Unit, onDragEnd: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) }
    val label = stringResource(if (tucked) R.string.show_panel else R.string.tuck_panel)
    Surface(onClick = onClick, tonalElevation = 2.dp, modifier = Modifier.width(if (tucked) 32.dp else 20.dp).fillMaxHeight()
        .draggable(drag, Orientation.Horizontal, onDragStopped = { onDragEnd() })) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(4.dp, 32.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
            app.orcaandroid.ui.prepare.Tip(label) {
                Icon(if (tucked) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight, label)
            }
            Box(Modifier.size(4.dp, 32.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun GlobalDialogs(state: UiState, vm: AppViewModel) {
    if (state.busy != null && state.phase != Phase.LOADING) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(state.busy)
                }
            },
        )
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(stringResource(R.string.error)) },
            // Engine errors are OrcaSlicer's (translatable) messages; show them in the app's language.
            text = { Text(vm.translator.tr(message)) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.ok)) } },
        )
    }
    state.aiConfirm?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ai_confirm_title)) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.answerAi(true) }) { Text(stringResource(R.string.ai_allow)) } },
            dismissButton = { TextButton(onClick = { vm.answerAi(false) }) { Text(stringResource(R.string.ai_deny)) } },
        )
    }
    state.crashReport?.let { report ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = vm::dismissCrashReport,
            title = { Text(stringResource(R.string.crash_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.crash_text))
                    Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Orca-Android crash", report))
            }) { Text(stringResource(R.string.copy)) } },
            dismissButton = { TextButton(onClick = vm::dismissCrashReport) { Text(stringResource(R.string.close)) } },
        )
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            val action = state.messageAction
            val result = snackbar.showSnackbar(it, actionLabel = action?.label,
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            vm.dismissMessage()
            if (result == SnackbarResult.ActionPerformed) action?.run?.invoke()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(snackbar, Modifier.padding(bottom = 72.dp))
    }
}

@Composable
fun Busy(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** "Quit?" with a short note and two equal buttons; Back or a tap outside keeps the app open. */
@Composable
private fun QuitDialog(onQuit: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.width(320.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.quit_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.quit_text), Modifier.padding(top = 8.dp, bottom = 24.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.no)) }
                    androidx.compose.material3.Button(onClick = onQuit, Modifier.weight(1f)) { Text(stringResource(R.string.yes)) }
                }
            }
        }
    }
}
