package app.orcaandroid.net

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** A filament the print uses: its 0-based project slot, type and colour (from slice_info.config). */
data class UsedFilament(val id: Int, val type: String, val color: String)

/**
 * Which loaded tray each used filament prints from, as the desktop's print dialog sends it
 * (SelectMachineDialog::get_ams_mapping_result): [filamentCount] entries, -1 / {255, 255} for
 * filaments the plate does not use.
 */
data class AmsMapping(val filamentCount: Int, val trays: Map<Int, PrinterTray>) {
    /** "ams_mapping": the tray index per filament (AMS id * 4 + slot), -1 for the external spool or unused. */
    fun v0(): String = JSONArray((0 until filamentCount).map { trays[it]?.let(::trayIndex) ?: -1 }).toString()

    /** "ams_mapping2": {ams_id, slot_id} per filament; the external spool is {255, 0}, unused {255, 255}. */
    fun v1(): String = JSONArray((0 until filamentCount).map { i ->
        val t = trays[i]
        JSONObject().put("ams_id", t?.amsId?.toIntOrNull() ?: 255).put("slot_id", if (t == null) 255 else t.slotId.toIntOrNull() ?: 0)
    }).toString()

    /** As SelectMachineDialog: the AMS feeds the print unless every used filament is on an external spool. */
    val useAms get() = trays.values.any { !isExternal(it) }

    companion object {
        private fun isExternal(t: PrinterTray) = (t.amsId.toIntOrNull() ?: 255) >= VIRTUAL_TRAY_DEPUTY_ID

        /** DevMappingUtil's tray index: AMS / AMS lite id * 4 + slot, AMS HT (id 128+) id + slot; -1 for an external spool. */
        private fun trayIndex(t: PrinterTray): Int {
            val ams = t.amsId.toIntOrNull() ?: return -1
            val slot = t.slotId.toIntOrNull() ?: 0
            return when {
                ams >= VIRTUAL_TRAY_DEPUTY_ID -> -1
                ams >= AMS_HT_FIRST_ID -> ams + slot
                else -> ams * 4 + slot
            }
        }

        private const val VIRTUAL_TRAY_DEPUTY_ID = 254
        private const val AMS_HT_FIRST_ID = 128

        /** The used filaments listed in a .gcode.3mf's Metadata/slice_info.config. */
        fun usedFilaments(gcode3mf: File): List<UsedFilament> = ZipFile(gcode3mf).use { zip ->
            val entry = zip.getEntry("Metadata/slice_info.config") ?: return emptyList()
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            Regex("<filament\\s([^>]*)/?>").findAll(xml).mapNotNull { m ->
                val attrs = Regex("(\\w+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
                val id = attrs["id"]?.toIntOrNull() ?: return@mapNotNull null
                UsedFilament(id - 1, attrs["type"].orEmpty(), attrs["color"].orEmpty())
            }.toList()
        }

        /**
         * DevMappingUtil::ams_filament_mapping: only trays of the same type qualify; repeatedly the
         * closest colour pair (CIE76) of the remaining filaments and trays is taken, so each tray
         * serves one filament unless a later filament has exactly its colour. A filament no tray of
         * its type is left for the user to pick (absent from the result).
         */
        fun auto(used: List<UsedFilament>, trays: List<PrinterTray>): Map<Int, PrinterTray> {
            val result = mutableMapOf<Int, PrinterTray>()
            val pickedTrays = mutableSetOf<Int>()
            val remaining = used.toMutableList()
            while (remaining.isNotEmpty()) {
                var best: Triple<UsedFilament, Int, Double>? = null
                for (f in remaining) for ((j, t) in trays.withIndex()) {
                    if (!f.type.equals(t.type, ignoreCase = true)) continue
                    val d = colorDistance(f.color, t.color)
                    if (j in pickedTrays && d > 0.0001) continue
                    if (best == null || d < best.third) best = Triple(f, j, d)
                }
                val (f, j) = best ?: break
                result[f.id] = trays[j]
                pickedTrays += j
                remaining -= f
            }
            return result
        }

        /** CIE76 ΔE between two #RRGGBB colours (GUI::calc_color_distance); large when one does not parse. */
        fun colorDistance(a: String, b: String): Double {
            val la = lab(a) ?: return 999999.0
            val lb = lab(b) ?: return 999999.0
            return sqrt((0..2).sumOf { (la[it] - lb[it]).pow(2) })
        }

        private fun lab(hex: String): DoubleArray? {
            val h = hex.removePrefix("#")
            if (h.length < 6) return null
            val rgb = (0..2).map { h.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: return null }.map { c ->
                val v = c / 255.0
                if (v > 0.04045) ((v + 0.055) / 1.055).pow(2.4) else v / 12.92
            }
            val x = (rgb[0] * 0.4124 + rgb[1] * 0.3576 + rgb[2] * 0.1805) / 0.95047
            val y = rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
            val z = (rgb[0] * 0.0193 + rgb[1] * 0.1192 + rgb[2] * 0.9505) / 1.08883
            fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
            return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
        }
    }
}
