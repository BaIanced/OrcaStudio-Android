package com.orcaslicer.android.core

import org.json.JSONArray
import org.json.JSONObject

data class Vendor(val id: String, val name: String, val models: List<PrinterModel>, val required: Boolean)

/** A printer model of a vendor and the nozzle diameters it has profiles for (e.g. "0.4"). */
data class PrinterModel(val name: String, val nozzles: List<String>)

/** Identifies one selectable printer: model + nozzle, e.g. "Qidi Q1 Pro|0.4". */
fun printerKey(model: String, nozzle: String): String = "$model|${nozzle.toDoubleOrNull() ?: nozzle}"
fun printerKey(model: String, nozzle: Double): String = "$model|$nozzle"

data class PrinterInfo(val name: String, val vendor: String, val model: String, val nozzle: Double, val system: Boolean)

data class PresetRef(val name: String, val vendor: String, val system: Boolean)

data class PrinterSetup(
    val prints: List<PresetRef>,
    val filaments: List<PresetRef>,
    /** Printable area outline in bed coordinates (mm), as x0,y0,x1,y1,... */
    val bed: FloatArray,
    val maxHeight: Float,
    val defaultPrint: String,
    val defaultFilament: String,
)

data class ModelInfo(
    val objects: Int,
    val triangles: Int,
    val fits: Boolean,
    val min: FloatArray,
    val size: FloatArray,
    /** Preview mesh written by the core: 6 floats (position, normal) per vertex. */
    val meshFile: String,
)

/** A layer of the toolpath preview: its height and the index of its first segment. */
data class LayerMark(val z: Float, val firstSegment: Int)

data class SliceResult(
    val gcodeFile: String,
    val previewFile: String,
    val printTimeSeconds: Double,
    val filamentMm: Double,
    val filamentGrams: Double,
    val cost: Double,
    val layers: List<LayerMark>,
    val warnings: List<String>,
)

class OrcaException(message: String) : Exception(message)

/** The three editable preset kinds, named as the native core expects. */
enum class PresetType(val id: String) {
    PRINT("print"), FILAMENT("filament"), PRINTER("printer");
}

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

/** Per-type option overrides on top of the selected presets: key -> serialized value. */
typealias Overrides = Map<PresetType, Map<String, String>>

fun Overrides.merged(): JSONObject = JSONObject().apply {
    // Printer first, so process and filament win where keys overlap (as in the desktop app).
    for (type in listOf(PresetType.PRINTER, PresetType.FILAMENT, PresetType.PRINT))
        this@merged[type]?.forEach { (k, v) -> put(k, v) }
}

internal fun JSONObject.throwIfError(): JSONObject {
    if (has("error")) throw OrcaException(getString("error"))
    return this
}

internal inline fun <T> JSONArray.map(transform: (Any) -> T): List<T> = List(length()) { transform(get(it)) }

internal fun JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }

internal fun JSONArray.toPresetRefs(): List<PresetRef> = map {
    val o = it as JSONObject
    PresetRef(o.getString("name"), o.optString("vendor"), o.optBoolean("system"))
}
