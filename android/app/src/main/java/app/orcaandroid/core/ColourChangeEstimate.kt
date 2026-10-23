package app.orcaandroid.core

/**
 * Colour-change count and flush (purge) estimate for a multi-colour print, from the per-layer
 * filament usage alone. Pure JVM: no Android imports, no slicing, no prime tower geometry.
 *
 * The flush data mirrors the profile keys in `src-orca/src/libslic3r/PrintConfig.cpp`
 * (`flush_volumes_matrix`, `flush_multiplier`): the matrix is row-major from -> to, in mm3,
 * so `matrix[from][to]` is the volume purged when switching from filament `from` to `to`.
 */
object ColourChangeEstimate {

    /** mm3 per mm of `grab_length`, which the change G-code extrudes anyway and the purge subtracts (`Print.cpp:4716-4718`). */
    const val GRAB_MM3_PER_MM = 2.4

    /**
     * Smallest chute purge with a prime tower: any non-zero purge is raised to this (`GCode.cpp:153`, used at `:1267`).
     * Why a real A1 plate flushed 100 mm3 per change while its matrix said 97 x 0.3 (see [estimate]).
     */
    const val TOWER_MIN_PURGE_MM3 = 100.0

    /** One estimate: how many switches, how much was purged, and the purged grams per filament flushed *to*. */
    data class Estimate(
        val changes: Int,
        val flushMm3: Double,
        val flushGrams: Double,
        val perFilamentGrams: DoubleArray,
    )

    /**
     * Parses the flat `flush_volumes_matrix` values into rows of [filamentCount].
     * Multi-nozzle printers concatenate one n x n matrix per nozzle; only the first n x n
     * block is used. Returns null when [filamentCount] < 1 or fewer than n x n values are given.
     */
    fun parseFlushMatrix(values: List<Double>, filamentCount: Int): Array<DoubleArray>? {
        if (filamentCount < 1 || values.size < filamentCount * filamentCount) return null
        return Array(filamentCount) { from ->
            DoubleArray(filamentCount) { to -> values[from * filamentCount + to] }
        }
    }

    /**
     * Estimates the switches for [layers], where `layers[i]` holds the 0-based filament ids
     * printed on layer i (unordered, may repeat, may be empty).
     *
     * Order within a layer: the filament left loaded from the previous layer prints first when
     * it is used on this layer; after that the next filament is always the one with the smallest
     * flush from the current one (ties: lower id). A switch counts for every change of the loaded
     * filament, including on the first layer when [startFilament] names a different filament;
     * with [startFilament] null the first filament loaded is not a switch and purges nothing.
     * An empty layer (or one holding only out-of-range ids, which are ignored) changes nothing
     * and leaves the loaded filament in place.
     *
     * Purge per switch: `matrix[from][to] * multiplier - grabMm3` mm3, at least [minPurgeMm3] (pass
     * [TOWER_MIN_PURGE_MM3] with a prime tower) and never below 0. Source only floors a non-zero purge, but a real A1
     * plate (matrix 97 x 0.3 - grab 41.76 < 0) still purged 100 mm3 on every switch (r371, WSA), so the floor
     * applies to every switch here. The grams use the density of
     * the filament being flushed *to* (`mm3 / 1000 * g/cm3`). A filament with no density entry
     * contributes its mm3 but 0 g. Flushing into infill / objects is not modelled.
     */
    fun estimate(
        layers: List<List<Int>>,
        matrix: Array<DoubleArray>,
        multiplier: Double,
        densityGPerCm3: List<Double>,
        startFilament: Int?,
        grabMm3: Double = 0.0,
        minPurgeMm3: Double = 0.0,
    ): Estimate {
        val n = matrix.size
        val perFilament = DoubleArray(n)
        var changes = 0
        var flushMm3 = 0.0
        var current: Int? = startFilament?.takeIf { it in 0 until n }

        for (layer in layers) {
            val remaining = layer.filter { it in 0 until n }.distinct().toMutableSet()
            if (remaining.isEmpty()) continue
            val loaded = current
            if (loaded != null && loaded in remaining) {
                remaining.remove(loaded)
            } else if (loaded == null) {
                // Initial load: no switch, no purge; start from the lowest id.
                current = remaining.min()
                remaining.remove(current)
            }
            while (remaining.isNotEmpty()) {
                val from = current!!
                val to = remaining.minWith(compareBy({ matrix[from][it] }, { it }))
                val mm3 = maxOf(matrix[from][to] * multiplier - grabMm3, minPurgeMm3, 0.0)
                val grams = mm3 / 1000.0 * (densityGPerCm3.getOrNull(to) ?: 0.0)
                changes++
                flushMm3 += mm3
                perFilament[to] += grams
                current = to
                remaining.remove(to)
            }
        }
        return Estimate(changes, flushMm3, perFilament.sum(), perFilament)
    }
}
