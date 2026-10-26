package app.orcaandroid.core

// Plain data exchanged with the native engine. JSON parsing lives in Engine.kt.

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = kotlin.math.sqrt(dot(this))
    fun toList() = listOf(x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

// --- Presets -------------------------------------------------------------------------------------

/** The three editable preset kinds, named as the native core expects. */
enum class PresetType(val id: String) { PRINT("print"), FILAMENT("filament"), PRINTER("printer") }

data class Vendor(val id: String, val name: String, val models: List<PrinterModel>, val required: Boolean, val version: String)

/** A printer model of a vendor and the nozzle diameters it has profiles for (e.g. "0.4"). */
data class PrinterModel(val name: String, val nozzles: List<String>)

/** Identifies one selectable printer: model + nozzle, e.g. "Qidi Q1 Pro|0.4". */
fun printerKey(model: String, nozzle: String): String = "$model|${nozzle.toDoubleOrNull() ?: nozzle}"
fun printerKey(model: String, nozzle: Double): String = "$model|$nozzle"

/** [base]: the system preset a user preset inherits (empty for system presets). */
data class PrinterInfo(val name: String, val vendor: String, val model: String, val nozzle: Double, val system: Boolean, val base: String = "") {
    val key get() = printerKey(model, nozzle)
    val isBambu get() = vendor == "BBL" || vendor == "Bambu Lab" || model.startsWith("Bambu Lab")
}

data class PresetRef(
    val name: String, val vendor: String, val system: Boolean, val brand: String = "", val type: String = "",
    /** A user preset's system parent, else "". */
    val base: String = "",
    /** A user filament's own colour (#RRGGBB), else "". */
    val color: String = "",
) {
    /** A system filament's brand (Bambu Lab, Generic, eSUN ...), for the brand filter and grouping. */
    val brandName get() = brand.ifEmpty { vendor.ifEmpty { "Generic" } }
}

data class PrinterSetup(
    val prints: List<PresetRef>,
    val filaments: List<PresetRef>,
    /** Printable area outline in bed coordinates (mm), as x0,y0,x1,y1,... */
    val bed: FloatArray,
    val maxHeight: Float,
    val defaultPrint: String,
    val defaultFilament: String,
    /** Plate types the printer supports ("Cool Plate", ...); empty if it has just one. */
    val bedTypes: List<String>,
    val defaultBedType: String,
)

/** Definition of one config option (libslic3r ConfigOptionDef). */
data class OptionDef(
    val key: String,
    val label: String,
    val fullLabel: String,
    val category: String,
    val tooltip: String,
    /** "float", "ints", "enum", "bool", "percent", "float_or_percent", "string", ... */
    val type: String,
    val sidetext: String,
    /** 0 simple, 1 advanced, 2 expert, 3 develop. */
    val mode: Int,
    val min: Double?,
    val max: Double?,
    val enumValues: List<String>,
    val enumLabels: List<String>,
    val readonly: Boolean,
    val multiline: Boolean,
    val isCode: Boolean,
    /** Capability type a plugin picker option selects from ("slicing-pipeline"), else empty. */
    val pluginType: String = "",
) {
    val isVector get() = type.endsWith("s") && type != "float_or_percent"
    val baseType get() = when (type) {
        "floats" -> "float"; "ints" -> "int"; "percents" -> "percent"; "bools" -> "bool"
        "strings" -> "string"; "floats_or_percents" -> "float_or_percent"; "enums" -> "enum"
        else -> type
    }
}

/** [rows]: option key -> label of the desktop row it shares with others (e.g. "Cool Plate" for its "First layer"). */
data class SettingsGroup(val title: String, val options: List<String>, val rows: Map<String, String> = emptyMap())
data class SettingsPage(val title: String, val groups: List<SettingsGroup>)

/** Which process options the desktop rules grey out / hide for the current values. */
data class OptionStates(val disabled: Set<String> = emptySet(), val hidden: Set<String> = emptySet())

/** One filament slot of the plate: preset, optional colour, per-slot edits and the AMS tray a sync filled it from; [look] = the FilamentMaterial name picked for the Prepare view (null = from the preset name). */
data class FilamentSlot(val preset: String, val color: String? = null, val overrides: Map<String, String> = emptyMap(), val source: TraySource? = null, val look: String? = null)

/**
 * The tray an AMS sync filled a slot from: [key] = printer serial / AMS / slot, [loaded] = the filament id and
 * colour it reported, [synced] = the preset the sync chose for it.
 */
data class TraySource(val key: String, val loaded: String, val synced: String)

// --- Scene ---------------------------------------------------------------------------------------

enum class VolumeType(val code: Int) {
    PART(0), NEGATIVE(1), MODIFIER(2), SUPPORT_BLOCKER(3), SUPPORT_ENFORCER(4);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: MODIFIER
    }
}

/**
 * One volume for [Engine.setVolumes]: mesh from [stl] or kept from volume [keep]; [matrix] is row-major 4x4
 * (null only with [keep]: the volume keeps its own placement).
 */
data class VolumeSpec(val type: VolumeType, val matrix: List<Double>?, val stl: String? = null, val keep: Int = -1, val name: String = "",
                      val anchor: Int = -1, val extruder: Int = -1)

data class LayerGcode(val z: Float, val type: String, val extra: String = "", val color: String = "", val extruder: Int = 1)

data class Plate(
    val index: Int,
    val originX: Float,
    val originY: Float,
    val bedType: String,
    val instances: Int,
    val layerGcodes: List<LayerGcode>,
    val wipeTower: Pair<Float, Float>?,
    /** The prime tower box as drawn and sliced: x, y, width, depth, height in plate mm; null when the plate prints none. */
    val tower: FloatArray? = null,
)

data class Instance(
    val index: Int,
    val plate: Int,
    val offset: Vec3,
    val rotation: Vec3,
    val scale: Vec3,
    val mirror: Vec3,
    val size: Vec3,
    val min: Vec3,
    /** The Build workspace's object: this copy as the Build view draws it (design pose: no turn, scale or mirror). */
    val design: Instance? = null,
) {
    /** The copy as the main view draws and picks it. */
    val shown: Instance get() = design ?: this
}

data class Volume(
    val index: Int, val name: String, val type: VolumeType, val settings: Map<String, String>, val offset: Vec3, val size: Vec3,
    /** Degrees and factors, in object coordinates. */
    val rotation: Vec3 = Vec3(0f, 0f, 0f), val scale: Vec3 = Vec3(1f, 1f, 1f),
)

data class LayerRange(val from: Float, val to: Float, val settings: Map<String, String>)

/** Settings an object, part or range overrides, without the filament assignment (`extruder`), which has its own picker. */
val Map<String, String>.overrides: Map<String, String> get() = filterKeys { it != "extruder" }

data class SceneObject(
    val index: Int,
    val name: String,
    val triangles: Int,
    val settings: Map<String, String>,
    val volumes: List<Volume>,
    val instances: List<Instance>,
    val layerRanges: List<LayerRange>,
    val hasLayerProfile: Boolean,
    /** ModelObject::module_name, kept per object in the 3MF; "openscad:{...}" = the object's OpenSCAD design. */
    val module: String = "",
)

data class Scene(
    val meshFile: String = "",
    val meshVersion: Int = -1,
    val paintFile: String = "",
    val paintVersion: Int = -1,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val calibrationActive: Boolean = false,
    val plateWidth: Float = 200f,
    val plateDepth: Float = 200f,
    val plates: List<Plate> = emptyList(),
    val objects: List<SceneObject> = emptyList(),
) {
    val isEmpty get() = objects.isEmpty()
    fun objectsOn(plate: Int) = objects.filter { o -> o.instances.any { it.plate == plate } }
    val outsideCount get() = objects.sumOf { o -> o.instances.count { it.plate < 0 } }
}

/** Nearest ray hit on the scene (see Engine.pick). */
data class PickHit(
    val obj: Int,
    val instance: Int,
    val volume: Int,
    val facet: Int,
    val point: Vec3,
    val normal: Vec3,
    val local: Vec3,
    /** "vertex" / "edge" when the point snapped to a corner / sharp edge of its facet (measure tool). */
    val snap: String? = null,
)

data class LayerProfile(val profile: FloatArray, val min: Float, val max: Float, val height: Float)

data class ProjectInfo(
    val printer: String,
    val print: String,
    val filaments: List<String>,
    val printOverrides: Map<String, String>,
    val filamentOverrides: List<Map<String, String>>,
    val filamentColors: List<String>,
)

data class Calibration(val type: String, val name: String, val start: Float, val end: Float, val step: Float)

// --- Slicing / preview ---------------------------------------------------------------------------

/** A layer of the toolpath preview: height, first element index in each buffer, print time. */
data class PreviewLayer(val z: Float, val extrusion: Int, val travel: Int, val marker: Int, val time: Float)

data class RoleStat(val role: Int, val time: Float, val filamentM: Float, val filamentG: Float)

data class SliceResult(
    val gcodeFile: String,
    val previewDir: String,
    val printTimeSeconds: Double,
    val filamentMm: Double,
    val filamentGrams: Double,
    val cost: Double,
    val warnings: List<String>,
    val originX: Float,
    val originY: Float,
    val layers: List<PreviewLayer>,
    val roles: List<RoleStat>,
    val travelTime: Float,
    /** Value ranges for the colour schemes: speed, fan, temperature, volumetric, width, height. */
    val ranges: Map<String, Pair<Float, Float>>,
    val filamentUse: List<Pair<Float, Float>>,
    /** Number of extrusion segments in the preview buffers. */
    val extrusionCount: Int = 0,
    /** True for an opened external G-code file (no plate/scene behind it). */
    val external: Boolean = false,
    /**
     * Tool changes counted in [gcodeFile] after the slice (Task 11), parsed off the main
     * thread and attached to this result; null until the parse finishes (or if it fails).
     * Re-slicing replaces the whole result, which discards the old count with it.
     */
    val toolChanges: GcodeToolChanges.Result? = null,
)

/**
 * Scene triangles a live sculpt call changed, read from the engine's patch file (OrcaSculpt.cpp): run i replaces
 * [counts] i triangles from scene triangle [firsts] i; [data] holds all runs' vertices back to back (8 floats each, as
 * the scene mesh). Applies to scene mesh [version] only.
 */
class MeshPatch(val version: Int, val firsts: IntArray, val counts: IntArray, val data: java.nio.FloatBuffer) {
    companion object {
        fun load(path: String, version: Int): MeshPatch {
            val bytes = java.io.File(path).readBytes()
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
            val runs = b.int
            val firsts = IntArray(runs)
            val counts = IntArray(runs)
            var floats = 0
            for (i in 0 until runs) {
                firsts[i] = b.int
                counts[i] = b.int
                b.position(b.position() + counts[i] * 96)
                floats += counts[i] * 24
            }
            val out = java.nio.ByteBuffer.allocateDirect(maxOf(floats, 1) * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            b.position(4)
            for (i in 0 until runs) {
                b.position(b.position() + 8)
                val n = counts[i] * 24
                val slice = b.slice().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                slice.limit(n)
                out.put(slice)
                b.position(b.position() + n * 4)
            }
            out.position(0)
            return MeshPatch(version, firsts, counts, out)
        }
    }
}

/**
 * One live sculpt call: [scene] when the whole scene mesh was rewritten (refined at the stroke's start), [patch] the changed
 * triangles, [cursor] the last dab's world point and surface normal (the brush ring).
 */
class SculptStep(val scene: Scene?, val patch: MeshPatch?, val hit: Boolean, val cursor: Pair<Vec3, Vec3>? = null)
