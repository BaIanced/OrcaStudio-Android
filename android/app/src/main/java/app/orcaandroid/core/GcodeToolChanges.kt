package app.orcaandroid.core

import kotlin.math.PI

/**
 * Counts tool changes in sliced G-code and collects the filaments printed per layer, as a
 * cross-check of [ColourChangeEstimate] (whose `estimate` takes exactly the [Result.perLayer]
 * shape). Pure JVM: no Android imports, the caller feeds it the G-code lines.
 *
 * Markers relied on, from the OrcaSlicer sources in `src-orca/`:
 * - Layer start: the comment line `; CHANGE_LAYER` — the reserved tag is defined in
 *   `src/libslic3r/GCode/GCodeProcessor.cpp:105` (`" CHANGE_LAYER"`) and emitted at
 *   `src/libslic3r/GCode.cpp:3941` and `:5758`.
 * - Feature blocks: comment lines `; FEATURE: <name>` — the tag is defined in
 *   `src/libslic3r/GCode/GCodeProcessor.cpp:100` (`" FEATURE: "`); the prime tower's name is
 *   `"Prime tower"` (`src/libslic3r/ExtrusionEntity.cpp:693`, role `erWipeTower`). The
 *   non-Bambu flavour writes `; TYPE: <name>` instead (the compatible tag list in the same
 *   file), so both spellings are recognised. `; WIPE_TOWER_START` / `; WIPE_TOWER_END`
 *   (same tag list, `:115-116`) also delimit prime-tower extrusion.
 * - Flush to the chute: `; FLUSH_START` / `; FLUSH_END` (`GCodeProcessor.cpp:154-155`), which
 *   the Bambu A1 / A1 mini `change_filament_gcode` writes around each purge (same profile
 *   file); the purge between them never reaches the prime tower.
 * - Tool selection: `GCodeWriter::toolchange` (`src/libslic3r/GCodeWriter.cpp:719-744`)
 *   writes `M1020 S<n>` on Bambu printers or the toolchange prefix plus the id
 *   (`toolchange_prefix`, `:709-717`: plain `T<n>`, `M135 T<n>` / `M108 T<n>` on other
 *   flavours, or the comment `; MANUAL_TOOL_CHANGE T<n>` for a manual change), unless the
 *   profile's `change_filament_gcode` already changes the tool
 *   (`src/libslic3r/GCode.cpp:1400` processes it, `:1427-1428` appends the command only when
 *   it does not). The Bambu profiles' `change_filament_gcode`
 *   (`resources/profiles/BBL/machine/Bambu Lab A1 0.4 nozzle template
 *   change_filament_gcode.json:5`) selects with `M620 S<n>A` … `T<n>` … `M621 S<n>A`, and
 *   unloads with `M620 S255` / `T255`.
 *
 * Counting rules:
 * - A change is counted when the selected filament actually changes; the first selection
 *   is the initial load and is not a change. Repeated selections of the loaded filament
 *   (the `M620 S<n>A` + `T<n>` + `M621 S<n>A` of one Bambu change) count once.
 * - Ids of 255 and above are no-ops (the unload / "no tool" ids above) and are ignored,
 *   as is everything after a `;` comment marker on a code line.
 * - Per layer: a layer's list holds the filaments selected while it is current, in
 *   selection order, each once. A layer starts at a `; CHANGE_LAYER` marker and is seeded
 *   with the filament loaded at that point (it prints the layer unless changed away).
 *   G-code without any layer marker yields a single layer with every selection.
 * - [Result.primeTowerMm3] sums the E deltas inside prime-tower blocks (net: retractions
 *   subtract, as the same filament is re-primed) and converts filament length to volume
 *   assuming 1.75 mm filament. Absolute E (`M82`, the default assumed here) and relative
 *   E (`M83`) are both tracked, and `G92 E<x>` resets the absolute reference.
 * - [Result.flushMm3] sums the E deltas inside flush blocks the same way.
 */
object GcodeToolChanges {

    data class Result(
        val changes: Int,
        val perLayer: List<List<Int>>,
        val primeTowerMm3: Double,
        val flushMm3: Double = 0.0,
    )

    /** Filament cross-section for the assumed 1.75 mm diameter, in mm2. */
    private const val FILAMENT_AREA_MM2 = PI * 0.875 * 0.875

    /** Ids at or above this are unload / no-tool sentinels (255, 1000, ...), never a filament. */
    private const val NO_OP_ID = 255

    private val m620 = Regex("""^M620\s+S(\d+)""")
    private val m1020 = Regex("""^M1020\s+S(\d+)""")
    private val toolWord = Regex("""^(?:M135\s+|M108\s+)?T(\d+)$""")
    private val manualChange = Regex("""MANUAL_TOOL_CHANGE\s+T(\d+)""")
    private val eParam = Regex("""(?:^|\s)E(-?\d+(?:\.\d+)?)""")

    fun parse(lines: Sequence<String>): Result {
        var changes = 0
        var current: Int? = null
        val layers = mutableListOf<MutableList<Int>>()
        val selectionOrder = mutableListOf<Int>()
        var inPrimeTower = false
        var relativeE = false
        var lastE: Double? = null
        var primeTowerMm = 0.0
        var inFlush = false
        var flushMm = 0.0

        fun select(id: Int) {
            if (id >= NO_OP_ID) return
            if (current != id) {
                if (current != null) changes++
                current = id
            }
            if (id !in selectionOrder) selectionOrder += id
            val layer = layers.lastOrNull() ?: return
            if (id !in layer) layer += id
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith(";")) {
                val comment = line.substring(1).trim()
                when {
                    comment == "CHANGE_LAYER" -> {
                        val layer = mutableListOf<Int>()
                        current?.let { layer += it }
                        layers += layer
                    }
                    comment.startsWith("FEATURE:") || comment.startsWith("TYPE:") ->
                        inPrimeTower = comment.substringAfter(':').trim() == "Prime tower"
                    comment == "WIPE_TOWER_START" -> inPrimeTower = true
                    comment == "WIPE_TOWER_END" -> inPrimeTower = false
                    comment == "FLUSH_START" -> inFlush = true
                    comment == "FLUSH_END" -> inFlush = false
                    else -> manualChange.find(comment)?.let { select(it.groupValues[1].toInt()) }
                }
                continue
            }
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) continue
            val word = code.substringBefore(' ')
            when {
                word == "M620" -> m620.find(code)?.let { select(it.groupValues[1].toInt()) }
                word == "M1020" -> m1020.find(code)?.let { select(it.groupValues[1].toInt()) }
                word == "M82" -> relativeE = false
                word == "M83" -> relativeE = true
                word == "G92" -> eParam.find(code)?.let { lastE = it.groupValues[1].toDouble() }
                word == "G0" || word == "G1" -> {
                    val e = eParam.find(code)?.groupValues?.get(1)?.toDouble() ?: continue
                    val delta = if (relativeE) e else {
                        val prev = lastE
                        lastE = e
                        if (prev == null) 0.0 else e - prev
                    }
                    if (inPrimeTower) primeTowerMm += delta
                    if (inFlush) flushMm += delta
                }
                else -> toolWord.matchEntire(word)?.let { select(it.groupValues[1].toInt()) }
            }
        }
        val perLayer = if (layers.isEmpty() && selectionOrder.isNotEmpty()) listOf(selectionOrder) else layers
        return Result(changes, perLayer, primeTowerMm * FILAMENT_AREA_MM2, flushMm * FILAMENT_AREA_MM2)
    }
}
