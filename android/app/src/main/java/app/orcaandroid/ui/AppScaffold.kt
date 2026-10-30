package app.orcaandroid.ui

import app.orcaandroid.ui.settings.engineErrorText
import app.orcaandroid.ui.theme.LocalAppColors
import app.orcaandroid.ui.theme.NAV_BAR
import app.orcaandroid.ui.theme.NAV_ICONS
import app.orcaandroid.ui.theme.NAV_PILL
import app.orcaandroid.ui.theme.NAV_SELECTED
import app.orcaandroid.ui.theme.Region
import app.orcaandroid.ui.theme.ThemeDesignPanel
import app.orcaandroid.ui.theme.Themed
import app.orcaandroid.ui.theme.contrast
import app.orcaandroid.ui.theme.onColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.components.Viewport
import app.orcaandroid.ui.device.DeviceScreen
import app.orcaandroid.ui.prepare.PlateSwitcherBar
import app.orcaandroid.ui.prepare.PrepareOverlay
import app.orcaandroid.ui.prepare.inPaintShop
import app.orcaandroid.ui.prepare.ProblemFocus
import app.orcaandroid.ui.prepare.PreparePanel
import app.orcaandroid.ui.preview.PreviewOverlay
import app.orcaandroid.ui.preview.PreviewPanel
import app.orcaandroid.ui.models.ModelBrowser
import app.orcaandroid.ui.models.ModelsScreen
import app.orcaandroid.ui.settings.LabPeek
import app.orcaandroid.ui.settings.MoreScreen
import androidx.compose.ui.graphics.graphicsLayer
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
    // Design panel: docked as a right sidebar by default, detachable to a floating panel (user, 2026-10-30).
    var designFloating by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // Interface and text size (Layout lab), on top of the system's display and font size.
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
        androidx.compose.ui.unit.Density(base.density * state.lab.uiScale, base.fontScale * state.lab.textScale)) {
        when {
            state.phase == Phase.LOADING -> Busy(state.busy ?: stringResource(R.string.starting))
            state.phase == Phase.SETUP || state.showPrinterSetup -> Themed(Region.EDITOR, Modifier.fillMaxSize(), fill = true) { PrinterSetupScreen(state, vm) }
            state.editor != null -> Themed(Region.EDITOR, Modifier.fillMaxSize(), fill = true) { SettingsEditor(state, vm) }
            else -> Box(Modifier.fillMaxSize().padding(end = if (state.designMode && !designFloating) app.orcaandroid.ui.theme.DESIGN_DOCK_WIDTH else 0.dp)) { MainLayout(state, vm) }
        }
        // The design panel under the dialogs: the message bar sits at the panel's height (it hid it, phone r189).
        if (state.designMode) ThemeDesignPanel(state, vm, designFloating) { designFloating = it }
        Themed(Region.DIALOGS) { GlobalDialogs(state, vm) }
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
            state.scad != null && state.screen == Screen.PREPARE -> if (state.scadOnPlate && state.scad.shapes == null) vm.setScadOnPlate(false) else vm.scad.close()
            state.screen != Screen.PREPARE -> vm.setScreen(Screen.PREPARE)
            state.inPaintShop -> vm.scene.setTool(Tool.None)
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
    // The selected label sits on the bar under the pill: the pill's text colour may not read there.
    val navLabel = if (contrast(navSelected, navBar) >= 3f) navSelected else onColor(navBar)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT
        val content: @Composable (Modifier) -> Unit = { m ->
            Box(m.clipToBounds()) {
                // Fullscreen portrait leaves the camera strip at the top to the 3D view only; the rest keeps clear.
                val belowHole = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                when (state.screen) {
                    Screen.PREPARE, Screen.PREVIEW -> Workspace(state, vm, wide)
                    Screen.DEVICE -> Themed(Region.DEVICE, Modifier.fillMaxSize(), fill = true) { Box(belowHole) { DeviceScreen(state, vm) } }
                    // The Models toolbar uses the camera strip itself (SplitAroundCutout), so no belowHole here.
                    Screen.MODELS -> Themed(Region.MODELS, Modifier.fillMaxSize(), fill = true) { ModelsScreen(browser.value) }
                    Screen.MORE -> {
                        // Layout lab open: Prepare stays composed under More, which fades out while a control is touched.
                        if (LabPeek.open) Workspace(state.copy(screen = Screen.PREPARE), vm, wide)
                        val fade by androidx.compose.animation.core.animateFloatAsState(if (LabPeek.touching) 0f else 1f, label = "labFade")
                        // While touched, More keeps the sizes it had at the touch, so a slider doesn't move under the finger.
                        val frozen = LabPeek.frozen
                        val here = androidx.compose.ui.platform.LocalDensity.current
                        val keep = if (frozen == null) here else androidx.compose.ui.unit.Density(
                            here.density / state.lab.uiScale * frozen.uiScale, here.fontScale / state.lab.textScale * frozen.textScale)
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides keep) {
                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade }) {
                                Themed(Region.MORE, Modifier.fillMaxSize(), fill = true) { Box(belowHole) { MoreScreen(state, vm) } }
                            }
                        }
                        LabPeek.readout?.takeIf { LabPeek.touching }?.let { (text, fraction) ->
                            androidx.compose.material3.Surface(Modifier.align(Alignment.Center).widthIn(max = 360.dp).fillMaxWidth(0.8f),
                                shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Text(text, style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.material3.LinearProgressIndicator({ fraction.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        // The paint shop takes the whole screen: no tab bar or rail.
        val shop = state.inPaintShop
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                // No insets of its own: MainActivity pads the bars, and in landscape the rail may use the cutout strip.
                if (!shop) NavigationRail(windowInsets = WindowInsets(0),
                    containerColor = if (colors.isCustom(Region.NAV, NAV_BAR)) navBar else androidx.compose.material3.NavigationRailDefaults.ContainerColor) {
                    CutoutAwareColumn {
                        DESTINATIONS.forEach { d ->
                            NavigationRailItem(
                                selected = state.screen == d.screen,
                                onClick = { vm.setScreen(d.screen) },
                                icon = { Icon(d.icon, null) },
                                label = { Text(stringResource(d.label)) },
                                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(selectedIconColor = navSelected,
                                    selectedTextColor = navLabel, indicatorColor = navPill, unselectedIconColor = navIcons, unselectedTextColor = navIcons),
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
                if (!shop) NavigationBar(containerColor = navBar) {
                    DESTINATIONS.forEach { d ->
                        NavigationBarItem(
                            selected = state.screen == d.screen,
                            onClick = { vm.setScreen(d.screen) },
                            icon = { Icon(d.icon, null) },
                            label = { Text(stringResource(d.label)) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(selectedIconColor = navSelected,
                                selectedTextColor = navLabel, indicatorColor = navPill, unselectedIconColor = navIcons, unselectedTextColor = navIcons),
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
    // The red problems badge on the 3D view asks for the panel: slide it up (portrait) or bring it back (wide).
    LaunchedEffect(ProblemFocus.tick) {
        if (ProblemFocus.tick == 0) return@LaunchedEffect
        if (PanelSize.height < 360f) PanelSize.height = 360f
        if (state.lab.panelTucked) vm.setLab(state.lab.copy(panelTucked = false))
    }
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val viewport: @Composable (Modifier) -> Unit = { m ->
        Box(m.onSizeChanged { viewportSize = it }) {
            Viewport(state, vm, onView = { plateView = it }, modifier = Modifier.fillMaxSize().steadyThroughGlide()
                .onGloballyPositioned { app.orcaandroid.ui.prepare.CanvasBounds.rect = it.boundsInWindow() })
            // Floating controls have their own size (Layout lab).
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(base.density * state.lab.overlayScale, base.fontScale)) {
                if (state.screen == Screen.PREPARE) Themed(Region.PREPARE_TOOLS) { PrepareOverlay(state, vm, plateView, wide) }
                else Themed(Region.PREVIEW_TOOLS) { PreviewOverlay(state, vm, plateView, wide) }
            }
            if (state.lab.viewCube) plateView?.let { ViewCubeSpot(it.camera, state.lab, vm, viewportSize) }
            // Below the top tool row, centered.
            app.orcaandroid.ui.components.Notices(state, vm, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
            // Gesture scrubber (held setting label): its list sits high, above the finger.
            app.orcaandroid.ui.components.ScrubHud(Modifier.align(Alignment.TopCenter).padding(top = 96.dp))
        }
    }
    // The paint shop keeps the 3D view where the plate view has it: moving it in the tree made a new GL view (a slow
    // entry, the shop's frame colour filling the screen until the first frame, user r459). Bar, frame and dock wrap it.
    val shop = state.inPaintShop
    Column(Modifier.fillMaxSize()) {
        if (shop) app.orcaandroid.ui.prepare.PaintShopBar(state, vm, plateView)
        Box(Modifier.weight(1f).fillMaxWidth()) { WorkspaceBody(state, vm, wide, shop, viewport, plateView) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceBody(state: UiState, vm: AppViewModel, wide: Boolean, shop: Boolean, viewport: @Composable (Modifier) -> Unit, plateView: PlateView?) {
    val panelRegion = if (state.screen == Screen.PREPARE) Region.PREPARE_PANEL else Region.PREVIEW_PANEL
    val panel: @Composable () -> Unit = {
        if (state.screen == Screen.PREPARE) PreparePanel(state, vm, wide) else PreviewPanel(state, vm, wide)
    }
    // The OpenSCAD editor takes the panel's place on Prepare. The design shows alone in the design view,
    // or (eye toggle, user 2026-10-13) as a ghost on the plate; the editor can be resized with the handle.
    val scad = state.scad?.takeIf { state.screen == Screen.PREPARE && !shop }
    // Fullscreen portrait: the 3D view uses the camera strip at the top, everything else keeps below it.
    val belowHole = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
    val scadView: @Composable (Modifier) -> Unit = { m ->
        if (scad != null && state.scadOnPlate) Box(m) {
            viewport(Modifier.fillMaxSize())
            // Code designs go back to their preview pane; shape designs have only this view.
            if (scad.shapes == null) ScadPlateToggle(onPlate = true, { vm.setScadOnPlate(false) }, Modifier.align(Alignment.TopEnd).then(belowHole).padding(top = 76.dp, end = 12.dp))
            // A shape design with no solid shape yet isn't on the plate: say how to start.
            else if (state.buildObject == null && !scad.rendering) Text(stringResource(R.string.shape_start), Modifier.align(Alignment.Center).padding(24.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            // The Build workspace: the design's object alone, its shapes picked and dragged in this view.
            if (state.buildObject != null) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.shape_preview_help), Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else if (scad != null) Themed(Region.OPENSCAD) { Surface(m) { ScadPreviewPane(scad, vm, Modifier.fillMaxSize().then(belowHole)) } } else viewport(m)
    }
    if (wide) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val total = maxWidth
            val width = (if (ScadEditorSize.width > 0f) ScadEditorSize.width.dp else (total * 0.45f).coerceIn(420.dp, 640.dp))
                .coerceIn(300.dp, (total - 240.dp).coerceAtLeast(300.dp))
            Row(Modifier.fillMaxSize()) {
                if (scad != null && !state.scadHidden) {
                    scadView(Modifier.weight(1f).fillMaxHeight())
                    Themed(Region.OPENSCAD) { DragHandle(vertical = true) { d -> ScadEditorSize.width = (width - d).value } }
                    Themed(Region.OPENSCAD) { Surface(Modifier.width(width).fillMaxHeight(), tonalElevation = 1.dp) { Box(belowHole) { ScadPanel(scad, vm, embeddedPreview = false) } } }
                } else {
                    // A tucked-away design editor (design placed on the plate, or an MCP view tool) comes back with its button.
                    if (scad != null) Box(Modifier.weight(1f).fillMaxHeight()) {
                        viewport(Modifier.fillMaxSize())
                        ScadReopenButton({ vm.setScadHidden(false) }, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 76.dp))
                    } else viewport(Modifier.weight(1f).fillMaxHeight().then(app.orcaandroid.ui.prepare.shopFrame(shop)))
                    if (shop) { app.orcaandroid.ui.prepare.PaintShopDock(state, vm, side = true, Modifier.width(360.dp).fillMaxHeight()); return@Row }
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
                        onDragEnd = { flick ->
                            if (flick != null) vm.setLab(lab.copy(panelTucked = flick))
                            else live?.let { w ->
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
            // Above the Slice button (same corner; it covered it, phone r189).
            ScadReopenButton({ vm.setScadHidden(false) }, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 76.dp))
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val total = maxHeight
            val height = (if (ScadEditorSize.height > 0f) ScadEditorSize.height.dp else total * 0.55f).coerceIn(200.dp, (total - 120.dp).coerceAtLeast(200.dp))
            Column(Modifier.fillMaxSize()) {
                scadView(Modifier.weight(1f).fillMaxWidth())
                val tallest = (total - 120.dp).coerceAtLeast(200.dp)
                Themed(Region.OPENSCAD) {
                    DragHandle(vertical = false, onFling = { down -> settle(if (down) 200f else tallest.value, height.value) { ScadEditorSize.height = it } }) { d ->
                        ScadEditorSize.height = (height - d).value
                    }
                }
                Themed(Region.OPENSCAD) { Surface(Modifier.height(height).fillMaxWidth()) { ScadPanel(scad, vm, onHide = { vm.setScadHidden(true) }, embeddedPreview = false) } }
            }
        }
    } else {
        // A panel the grip resizes to any height (user 2026-10-15: the sheet snapped to 75 % to reach nested options).
        val sheetColors = LocalAppColors.current.scheme(panelRegion)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val total = maxHeight
            val height = (if (PanelSize.height > 0f) PanelSize.height.dp else 132.dp).coerceIn(56.dp, (total * 0.85f).coerceAtLeast(56.dp))
            // A landscape phone docks the shop's options beside the view (a new GL view on entry there, as before).
            if (shop && maxWidth > maxHeight && maxWidth >= 600.dp) Row(Modifier.fillMaxSize()) {
                viewport(Modifier.weight(1f).fillMaxHeight().then(app.orcaandroid.ui.prepare.shopFrame(true)))
                app.orcaandroid.ui.prepare.PaintShopDock(state, vm, side = true, Modifier.width(360.dp).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                viewport(Modifier.weight(1f).fillMaxWidth().then(app.orcaandroid.ui.prepare.shopFrame(shop)))
                if (shop) { app.orcaandroid.ui.prepare.PaintShopDock(state, vm, side = false, Modifier.fillMaxWidth().heightIn(max = total * 0.45f)); return@Column }
                val onHandle = state.lab.platesOnHandle && state.screen == Screen.PREPARE
                val slice = state.screen == Screen.PREPARE && app.orcaandroid.ui.prepare.sliceOnGrip(state, wide)
                val handlePlates = onHandle && state.lab.showPlates
                val header: (@Composable () -> Unit)? = if (handlePlates || slice) ({
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (handlePlates) PlateSwitcherBar(state, vm, plateView, Modifier.weight(1f)) else Box(Modifier.weight(1f))
                        if (slice) app.orcaandroid.ui.prepare.GripSliceButton(state, vm, Modifier.padding(start = 8.dp))
                    }
                }) else null
                val tallest = (total * 0.85f).coerceAtLeast(56.dp)
                Themed(panelRegion) {
                    DragHandle(vertical = false, header = header,
                        onFling = { down -> settle(if (down) 56f else tallest.value, height.value) { PanelSize.height = it } },
                    ) { d -> PanelSize.height = (height - d).value }
                }
                Surface(Modifier.height(height).fillMaxWidth(), color = sheetColors.surfaceContainerLow, contentColor = sheetColors.onSurface) {
                    Themed(panelRegion) { Box(Modifier.fillMaxSize()) { panel() } }
                }
            }
        }
    }
}

/**
 * A grip between two panes; dragging it reports the move ([vertical] grip: sideways, else up/down). Let go of a quick
 * flick and [onFling] gets its direction (true = down / right); a slow release stays where the finger left it.
 */
@Composable
private fun DragHandle(vertical: Boolean, header: (@Composable () -> Unit)? = null, onFling: ((Boolean) -> Unit)? = null,
                       onDrag: (androidx.compose.ui.unit.Dp) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) }
    val speed = remember { FingerSpeed() }
    val stopped: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit = {
        val dps = with(density) { speed.release(vertical = !vertical).toDp() }
        if (onFling != null && kotlin.math.abs(dps.value) >= FLING_DP_PER_S) onFling(dps.value > 0)
    }
    // Something nested on the grip (the plate switcher): it rides on top of the grip row, which alone drags.
    if (header != null && !vertical) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
            header()
            Box(Modifier.height(20.dp).fillMaxWidth().trackFinger(speed).draggable(drag, Orientation.Vertical, onDragStarted = { GripGlide.job?.cancel() }, onDragStopped = stopped), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
            }
        }
        return
    }
    Box(
        (if (vertical) Modifier.width(16.dp).fillMaxHeight() else Modifier.height(20.dp).fillMaxWidth())
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .trackFinger(speed)
            .draggable(drag, if (vertical) Orientation.Horizontal else Orientation.Vertical, onDragStarted = { GripGlide.job?.cancel() }, onDragStopped = stopped),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (vertical) 4.dp else 40.dp, if (vertical) 40.dp else 4.dp)
            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
    }
}

/** Release speed (dp/s) from which a grip flick collapses or expands its pane. */
private const val FLING_DP_PER_S = 800f

/**
 * The finger's speed over a grip, in window pixels. A grip follows the finger, so the speed relative to it (what
 * draggable's onDragStopped reports) stays near 0 and flicks never fired (r281).
 */
private class FingerSpeed {
    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null

    /** Speed at release (px/s) along y ([vertical]) or x; 0 when the finger rested before letting go. */
    fun release(vertical: Boolean): Float = tracker.calculateVelocity().let { if (vertical) it.y else it.x }
}

private fun Modifier.trackFinger(s: FingerSpeed) = onGloballyPositioned { s.coords = it }.pointerInput(s) {
    awaitPointerEventScope {
        while (true) {
            // Initial pass, nothing consumed: the draggable below still gets every event.
            val change = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull() ?: continue
            val coords = s.coords ?: continue
            if (change.pressed && !change.previousPressed) s.tracker.resetTracking()
            // Event positions are local to the layout as it is now: map them to the root before the grip moves again.
            change.historical.forEach { s.tracker.addPosition(it.uptimeMillis, coords.localToRoot(it.position)) }
            s.tracker.addPosition(change.uptimeMillis, coords.localToRoot(change.position))
        }
    }
}

/** Glides a pane size (dp) from [from] to [to] after a flick. */
private fun settle(to: Float, from: Float, set: (Float) -> Unit) {
    GripGlide.job?.cancel()
    val low = minOf(from, to)
    GripGlide.job = GripGlide.scope.launch {
        val me = coroutineContext[kotlinx.coroutines.Job]
        try {
            androidx.compose.animation.core.animate(from, to) { v, _ -> GripGlide.extra = v - low; set(v) }
        } finally {
            if (GripGlide.job === me) GripGlide.extra = 0f
        }
    }
}

private object GripGlide {
    // animate() needs a MonotonicFrameClock: plain MainScope() has none and crashed every fling (r283).
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + androidx.compose.ui.platform.AndroidUiDispatcher.Main)
    var job: kotlinx.coroutines.Job? = null
    /** How far (dp) the gliding pane is above its smallest size on this glide; 0 when no glide runs. */
    var extra by mutableStateOf(0f)
}

/**
 * During a grip glide the 3D view keeps the largest size it reaches (the rest hides under the panel), so its GL surface
 * resizes once instead of on every frame: the stretched stale frames made the plate jiggle "jello" (user, r294).
 */
private fun Modifier.steadyThroughGlide() = layout { m, c ->
    val extra = GripGlide.extra.dp.roundToPx()
    val tall = if (extra > 0 && c.hasBoundedHeight) c.maxHeight + extra else -1
    val p = m.measure(if (tall > 0) c.copy(minHeight = tall, maxHeight = tall) else c)
    layout(c.constrainWidth(p.width), c.constrainHeight(p.height)) { p.place(0, 0) }
}

private val PANEL_DEFAULT = 380.dp
private val PANEL_MIN = 300.dp
/** Let go narrower than this and the panel tucks away. */
private val PANEL_SNAP = 200.dp

/** The OpenSCAD editor's dragged size in dp (0 = default); kept for the session, so leaving Prepare doesn't reset it. */
/** Height of the portrait panel (dp, 0 = default), kept while the app runs. */
private object PanelSize {
    var height by mutableStateOf(0f)
}

private object ScadEditorSize {
    var width by mutableStateOf(0f)
    var height by mutableStateOf(0f)
}

/** The settings panel's edge on wide layouts: drag to resize, tap to tuck the panel away or bring it back. */
@Composable
private fun PanelEdge(tucked: Boolean, onClick: () -> Unit, onDrag: (androidx.compose.ui.unit.Dp) -> Unit, onDragEnd: (flick: Boolean?) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) }
    val label = stringResource(if (tucked) R.string.show_panel else R.string.tuck_panel)
    val speed = remember { FingerSpeed() }
    Surface(onClick = onClick, tonalElevation = 2.dp, modifier = Modifier.width(if (tucked) 32.dp else 20.dp).fillMaxHeight()
        .trackFinger(speed)
        .draggable(drag, Orientation.Horizontal, onDragStopped = {
            val dps = with(density) { speed.release(vertical = false).toDp() }.value
            onDragEnd(if (kotlin.math.abs(dps) >= FLING_DP_PER_S) dps > 0 else null)
        })) {
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
    // The filament question comes up while a project opens: it replaces the busy dialog, which would cover it.
    state.filamentChoice?.let { FilamentChoiceDialog(it) { mode, remember -> vm.files.answerFilaments(mode, remember) } }
    state.projectOrImport?.let { name ->
        AlertDialog(
            onDismissRequest = { vm.files.answerOpenAsProject(false) },
            title = { Text(stringResource(R.string.open_or_import_title)) },
            text = { Text(stringResource(R.string.open_or_import_text, name)) },
            confirmButton = { androidx.compose.material3.TextButton({ vm.files.answerOpenAsProject(true) }) { Text(stringResource(R.string.open_as_project)) } },
            dismissButton = { androidx.compose.material3.TextButton({ vm.files.answerOpenAsProject(false) }) { Text(stringResource(R.string.import_models_only)) } },
        )
    }
    if (state.busy != null && state.phase != Phase.LOADING && state.filamentChoice == null && state.projectOrImport == null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = { state.busyCancel?.let { TextButton(onClick = it) { Text(stringResource(R.string.cancel)) } } },
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
            text = { Text(engineErrorText(message, vm.translator)) },
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

/** Opened project with other filaments: current (app) circles above, the project's below; keep mine or use the project's. */
@Composable
private fun FilamentChoiceDialog(choice: FilamentChoice, onAnswer: (mode: String, remember: Boolean) -> Unit) {
    var remember by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = { onAnswer("mine", false) }) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.width(340.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.filament_choice_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.filament_choice_text), Modifier.padding(top = 8.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                @Composable fun row(label: Int, slots: List<app.orcaandroid.core.FilamentSlot>) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        slots.take(8).forEach { app.orcaandroid.ui.prepare.ColorDot(it.color, 26) }
                    }
                }
                row(R.string.filament_current, choice.current)
                row(R.string.filament_project, choice.project)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).then(Modifier.clickable { remember = !remember }), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(remember, { remember = it })
                    Text(stringResource(R.string.filament_remember), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { onAnswer("project", remember) }, Modifier.weight(1f)) { Text(stringResource(R.string.filament_use_project)) }
                    androidx.compose.material3.Button(onClick = { onAnswer("mine", remember) }, Modifier.weight(1f)) { Text(stringResource(R.string.filament_keep_mine)) }
                }
                // Additive: the project's slots first (its objects use them), then mine that it lacks.
                androidx.compose.material3.OutlinedButton(onClick = { onAnswer("both", remember) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.filament_use_both)) }
            }
        }
    }
}

/**
 * The view cube where the user left it: top right by default; a long press on it picks it up and dragging moves it
 * (kept inside the 3D view, saved as fractions of the free room so it stays put across rotation and resizing).
 */
@Composable
private fun ViewCubeSpot(camera: app.orcaandroid.render.Camera, lab: app.orcaandroid.core.LayoutLab, vm: AppViewModel, area: androidx.compose.ui.unit.IntSize) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cube = with(density) { 96.dp.toPx() }
    val roomX = (area.width - cube).coerceAtLeast(0f)
    val roomY = (area.height - cube).coerceAtLeast(0f)
    fun saved() = if (lab.viewCubeX < 0f || lab.viewCubeY < 0f) Offset(roomX - with(density) { 8.dp.toPx() }, with(density) { 72.dp.toPx() })
        else Offset(lab.viewCubeX * roomX, lab.viewCubeY * roomY)
    var live by remember { mutableStateOf<Offset?>(null) }
    val at = (live ?: saved()).let { Offset(it.x.coerceIn(0f, roomX), it.y.coerceIn(0f, roomY)) }
    app.orcaandroid.ui.components.ViewCube(camera,
        Modifier.offset { androidx.compose.ui.unit.IntOffset(at.x.roundToInt(), at.y.roundToInt()) },
        onMove = { d -> (at + d).let { live = Offset(it.x.coerceIn(0f, roomX), it.y.coerceIn(0f, roomY)) } },
        onMoveEnd = {
            live?.let { p ->
                vm.setLab(lab.copy(viewCubeX = if (roomX > 0f) (p.x / roomX).coerceIn(0f, 1f) else 0f,
                                   viewCubeY = if (roomY > 0f) (p.y / roomY).coerceIn(0f, 1f) else 0f))
            }
            live = null
        })
}
