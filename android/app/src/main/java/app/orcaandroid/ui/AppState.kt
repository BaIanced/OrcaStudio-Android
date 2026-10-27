package app.orcaandroid.ui

import app.orcaandroid.core.Calibration
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.core.LayerProfile
import app.orcaandroid.core.OptionStates
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.PrinterInfo
import app.orcaandroid.core.PrinterSetup
import app.orcaandroid.core.RecentFile
import app.orcaandroid.core.ResourceStore
import app.orcaandroid.core.Scene
import app.orcaandroid.core.SliceResult
import app.orcaandroid.core.ThemeMode
import app.orcaandroid.core.Vec3
import app.orcaandroid.core.Vendor
import app.orcaandroid.core.printerKey
import app.orcaandroid.net.DiscoveredPrinter
import app.orcaandroid.net.PrinterConnection
import app.orcaandroid.net.PrinterStatus
import app.orcaandroid.net.PrinterTray
import app.orcaandroid.net.UsedFilament

enum class Phase { LOADING, SETUP, READY }

/** Top-level destinations, like the desktop's Prepare / Preview / Device tabs. */
enum class Screen { PREPARE, PREVIEW, DEVICE, MODELS, MORE }

/** The object (and optionally one of its parts) the user works on. */
data class Selection(val obj: Int, val instance: Int = 0, val volume: Int = -1)

/** A modal interaction with the 3D view. */
sealed interface Tool {
    data object None : Tool
    // edgeAngle > 0: edge detection (degrees, the desktop's smart fill angle): a stroke stays on the surface it started on.
    /** [shape]: "sphere" / "circle" brush, "triangle", "fill" (bucket fill, bounded by [edgeAngle]), "height" (band of [height] mm). [overhang] > 0: support brushes paint only faces overhanging by more than that many degrees. */
    data class Paint(val kind: String, val state: Int, val radius: Float, val edgeAngle: Float = 0f, val shape: String = "sphere",
                     val height: Float = 0.2f, val overhang: Float = 0f,
                     /** > 0: support painting tints the selection's faces overhanging by more than this many degrees (the desktop's "Highlight overhangs"). */
                     val highlight: Float = 0f,
                     /** Brush stroke locked to a screen axis through its first point: "v" (straight up/down), "h" (sideways) or "". */
                     val lock: String = "",
                     /** The shape the eyedropper ("pick") goes back to after a pick. */
                     val back: String = "sphere",
                     /** Guard plane: painting stops at the horizontal plane at [guardZ] mm (world) while [guardOn]. */
                     val guardOn: Boolean = false, val guardZ: Float = 10f,
                     /** Fill over colours: the fill ignores existing colours and stops only at edges / the guard plane. */
                     val overColours: Boolean = false,
                     /** Paint shop layers (bit i = filament i + 1): [hidden] ones are drawn grey, [locked] ones no paint covers. */
                     val hidden: Int = 0, val locked: Int = 0,
                     /** Colour painting's Object task mode (its shape is plain "fill", so the mode needs its own flag;
                      *  it used to be a composable-local in ColorPaintOptions and was lost when the paint shop left
                      *  composition, landing the user on Fill). Session-only: [UiState] is never persisted. */
                     val objectMode: Boolean = false,
                     /** The brush / fill shapes each paint mode used last (Task 11, the Task 7 follow-up: these
                      *  were `rememberSaveable` in ColorPaintOptions and died with its composition, like Object
                      *  mode did). Session-only, like [objectMode]. */
                     val brushShape: String = "sphere", val fillShape: String = "fill",
                     /** The fill settings Object mode overrides, saved on entering it and restored on leaving
                      *  (was a plain `remember` snapshot in ColorPaintOptions, lost the same way). */
                     val fillKeepEdgeAngle: Float = 0f, val fillKeepOverColours: Boolean = false,
                     val fillKeepGuardOn: Boolean = false) : Tool
    /** Sculpt mode: a drag on the selected object dabs [brush] (draw, inflate, smooth, flatten, pinch, crease, grab) of [radius] mm. */
    data class Sculpt(val brush: String = "draw", val radius: Float = 5f, val strength: Float = 0.5f, val invert: Boolean = false) : Tool
    data object LayOnFace : Tool
    data object Measure : Tool
    /**
     * The cut plane passes through the object's middle moved by ([dx], [dy]) at height [z], turned by [rot] (Euler degrees) from the
     * horizontal (an upright plane moves by the shift, any other by [z]); [placing]: the panel's height slider is held (the view
     * shows the copy x-ray).
     */
    data class Cut(val z: Float, val rot: Vec3 = Vec3(0f, 0f, 0f), val placing: Boolean = false, val dx: Float = 0f, val dy: Float = 0f) : Tool
    data object LayerHeight : Tool
}

/**
 * The tool in force after switching from screen [from] to screen [to]. Prepare and
 * Preview are two views of the same workspace (the preview viewport already ignores
 * the tool), so the tool — the paint mode included — survives switching between
 * them; leaving the workspace for another tab clears it, as before.
 */
fun toolAfterScreenChange(from: Screen, to: Screen, tool: Tool): Tool = when {
    to == Screen.PREPARE -> tool
    from == Screen.PREPARE && to == Screen.PREVIEW -> tool
    else -> Tool.None
}

/** What the settings editor currently edits. */
sealed interface EditorTarget {
    /** [pickCompare]: open with the "compare with" picker showing (More > Compare presets). */
    data class Preset(val type: PresetType, val pickCompare: Boolean = false) : EditorTarget
    /** Per-object (volume < 0) or per-part/modifier settings. */
    data class Object(val obj: Int, val volume: Int = -1) : EditorTarget
    data class Range(val obj: Int, val range: Int) : EditorTarget
    /** Side-by-side comparison of two presets of a type. */
    data class Compare(val type: PresetType, val left: String, val right: String) : EditorTarget
}

sealed interface SliceStatus {
    data object Idle : SliceStatus
    data class Running(val plate: Int, val percent: Int, val text: String) : SliceStatus
}

sealed interface UploadState {
    data class Running(val progress: Float, val startPrint: Boolean) : UploadState
    data class Done(val message: String) : UploadState
}

/** A button on the message bar ([UiState.message]). */
class MessageAction(val label: String, val run: () -> Unit)

/** A Bambu print waiting for the tray of each used filament; [choice] starts as the desktop's automatic mapping. */
data class AmsPrompt(
    val used: List<UsedFilament>,
    val trays: List<PrinterTray>,
    val choice: Map<Int, PrinterTray>,
    val filamentCount: Int,
)

/** A print or send held back because the sliced printer model differs from the connected one. */
data class MismatchPrompt(val sliced: String, val connected: String, val startPrint: Boolean)

/** How the preview is shown. */
data class PreviewUi(
    val layerLow: Int = 0,
    val layerHigh: Int = 0,
    /** Number of segments drawn in the top layer (moves slider); null = all. */
    val moveEnd: Int? = null,
    val scheme: Int = 0,
    val hiddenRoles: Int = 0,
    val showTravels: Boolean = false,
    val showRetracts: Boolean = false,
    val showSeams: Boolean = false,
    val showGcode: Boolean = false,
)

data class UiState(
    val phase: Phase = Phase.LOADING,
    val busy: String? = null,
    /** Cancels the [busy] operation (Cancel button on the busy dialog); null = not cancellable. */
    val busyCancel: (() -> Unit)? = null,
    val error: String? = null,
    val message: String? = null,
    val messageAction: MessageAction? = null,
    /** A page the Models tab's browser should open next (e.g. a 3D Filament Profiles print log). */
    val modelsUrl: String? = null,
    /** Where the 3D view's context menu is open (view pixels), or null. */
    val contextMenu: Pair<Float, Float>? = null,
    /**
     * The copy whose box corners resize it (Resize in the context menu, Ctrl+T or S); off again once the
     * selection changes, so a drag can't resize by accident (user, 2026-10-19).
     */
    val resizeFor: Selection? = null,
    /** The copy the rotate gizmo is on (Rotate in the context menu or R), as [resizeFor]. */
    val rotateFor: Selection? = null,
    /** An add dialog asked for by a shortcut ("text" for T), shown by the 3D view's context menu host. */
    val addDialog: String? = null,
    val screen: Screen = Screen.PREPARE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val themeSpec: app.orcaandroid.core.ThemeSpec = app.orcaandroid.core.ThemeSpec(),
    /** The floating colour panel is open over the app (design mode). */
    val designMode: Boolean = false,
    val doubleBackToQuit: Boolean = false,
    val pen: app.orcaandroid.core.PenConfig = app.orcaandroid.core.PenConfig(),
    /** What an opened project does to the filament slots ("ask", "project", "mine") and the question being asked. */
    val projectFilaments: String = "ask",
    val filamentChoice: FilamentChoice? = null,
    /** A project file dropped onto a plate with objects: its name while asking open-as-project vs import. */
    val projectOrImport: String? = null,
    /** 3D view contact shadows. */
    val contactShadows: Boolean = true,
    /** 3D view realistic render mode (physically based shading, studio lighting). */
    val realistic: Boolean = false,
    /** 3D view printed layer lines on the models. */
    val layerLines: Boolean = false,
    /** 3D view: dark outline along the models' crease edges (View menu). */
    val edgeOutline: Boolean = false,
    /** How the selection shows ([app.orcaandroid.render.SelectionStyle] bits). */
    val selectionStyle: Int = app.orcaandroid.render.SelectionStyle.DEFAULT,
    /** Preview supersampling ([app.orcaandroid.render.Supersample]). */
    val supersample: Int = app.orcaandroid.render.Supersample.STILL,
    /** 3D view projection: perspective, or orthographic when false. */
    val perspective: Boolean = true,
    val lab: app.orcaandroid.core.LayoutLab = app.orcaandroid.core.LayoutLab(),
    val undoLimit: Int = app.orcaandroid.core.DEFAULT_UNDO_LIMIT,
    /** Auto-save interval in seconds; 0 or less = off (-seconds keeps the interval it had). */
    val autoSaveSeconds: Int = app.orcaandroid.core.DEFAULT_AUTO_SAVE_SECONDS,
    /** The MCP server for AI clients is switched on; [aiToken] is what they must send. */
    val aiTools: Boolean = false,
    val aiToken: String = "",
    /** It also listens on the local network; [aiAddresses] are this device's addresses there. */
    val aiToolsLan: Boolean = false,
    val aiAddresses: List<String> = emptyList(),
    /** What an AI tool asks the user to allow (shown as a dialog), or null. */
    val aiConfirm: String? = null,
    /** Why the server isn't running although switched on (port taken, ...). */
    val aiError: String? = null,

    // Printers and presets
    val vendors: List<Vendor> = emptyList(),
    val selectedPrinters: Set<String> = emptySet(),
    val printers: List<PrinterInfo> = emptyList(),
    val printer: String? = null,
    val setup: PrinterSetup? = null,
    val print: String? = null,
    val filaments: List<FilamentSlot> = emptyList(),
    /** Filament slot shown in the settings editor. */
    val activeFilament: Int = 0,
    /** Option values of the selected presets (filament: of the active slot). */
    val presetValues: Map<PresetType, Map<String, String>> = emptyMap(),
    /** Unsaved edits of the process and printer presets (filament edits live in the slots). */
    val overrides: Map<PresetType, Map<String, String>> = emptyMap(),
    val optionStates: OptionStates = OptionStates(),
    val hiddenFilamentVendors: Set<String> = emptySet(),
    /** Plate types left out of the plate type pickers, and the user's default one ("" = the printer's). */
    val hiddenBedTypes: Set<String> = emptySet(),
    val preferredBedType: String = "",
    /** AMS sync and new slots take the user's own filament preset saved from a system one (switchable). */
    val preferMyFilaments: Boolean = true,
    val allowMixedTemps: Boolean = false,
    /** Printer presets with another nozzle than the model's [installedNozzles] entry are hidden (switchable). */
    val matchNozzle: Boolean = true,
    val installedNozzles: Map<String, Float> = emptyMap(),
    val editor: EditorTarget? = null,
    val showPrinterSetup: Boolean = false,

    // Scene
    val scene: Scene = Scene(),
    val activePlate: Int = 0,
    val selection: Selection? = null,
    /** Two or more copies selected together (Ctrl/Shift click, select mode); [selection] is null then. */
    val multiSelection: List<Selection> = emptyList(),
    /** Taps on the 3D view and the object list add or remove objects instead of replacing the selection. */
    val selectMode: Boolean = false,
    val tool: Tool = Tool.None,
    /** Measure tool: the placed points (at most 2). */
    val measure: List<MeasurePoint> = emptyList(),
    /** Measure tool: points snap to corners and edges (off: the bare hit; Alt held does the same for one click). */
    val measureSnap: Boolean = true,
    /** Measure tool: a line near the X, Y or Z axis locks to it (measured along it, in the axis colour). */
    val measureAxes: Boolean = true,
    /** Measure tool: the line locks to 15° steps (the touch toggle; Shift held does the same). */
    val measureSteps: Boolean = false,
    /** Measure tool: a held finger steers the dot and the camera follows it (hold = zoom in, fast slide = out; interaction plan step 6). */
    val measureSteer: Boolean = false,
    /** "Show overhangs" (quick settings): every part's faces the support threshold angle would support are tinted red; [overhangDrag] = the
     *  angle slider while it moves (degrees), before it is written to the print settings. Interaction plan step 7. */
    val overhangView: Boolean = false,
    val overhangDrag: Float? = null,
    /** Measure tool: Shift held now, and whether it was held when the second point went down (that pair keeps its steps). */
    val measureShift: Boolean = false,
    val measureShiftPlaced: Boolean = false,
    /** Measure tool, loupe up (finger held, or a mouse / pen hovering): the aim spot, and the point (snapped, with how) the dot sits on. */
    val loupe: Loupe? = null,
    val layerProfile: LayerProfile? = null,
    val calibration: Calibration? = null,
    val projectName: String? = null,
    /** The project name before a calibration replaced it, restored when Undo leaves the calibration. */
    val nameBeforeCalibration: String? = null,
    /** Unsaved changes since the project was opened/saved. */
    val projectDirty: Boolean = false,

    // Slicing and preview
    val slice: SliceStatus = SliceStatus.Idle,
    val results: Map<Int, SliceResult> = emptyMap(),
    /** Why the last slice failed (plate, message), shown on the 3D view (Notices); cleared by the next slice. */
    val sliceError: Pair<Int, String>? = null,
    val previewPlate: Int = 0,
    /** An opened external G-code file (preview only). */
    val external: SliceResult? = null,
    val preview: PreviewUi = PreviewUi(),

    // Device
    val connection: PrinterConnection? = null,
    val suggestedConnection: PrinterConnection? = null,
    val upload: UploadState? = null,
    val amsPrompt: AmsPrompt? = null,
    val mismatchPrompt: MismatchPrompt? = null,
    val printerStatus: PrinterStatus? = null,
    /** Why the last status request failed, shown instead of waiting forever. */
    val printerStatusError: String? = null,
    val discovered: List<DiscoveredPrinter> = emptyList(),
    val discovering: Boolean = false,

    // Files and updates
    val recents: List<RecentFile> = emptyList(),
    val profileUpdates: List<ResourceStore.ProfileUpdate>? = null,
    /** Progress text while profile updates install in the background; null when idle. */
    val profileUpdateProgress: String? = null,
    /** Report of a crash that closed the app last time (see CrashReport). */
    val crashReport: String? = null,
    /** The OpenSCAD editor, while open. */
    val scad: ScadEditorState? = null,
    /** On narrow screens: the editor is folded away to show the plate ("Hide editor"). */
    val scadHidden: Boolean = false,
    /** The OpenSCAD design is shown on the active plate (eye toggle) instead of alone in the design view. */
    val scadOnPlate: Boolean = false,
    /** Build workspace: push / pull is on (a drag on a flat face moves it instead of the shape). */
    val buildPush: Boolean = false,
) {
    fun presetName(type: PresetType) = when (type) {
        PresetType.PRINT -> print
        PresetType.FILAMENT -> filaments.getOrNull(activeFilament)?.preset
        PresetType.PRINTER -> printer
    }

    fun overridesOf(type: PresetType): Map<String, String> =
        if (type == PresetType.FILAMENT) filaments.getOrNull(activeFilament)?.overrides.orEmpty() else overrides[type].orEmpty()

    /** Effective value of an option: the unsaved edit if any, else the preset's value. */
    fun value(type: PresetType, key: String): String? = overridesOf(type)[key] ?: presetValues[type]?.get(key)

    fun isModified(type: PresetType, key: String) = overridesOf(type).containsKey(key)

    /**
     * Printers offered for selection: the chosen system printers plus all user printers; with
     * [matchNozzle], not those whose nozzle differs from what that model last reported (the selected one stays).
     */
    val visiblePrinters: List<PrinterInfo>
        get() = (if (selectedPrinters.isEmpty()) printers else printers.filter { !it.system || it.key in selectedPrinters })
            .filter { it.name == printer || !matchNozzle || nozzleFits(it) }

    fun nozzleFits(p: PrinterInfo) = installedNozzles[p.model]?.let { kotlin.math.abs(it - p.nozzle) < 1e-3 } ?: true

    /** The selected printer preset's nozzle differs from the one installed in that printer, which is [Float]. */
    val nozzleMismatch: Float? get() = printerInfo?.takeIf { !nozzleFits(it) }?.let { installedNozzles[it.model] }

    val selectedObject get() = selection?.let { scene.objects.getOrNull(it.obj) }

    /**
     * The measure line's two ends as drawn (the last placed point and the loupe's preview, else the two placed points)
     * and the direction it locks to, if any.
     */
    val measureLine: Triple<MeasurePoint, MeasurePoint, MeasureLock?>? get() {
        val (a, b) = (measure + listOfNotNull(loupe?.preview)).takeLast(2).takeIf { it.size == 2 } ?: return null
        val steps = measureSteps || measureShift || (loupe?.preview == null && measureShiftPlaced)
        return Triple(a, b, measureLock(a, b, measureAxes, steps))
    }

    /** Every selected copy: the multi-selection, else the single selection. */
    val selectedItems: List<Selection> get() = multiSelection.ifEmpty { listOfNotNull(selection) }

    /** Corner resizing is on for the lone selected copy. */
    val resizing: Boolean get() = resizeFor != null && selectedItems.singleOrNull() == resizeFor

    /** The rotate gizmo is on for the lone selected copy. */
    val rotating: Boolean get() = rotateFor != null && selectedItems.singleOrNull() == rotateFor

    /** Selects the copies [items]: one becomes the single selection, more a multi-selection. */
    fun withSelected(items: List<Selection>) = copy(
        selection = items.singleOrNull(),
        multiSelection = if (items.size > 1) items else emptyList(),
        tool = if (items.size == 1) tool else Tool.None,
    )

    /** Adds [items] to the selection, or removes them when all of them are selected already. */
    fun toggled(items: List<Selection>): UiState {
        val current = selectedItems.map { it.copy(volume = -1) }
        val keys = items.map { it.copy(volume = -1) }
        return withSelected(if (keys.all { it in current }) current - keys.toSet() else current + keys.filterNot { it in current })
    }

    /** The result shown in the preview (external G-code or the preview plate's slice). */
    val shownResult: SliceResult? get() = external ?: results[previewPlate]

    val isSlicing get() = slice is SliceStatus.Running

    /** Process options printers without multiple plate types never see. */
    val supportsBedTypes get() = setup?.bedTypes?.isNotEmpty() == true

    /** Plate type [plate] slices with: its own, else the default. */
    fun bedTypeOf(plate: Int): String? = scene.plates.getOrNull(plate)?.bedType?.ifEmpty { null } ?: setup?.defaultBedType

    /** Plate types the pickers offer: the visible ones, plus [current] even when hidden. */
    fun bedTypeChoices(current: String?): List<String> = setup?.bedTypes.orEmpty().filter { it !in hiddenBedTypes || it == current }

    val selectedPrinterKeys get() = printers.filter { it.name == printer }.map { printerKey(it.model, it.nozzle) }

    val printerInfo: PrinterInfo? get() = printers.firstOrNull { it.name == printer }
}

/** A project came with filaments that differ from the app's: the user picks which slots to keep. */
data class FilamentChoice(val current: List<app.orcaandroid.core.FilamentSlot>, val project: List<app.orcaandroid.core.FilamentSlot>)

data class Loupe(val x: Float, val y: Float, val point: Vec3?, val snap: String?, val feature: app.orcaandroid.core.SnapFeature? = null,
                 /** What a click here would place (a ring: its centre). */
                 val preview: MeasurePoint? = null)
