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

data class PrinterInfo(val name: String, val vendor: String, val model: String, val nozzle: Double, val system: Boolean) {
    val key get() = printerKey(model, nozzle)
}

data class PresetRef(val name: String, val vendor: String, val system: Boolean, val brand: String = "") {
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
) {
    val isVector get() = type.endsWith("s") && type != "float_or_percent"
    val baseType get() = when (type) {
        "floats" -> "float"; "ints" -> "int"; "percents" -> "percent"; "bools" -> "bool"
        "strings" -> "string"; "floats_or_percents" -> "float_or_percent"; "enums" -> "enum"
        else -> type
    }
}

data class SettingsGroup(val title: String, val options: List<String>)
data class SettingsPage(val title: String, val groups: List<SettingsGroup>)

/** Which process options the desktop rules grey out / hide for the current values. */
data class OptionStates(val disabled: Set<String> = emptySet(), val hidden: Set<String> = emptySet())

/** One filament slot of the plate: preset, optional colour and per-slot edits. */
data class FilamentSlot(val preset: String, val color: String? = null, val overrides: Map<String, String> = emptyMap())

// --- Scene ---------------------------------------------------------------------------------------

enum class VolumeType(val code: Int) {
    PART(0), NEGATIVE(1), MODIFIER(2), SUPPORT_BLOCKER(3), SUPPORT_ENFORCER(4);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: MODIFIER
    }
}

data class LayerGcode(val z: Float, val type: String, val extra: String = "", val color: String = "", val extruder: Int = 1)

data class Plate(
    val index: Int,
    val originX: Float,
    val originY: Float,
    val bedType: String,
    val instances: Int,
    val layerGcodes: List<LayerGcode>,
    val wipeTower: Pair<Float, Float>?,
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
)

data class Volume(val index: Int, val name: String, val type: VolumeType, val settings: Map<String, String>, val offset: Vec3, val size: Vec3)

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
)

data class Scene(
    val meshFile: String = "",
    val meshVersion: Int = -1,
    val paintFile: String = "",
    val paintVersion: Int = -1,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
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
)
