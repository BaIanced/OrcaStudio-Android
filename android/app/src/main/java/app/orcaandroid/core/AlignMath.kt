package app.orcaandroid.core

/** A footprint on the plate (world mm; y grows towards the back). */
data class Footprint(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val centerX get() = (minX + maxX) / 2
    val centerY get() = (minY + maxY) / 2

    companion object {
        fun of(i: Instance) = Footprint(i.min.x, i.min.y, i.min.x + i.size.x, i.min.y + i.size.y)
        fun union(items: List<Footprint>) =
            Footprint(items.minOf { it.minX }, items.minOf { it.minY }, items.maxOf { it.maxX }, items.maxOf { it.maxY })
    }
}

enum class AlignKind { LEFT, CENTER_X, RIGHT, FRONT, CENTER_Y, BACK }

/** Plain maths for the Prepare "Center" and "Align" actions (overlap between objects is allowed). */
object AlignMath {
    /** One shift for the whole group so its footprint centre lands on ([cx], [cy]); the group keeps its shape. */
    fun center(items: List<Footprint>, cx: Float, cy: Float): Pair<Float, Float> {
        val box = Footprint.union(items)
        return (cx - box.centerX) to (cy - box.centerY)
    }

    /** Per-item shifts that line every item up with the group's left / centre / right / front / middle / back edge. */
    fun align(items: List<Footprint>, kind: AlignKind): List<Pair<Float, Float>> {
        val g = Footprint.union(items)
        return items.map { f ->
            when (kind) {
                AlignKind.LEFT -> (g.minX - f.minX) to 0f
                AlignKind.CENTER_X -> (g.centerX - f.centerX) to 0f
                AlignKind.RIGHT -> (g.maxX - f.maxX) to 0f
                AlignKind.FRONT -> 0f to (g.minY - f.minY)
                AlignKind.CENTER_Y -> 0f to (g.centerY - f.centerY)
                AlignKind.BACK -> 0f to (g.maxY - f.maxY)
            }
        }
    }
}
