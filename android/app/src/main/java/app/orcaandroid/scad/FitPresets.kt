package app.orcaandroid.scad

import kotlin.math.cos

/**
 * "Hole for..." presets of the shape builder: the diameter (and depth) a Hole cylinder needs for a screw, nut, magnet or
 * heat-set insert. Pure data and functions; the builder applies the result to an ordinary cylinder.
 * All numbers are mm before the [Fit] offset.
 */
object FitPresets {
    /** Print tolerance added to the diameter (to across-flats for a nut pocket); not used by [Kind.TAPPING] and [Kind.HEAT_SET]. */
    enum class Fit(val offset: Double) { TIGHT(0.0), NORMAL(0.2), LOOSE(0.4) }

    enum class Kind(val usesFit: Boolean) {
        /** Clearance hole the screw slides through. */
        THROUGH(true),
        /** Pilot hole the screw cuts its own thread into. */
        TAPPING(false),
        /** Six-sided pocket for a nut. */
        NUT(true),
        /** Through hole plus a 90 degree cone for the head. */
        COUNTERSUNK(true),
        /** Hole for a heat-set threaded insert (differs by maker). */
        HEAT_SET(false),
        /** Round pocket for a disc magnet. */
        MAGNET(true),
    }

    /** One screw size's row of the table. */
    data class Screw(val size: String, val through: Double, val tapping: Double, val nutFlats: Double, val nutDepth: Double, val head: Double)

    val SCREWS = listOf(
        Screw("M2", 2.4, 1.6, 4.0, 1.6, 4.0),
        Screw("M2.5", 2.9, 2.05, 5.0, 2.0, 5.0),
        Screw("M3", 3.4, 2.5, 5.5, 2.4, 6.0),
        Screw("M4", 4.5, 3.3, 7.0, 3.2, 8.0),
        Screw("M5", 5.5, 4.2, 8.0, 4.7, 10.0),
        Screw("M6", 6.6, 5.0, 10.0, 5.2, 12.0),
        Screw("M8", 9.0, 6.8, 13.0, 6.8, 16.0),
    )

    /** Heat-set insert holes, M3 / M4 / M5 (maker dependent: the builder tells the user to check the insert's data sheet). */
    val HEAT_SET = mapOf("M3" to 4.0, "M4" to 5.6, "M5" to 6.4)

    /** Disc magnets, "diameter x thickness": hole diameter and depth. */
    data class Magnet(val label: String, val diameter: Double, val depth: Double)

    val MAGNETS = listOf(Magnet("6 x 2", 6.0, 2.0), Magnet("8 x 3", 8.0, 3.0))

    /** The cone of a countersunk hole: wide end [head] across, narrow end [topRatio] x [head] across, [height] tall. */
    data class Cone(val head: Double, val height: Double, val topRatio: Double)

    /** What to set on the cylinder: [diameter] (a hexagon's is corner to corner), [depth] when the preset has one, [sides] (0 = round), [cone] to add. */
    data class Spec(val diameter: Double, val depth: Double?, val sides: Int, val cone: Cone?)

    /** Size names offered for [kind]. */
    fun sizes(kind: Kind): List<String> = when (kind) {
        Kind.HEAT_SET -> HEAT_SET.keys.toList()
        Kind.MAGNET -> MAGNETS.map { it.label }
        else -> SCREWS.map { it.size }
    }

    /** Circumradius of a regular hexagon with [acrossFlats] between opposite flats: af / cos 30 deg / 2. */
    fun hexCircumradius(acrossFlats: Double): Double = acrossFlats / cos(Math.toRadians(30.0)) / 2

    /** The settings for [size] of [kind] with [fit], or null for an unknown size. */
    fun spec(kind: Kind, size: String, fit: Fit): Spec? {
        val add = if (kind.usesFit) fit.offset else 0.0
        if (kind == Kind.HEAT_SET) return HEAT_SET[size]?.let { Spec(it, null, 0, null) }
        if (kind == Kind.MAGNET) return MAGNETS.firstOrNull { it.label == size }?.let { Spec(it.diameter + add, it.depth, 0, null) }
        val r = SCREWS.firstOrNull { it.size == size } ?: return null
        return when (kind) {
            Kind.THROUGH -> Spec(r.through + add, null, 0, null)
            Kind.TAPPING -> Spec(r.tapping, null, 0, null)
            Kind.NUT -> Spec(2 * hexCircumradius(r.nutFlats + add), r.nutDepth, 6, null)
            Kind.COUNTERSUNK -> {
                // 90 degrees: the radius grows as much as the cone is tall.
                val hole = r.through + add
                val head = r.head + add
                Spec(hole, null, 0, Cone(head, (head - hole) / 2, hole / head))
            }
            else -> null
        }
    }
}
