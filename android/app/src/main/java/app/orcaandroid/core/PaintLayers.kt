package app.orcaandroid.core

import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Per-layer filament ids of a colour-painted plate before slicing, the input [ColourChangeEstimate.estimate] needs.
 * Pure JVM: reads the paint mesh the engine already writes for the viewport (`OrcaEngine::write_paint_mesh`), so no
 * extra native call.
 *
 * Paint mesh record (`OrcaTools.cpp:161`): 3 vertices x 8 floats `x, y, z, nx, ny, nz, objCode, typeCode` in world mm,
 * `objCode = object * INSTANCE_ID_STRIDE + instance`, `typeCode = 10 + filament` (0-based) for colour paint
 * (`OrcaTools.cpp:220`); codes below 10 are fuzzy skin / support / seam paint and are skipped.
 *
 * Approximations (an estimate, not the slicer's segmentation): uniform layer height (no variable layer profile, no
 * per-range extruder), and an object's base filament counts on every layer of its height, since unpainted interior
 * prints with it even under a fully painted surface — except a copy's top / bottom layer when the flat face there
 * is painted over its whole area (see [forPlate]).
 */
object PaintLayers {

    const val FLOATS_PER_TRIANGLE = 24
    const val COLOR_CODE = 10

    /** |normal z| above this = a top / bottom face that gets the paint depth; below = a side wall. */
    const val FLAT_NZ = 0.05f

    /**
     * One filament (0-based) printed somewhere between [zMin] and [zMax] (world mm). [areaXY] is the source
     * triangle's XY-projected area (mm2), 0 for spans built by hand or synthesized in [forPlate]; [forPlate]
     * uses it to tell a fully painted flat face from a partially painted one.
     */
    data class Span(val filament: Int, val zMin: Float, val zMax: Float, val areaXY: Double = 0.0)

    /**
     * Colour-painted triangles as spans, for the copies [keep] accepts (object index, instance index). [adjust] may
     * reshape each span from its copy and the first vertex's normal z ([forPlate] adds the paint depth with it).
     */
    fun spans(
        paint: FloatBuffer,
        adjust: ((obj: Int, instance: Int, nz: Float, span: Span) -> Span)? = null,
        keep: (obj: Int, instance: Int) -> Boolean,
    ): List<Span> {
        val out = ArrayList<Span>()
        val triangles = paint.limit() / FLOATS_PER_TRIANGLE
        for (t in 0 until triangles) {
            val base = t * FLOATS_PER_TRIANGLE
            val filament = paint.get(base + 7).toInt() - COLOR_CODE
            if (filament < 0) continue
            val code = paint.get(base + 6).toInt()
            val obj = code / INSTANCE_STRIDE
            val instance = code % INSTANCE_STRIDE
            if (!keep(obj, instance)) continue
            val z0 = paint.get(base + 2)
            val z1 = paint.get(base + 10)
            val z2 = paint.get(base + 18)
            val areaXY = abs(
                (paint.get(base + 8) - paint.get(base + 0)) * (paint.get(base + 17) - paint.get(base + 1)) -
                    (paint.get(base + 16) - paint.get(base + 0)) * (paint.get(base + 9) - paint.get(base + 1)),
            ) / 2.0
            val span = Span(filament, minOf(z0, z1, z2), maxOf(z0, z1, z2), areaXY)
            out += adjust?.invoke(obj, instance, paint.get(base + 5), span) ?: span
        }
        return out
    }

    /**
     * Layers the colour of an up-facing ([top]) or down-facing face reaches into the object: its `mmu_top_paint_layers` /
     * `mmu_bottom_paint_layers` (Object mode "Paint depth"), where 0 = the shell layers ([shells], the object's own
     * `top_shell_layers` / `bottom_shell_layers` first) and -1 = all the way through ([Int.MAX_VALUE]).
     */
    fun depthLayers(settings: Map<String, String>, top: Boolean, shells: Int): Int {
        val side = if (top) "top" else "bottom"
        val depth = settings["mmu_${side}_paint_layers"]?.toIntOrNull() ?: 0
        return when {
            depth < 0 -> Int.MAX_VALUE
            depth > 0 -> depth
            else -> settings["${side}_shell_layers"]?.toIntOrNull() ?: shells
        }
    }

    /**
     * Bins [spans] into layers: `result[i]` = the distinct filaments on layer i, sorted. Layer 0 is [firstLayer] high,
     * every later layer [layerHeight]; the count reaches the highest span. A span is on every layer whose mid height
     * (the slicer's cut plane) it contains, endpoints included, as in the slicer's facet projection
     * (`MultiMaterialSegmentation.cpp:2119-2123`); a span crossing no mid (a flat top, a sliver) goes on the layer
     * holding its middle. The edge formulas carry the same 1e-4-of-a-layer epsilon [layerOf] has: without it,
     * Float rounding puts an edge that lands exactly on a mid (z = 1.3 or 1.5 at 0.2 mm layers) a layer off.
     */
    fun layers(spans: List<Span>, firstLayer: Float, layerHeight: Float): List<List<Int>> {
        if (spans.isEmpty() || firstLayer <= 0f || layerHeight <= 0f) return emptyList()
        fun layerOf(z: Float): Int = if (z <= firstLayer) 0 else ceil((z - firstLayer) / layerHeight - 1e-4f).toInt()
        val count = layerOf(spans.maxOf { it.zMax }) + 1
        val sets = List(count) { sortedSetOf<Int>() }
        for (s in spans) {
            val lo = if (s.zMin <= firstLayer / 2) 0 else maxOf(1, ceil((s.zMin - firstLayer) / layerHeight + 0.5f - 1e-4f).toInt())
            val hi = when {
                s.zMax < firstLayer / 2 -> -1
                s.zMax < firstLayer + layerHeight / 2 -> 0
                else -> floor((s.zMax - firstLayer) / layerHeight + 0.5f + 1e-4f).toInt()
            }.coerceAtMost(count - 1)
            if (lo <= hi) for (i in lo..hi) sets[i] += s.filament
            else sets[layerOf((s.zMin + s.zMax) / 2).coerceIn(0, count - 1)] += s.filament
        }
        return sets.map { it.toList() }
    }

    /**
     * Per-layer filament ids of [plate]: the colour paint of its copies plus, per copy, the object's base filament
     * (`extruder`, 1-based in the settings, 1 when unset) over the copy's whole height. [SceneObject.instances]'s
     * `min` / `size` are the world bounding box (`OrcaScene.cpp:494`). Parts with their own filament count as the base.
     *
     * Paint depth: a face pointing up (normal z > [FLAT_NZ]) carries its colour [depthLayers] layers down, one pointing
     * down that many up, within the copy's height, as the slicer's segmentation does (r367 WSA G-code: "Top 8 layers"
     * on a cube's top face put the colour on its top 8 layers). [topShells] / [bottomShells] are the process's shell
     * layers; 0 adds no depth. Side faces keep their own height.
     *
     * End layers: the slicer appends a flat top / bottom face's projection un-inset to its own layer and only
     * insets the projections it sends to the layers below / above (`MultiMaterialSegmentation.cpp:1439-1445`,
     * `:1459-1465`), so a face that is painted over its whole area takes its layer alone: the base filament has
     * no region left there (a layer's filaments are collected from the regions that actually extrude,
     * `ToolOrdering.cpp:767`). A copy whose flat face at its very top / bottom is painted over at least 99 % of
     * its XY bounding-box area therefore loses the base filament on that one layer; a partially painted face
     * keeps it. Coverage is the painted triangles' XY area ([Span.areaXY]) against the copy's bounding box,
     * which is exact for a rectangular face and conservative (no trim) for a round or rotated one.
     */
    fun forPlate(
        paint: FloatBuffer, objects: List<SceneObject>, plate: Int, firstLayer: Float, layerHeight: Float,
        topShells: Int = 0, bottomShells: Int = 0,
    ): List<List<Int>> {
        val topArea = HashMap<Pair<Int, Int>, Double>()
        val bottomArea = HashMap<Pair<Int, Int>, Double>()
        val deepen = { o: Int, i: Int, nz: Float, s: Span ->
            val obj = objects[o]
            val inst = obj.instances[i]
            val bottom = inst.min.z
            val top = bottom + inst.size.z
            if (abs(s.zMax - s.zMin) <= 0.01f) {
                if (nz > FLAT_NZ && abs(s.zMax - top) <= 0.01f) topArea[o to i] = (topArea[o to i] ?: 0.0) + s.areaXY
                if (nz < -FLAT_NZ && abs(s.zMin - bottom) <= 0.01f) bottomArea[o to i] = (bottomArea[o to i] ?: 0.0) + s.areaXY
            }
            fun reach(layers: Int) = if (layers == Int.MAX_VALUE) top - bottom else layers * layerHeight
            when {
                nz > FLAT_NZ -> s.copy(zMin = maxOf(bottom, s.zMin - reach(depthLayers(obj.settings, true, topShells))))
                nz < -FLAT_NZ -> s.copy(zMax = minOf(top, s.zMax + reach(depthLayers(obj.settings, false, bottomShells))))
                else -> s
            }
        }
        val spans = spans(paint, deepen) { o, i -> objects.getOrNull(o)?.instances?.getOrNull(i)?.plate == plate }.toMutableList()
        // The first / last layer whose mid a base span over [zMin, zMax] contains, and that layer's mid: the same
        // binning `layers` applies, used here to trim a base span by whole layers.
        fun firstContained(zMin: Float) = if (zMin <= firstLayer / 2) 0 else maxOf(1, ceil((zMin - firstLayer) / layerHeight + 0.5f - 1e-4f).toInt())
        fun lastContained(zMax: Float) = when {
            zMax < firstLayer / 2 -> -1
            zMax < firstLayer + layerHeight / 2 -> 0
            else -> floor((zMax - firstLayer) / layerHeight + 0.5f + 1e-4f).toInt()
        }
        fun mid(layer: Int) = if (layer == 0) firstLayer / 2 else firstLayer + (layer - 0.5f) * layerHeight
        for (o in objects.indices) {
            val obj = objects[o]
            val base = ((obj.settings["extruder"]?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
            for ((i, inst) in obj.instances.withIndex()) {
                if (inst.plate != plate) continue
                val zMin = inst.min.z
                val zMax = zMin + inst.size.z
                val bboxArea = inst.size.x.toDouble() * inst.size.y.toDouble()
                val topFull = bboxArea > 0 && (topArea[o to i] ?: 0.0) >= 0.99 * bboxArea
                val bottomFull = bboxArea > 0 && (bottomArea[o to i] ?: 0.0) >= 0.99 * bboxArea
                val lo0 = firstContained(zMin)
                val hi0 = lastContained(zMax)
                when {
                    lo0 > hi0 -> spans += Span(base, zMin, zMax) // crosses no mid: leave the fallback in `layers` alone
                    else -> {
                        val lo = lo0 + if (bottomFull) 1 else 0
                        val hi = hi0 - if (topFull) 1 else 0
                        if (lo <= hi) spans += if (lo == lo0 && hi == hi0) Span(base, zMin, zMax) else Span(base, mid(lo), mid(hi))
                        // lo > hi: the fully painted end face(s) take every layer the base had; no base span.
                    }
                }
            }
        }
        return layers(spans, firstLayer, layerHeight)
    }

    /** Mirrors `INSTANCE_ID_STRIDE` (`OrcaEngine.hpp:48`, `PlateRenderer.kt:12`); kept here so the JVM tests need no render class. */
    private const val INSTANCE_STRIDE = 4096
}
