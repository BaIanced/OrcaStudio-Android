package app.orcaandroid.scad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** A point of a sketch (mm). */
data class P2(val x: Double, val y: Double) {
    operator fun plus(o: P2) = P2(x + o.x, y + o.y)
    operator fun minus(o: P2) = P2(x - o.x, y - o.y)
    operator fun times(k: Double) = P2(x * k, y * k)
    fun dist(o: P2) = hypot(x - o.x, y - o.y)
}

/**
 * A closed outline: corners in order, and per side (corner i to i + 1) a bulge that bends it into an
 * arc. The bulge is tan(arc angle / 4): 0 = straight, 1 = half circle; positive bends to the right
 * of the side's direction, i.e. outwards on an outline drawn counter-clockwise. A circle is two
 * corners with bulge 1 on both sides. A hole outline is cut from the outlines it overlaps.
 */
data class Loop(val pts: List<P2>, val bulge: List<Double> = List(pts.size) { 0.0 }, val hole: Boolean = false) {

    fun bulgeAt(i: Int) = bulge.getOrElse(i) { 0.0 }

    /** The point halfway along side [i] (on the arc when bent). */
    fun sideMid(i: Int): P2 {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        val m = (a + b) * 0.5
        val l = a.dist(b)
        if (l == 0.0) return m
        return m + right(a, b) * (bulgeAt(i) * l / 2)
    }

    /** The outline as straight segments, arcs split so a side is at most [fs] mm or [fa] degrees. */
    fun polygon(fs: Double = 0.4, fa: Double = 4.0): List<P2> {
        val out = ArrayList<P2>()
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            out += a
            val k = bulgeAt(i)
            val l = a.dist(b)
            if (k == 0.0 || l == 0.0) continue
            val s = k * l / 2                           // sagitta, signed
            val r = (l * l / 4 + s * s) / (2 * s)       // radius, signed like s
            val c = (a + b) * 0.5 + right(a, b) * (s - r)
            val sweep = sign(k) * 4 * atan(abs(k))
            val a0 = atan2(a.y - c.y, a.x - c.x)
            val n = max(2, min(ceil(Math.toDegrees(abs(sweep)) / fa), ceil(abs(sweep * r) / fs)).toInt())
            for (j in 1 until n) {
                val t = a0 + sweep * j / n
                out += P2(c.x + abs(r) * cos(t), c.y + abs(r) * sin(t))
            }
        }
        return out
    }

    fun json(): JSONObject = JSONObject()
        .put("pts", JSONArray().apply { pts.forEach { put(JSONArray().put(it.x).put(it.y)) } })
        .apply {
            if (bulge.any { it != 0.0 }) put("bulge", JSONArray().apply { pts.indices.forEach { put(bulgeAt(it)) } })
            if (hole) put("hole", true)
        }

    companion object {
        /** Unit normal to the right of the direction a -> b. */
        fun right(a: P2, b: P2): P2 {
            val l = a.dist(b)
            return if (l == 0.0) P2(0.0, 0.0) else P2((b.y - a.y) / l, -(b.x - a.x) / l)
        }

        fun rect(a: P2, b: P2) = Loop(listOf(P2(min(a.x, b.x), min(a.y, b.y)), P2(max(a.x, b.x), min(a.y, b.y)),
            P2(max(a.x, b.x), max(a.y, b.y)), P2(min(a.x, b.x), max(a.y, b.y))))

        fun circle(c: P2, r: Double) = Loop(listOf(P2(c.x - r, c.y), P2(c.x + r, c.y)), listOf(1.0, 1.0))

        fun of(o: JSONObject): Loop? {
            val a = o.optJSONArray("pts") ?: return null
            val pts = (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let { p -> P2(p.optDouble(0), p.optDouble(1)) } }
            if (pts.size < 2) return null
            val b = o.optJSONArray("bulge")
            return Loop(pts, pts.indices.map { b?.optDouble(it, 0.0)?.takeIf { v -> v.isFinite() } ?: 0.0 }, o.optBoolean("hole"))
        }
    }
}

/** A 2D drawing (outlines in mm) that a sketch shape extrudes or revolves. */
data class Sketch(val loops: List<Loop> = emptyList()) {

    fun json(): JSONObject = JSONObject().put("loops", JSONArray().apply { loops.forEach { put(it.json()) } })

    /** Bounds of every outline (arcs included); null when empty. */
    fun bounds(): Pair<P2, P2>? {
        val all = loops.flatMap { it.polygon() }
        if (all.isEmpty()) return null
        return P2(all.minOf { it.x }, all.minOf { it.y }) to P2(all.maxOf { it.x }, all.maxOf { it.y })
    }

    /**
     * OpenSCAD 2D code for the drawing moved by [shift]: solid outlines joined, hole outlines cut.
     * Outlines are written as polygons, arcs already split.
     */
    fun toScad(shift: P2 = P2(0.0, 0.0)): String {
        fun poly(l: Loop) = "polygon(" + l.polygon().joinToString(", ", "[", "]") { "[${ShapeDesign.n(it.x + shift.x)}, ${ShapeDesign.n(it.y + shift.y)}]" } + ");"
        val solids = loops.filter { !it.hole && it.pts.size >= 2 }.map(::poly)
        val holes = loops.filter { it.hole && it.pts.size >= 2 }.map(::poly)
        if (solids.isEmpty()) return "square(0.01);"
        val joined = if (solids.size == 1) solids[0] else "union() { ${solids.joinToString(" ")} }"
        return if (holes.isEmpty()) joined else "difference() { $joined ${holes.joinToString(" ")} }"
    }

    companion object {
        /** The drawing a new sketch shape starts with: a 20 x 20 mm square. */
        val START = Sketch(listOf(Loop.rect(P2(-10.0, -10.0), P2(10.0, 10.0))))

        fun of(o: JSONObject?): Sketch? = o?.optJSONArray("loops")?.let { a -> Sketch((0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Loop::of) }) }
    }
}
