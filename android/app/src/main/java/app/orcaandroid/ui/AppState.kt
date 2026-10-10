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

enum class Phase { LOADING, SETUP, READY }

/** Top-level destinations, like the desktop's Prepare / Preview / Device tabs. */
enum class Screen { PREPARE, PREVIEW, DEVICE, MORE }

/** The object (and optionally one of its parts) the user works on. */
data class Selection(val obj: Int, val instance: Int = 0, val volume: Int = -1)

/** A modal interaction with the 3D view. */
sealed interface Tool {
    data object None : Tool
    data class Paint(val kind: String, val state: Int, val radius: Float) : Tool
    data object LayOnFace : Tool
    data object Measure : Tool
    data class Cut(val z: Float) : Tool
    data object LayerHeight : Tool
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
    val error: String? = null,
    val message: String? = null,
    /** Where the 3D view's context menu is open (view pixels), or null. */
    val contextMenu: Pair<Float, Float>? = null,
    val screen: Screen = Screen.PREPARE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,

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
    val measure: List<Vec3> = emptyList(),
    val layerProfile: LayerProfile? = null,
    val calibration: Calibration? = null,
    val projectName: String? = null,
    /** Unsaved changes since the project was opened/saved. */
    val projectDirty: Boolean = false,

    // Slicing and preview
    val slice: SliceStatus = SliceStatus.Idle,
    val results: Map<Int, SliceResult> = emptyMap(),
    val previewPlate: Int = 0,
    /** An opened external G-code file (preview only). */
    val external: SliceResult? = null,
    val preview: PreviewUi = PreviewUi(),

    // Device
    val connection: PrinterConnection? = null,
    val suggestedConnection: PrinterConnection? = null,
    val upload: UploadState? = null,
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

    /** Printers offered for selection: the chosen system printers plus all user printers. */
    val visiblePrinters: List<PrinterInfo>
        get() = if (selectedPrinters.isEmpty()) printers else printers.filter { !it.system || it.key in selectedPrinters }

    val selectedObject get() = selection?.let { scene.objects.getOrNull(it.obj) }

    /** Every selected copy: the multi-selection, else the single selection. */
    val selectedItems: List<Selection> get() = multiSelection.ifEmpty { listOfNotNull(selection) }

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

    val selectedPrinterKeys get() = printers.filter { it.name == printer }.map { printerKey(it.model, it.nozzle) }

    val printerInfo: PrinterInfo? get() = printers.firstOrNull { it.name == printer }
}
