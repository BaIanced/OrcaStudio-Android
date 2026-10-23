package app.orcaandroid.core

import java.nio.FloatBuffer
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
 * prints with it even under a fully painted surface.
 */
object PaintLayers {

    const val FLOATS_PER_TRIANGLE = 24
    const val COLOR_CODE = 10

    /** |normal z| above this = a top / bottom face that gets the paint depth; below = a side wall. */
    const val FLAT_NZ = 0.05f

    /** One filament (0-based) printed somewhere between [zMin] and [zMax] (world mm). */
    data class Span(val filament: Int, val zMin: Float, val zMax: Float)

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
            val span = Span(filament, minOf(z0, z1, z2), maxOf(z0, z1, z2))
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
     * (the slicer's cut plane) it contains; a span crossing no mid (a flat top, a sliver) goes on the layer holding
     * its middle.
     */
    fun layers(spans: List<Span>, firstLayer: Float, layerHeight: Float): List<List<Int>> {
        if (spans.isEmpty() || firstLayer <= 0f || layerHeight <= 0f) return emptyList()
        fun layerOf(z: Float): Int = if (z <= firstLayer) 0 else ceil((z - firstLayer) / layerHeight - 1e-4f).toInt()
        val count = layerOf(spans.maxOf { it.zMax }) + 1
        val sets = List(count) { sortedSetOf<Int>() }
        for (s in spans) {
            val lo = if (s.zMin <= firstLayer / 2) 0 else maxOf(1, ceil((s.zMin - firstLayer) / layerHeight + 0.5f).toInt())
            val hi = when {
                s.zMax < firstLayer / 2 -> -1
                s.zMax < firstLayer + layerHeight / 2 -> 0
                else -> floor((s.zMax - firstLayer) / layerHeight + 0.5f).toInt()
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
     */
    fun forPlate(
        paint: FloatBuffer, objects: List<SceneObject>, plate: Int, firstLayer: Float, layerHeight: Float,
        topShells: Int = 0, bottomShells: Int = 0,
    ): List<List<Int>> {
        val deepen = { o: Int, i: Int, nz: Float, s: Span ->
            val obj = objects[o]
            val inst = obj.instances[i]
            val bottom = inst.min.z
            val top = bottom + inst.size.z
            fun reach(layers: Int) = if (layers == Int.MAX_VALUE) top - bottom else layers * layerHeight
            when {
                nz > FLAT_NZ -> s.copy(zMin = maxOf(bottom, s.zMin - reach(depthLayers(obj.settings, true, topShells))))
                nz < -FLAT_NZ -> s.copy(zMax = minOf(top, s.zMax + reach(depthLayers(obj.settings, false, bottomShells))))
                else -> s
            }
        }
        val spans = spans(paint, deepen) { o, i -> objects.getOrNull(o)?.instances?.getOrNull(i)?.plate == plate }.toMutableList()
        for (o in objects) {
            val base = ((o.settings["extruder"]?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
            for (inst in o.instances) if (inst.plate == plate) spans += Span(base, inst.min.z, inst.min.z + inst.size.z)
        }
        return layers(spans, firstLayer, layerHeight)
    }

    /** Mirrors `INSTANCE_ID_STRIDE` (`OrcaEngine.hpp:48`, `PlateRenderer.kt:12`); kept here so the JVM tests need no render class. */
    private const val INSTANCE_STRIDE = 4096
}
