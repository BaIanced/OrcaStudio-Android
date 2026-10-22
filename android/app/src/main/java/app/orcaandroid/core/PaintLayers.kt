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

    /** One filament (0-based) printed somewhere between [zMin] and [zMax] (world mm). */
    data class Span(val filament: Int, val zMin: Float, val zMax: Float)

    /** Colour-painted triangles as spans, for the copies [keep] accepts (object index, instance index). */
    fun spans(paint: FloatBuffer, keep: (obj: Int, instance: Int) -> Boolean): List<Span> {
        val out = ArrayList<Span>()
        val triangles = paint.limit() / FLOATS_PER_TRIANGLE
        for (t in 0 until triangles) {
            val base = t * FLOATS_PER_TRIANGLE
            val filament = paint.get(base + 7).toInt() - COLOR_CODE
            if (filament < 0) continue
            val code = paint.get(base + 6).toInt()
            if (!keep(code / INSTANCE_STRIDE, code % INSTANCE_STRIDE)) continue
            val z0 = paint.get(base + 2)
            val z1 = paint.get(base + 10)
            val z2 = paint.get(base + 18)
            out += Span(filament, minOf(z0, z1, z2), maxOf(z0, z1, z2))
        }
        return out
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

    /** Mirrors `INSTANCE_ID_STRIDE` (`OrcaEngine.hpp:48`, `PlateRenderer.kt:12`); kept here so the JVM tests need no render class. */
    private const val INSTANCE_STRIDE = 4096
}
