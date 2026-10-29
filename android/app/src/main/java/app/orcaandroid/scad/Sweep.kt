package app.orcaandroid.scad

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A sketch profile swept along a path drawn from above: the profile's x runs sideways (to the left of the way the
 * path goes; outwards on a closed path), its y up from the bed, and the path passes through the profile's origin.
 * Corners are mitred, so the profile keeps its width round them. Drawn by the code prelude's `sweep` module.
 */
class Sweep private constructor(
    /** Solid and hole outlines of the profile: counter-clockwise, lifted so the lowest point is at 0. */
    val solids: List<List<P2>>,
    val holes: List<List<P2>>,
    /** Each path as its points and whether it closes on itself. */
    val paths: List<Pair<List<P2>, Boolean>>,
) {
    /** One step along a path: its point and the sideways direction, longer at a corner by the mitre. */
    data class Frame(val p: P2, val side: P2)

    /** The swept solid's bounds (min, max) in the drawing's frame. */
    val bounds: Pair<D3, D3> by lazy {
        var lo = D3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        var hi = D3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for ((path, closed) in paths) for (f in frames(path, closed)) for (q in solids.flatten()) {
            val x = f.p.x + f.side.x * q.x
            val y = f.p.y + f.side.y * q.x
            lo = D3(minOf(lo.x, x), minOf(lo.y, y), minOf(lo.z, q.y))
            hi = D3(maxOf(hi.x, x), maxOf(hi.y, y), maxOf(hi.z, q.y))
        }
        lo to hi
    }

    /** Box size of the sweep at 1:1. */
    val size: D3 get() = bounds.let { (lo, hi) -> D3(max(0.5, hi.x - lo.x), max(0.5, hi.y - lo.y), max(0.5, hi.z - lo.z)) }

    /**
     * The code: centred on x / y and standing on z = 0, scaled to [w] x [d] x [h] (code expressions). Holes are cut
     * from every solid; on an open path they reach a little past the ends so no skin is left on the end faces.
     */
    fun toScad(w: String, d: String, h: String): String {
        fun n(v: Double) = ShapeDesign.n(v)
        fun pts(p: List<P2>) = p.joinToString(", ", "[", "]") { "[${n(it.x)}, ${n(it.y)}]" }
        fun frames(f: List<Frame>) = f.joinToString(", ", "[", "]") { "[${n(it.p.x)}, ${n(it.p.y)}, ${n(it.side.x)}, ${n(it.side.y)}]" }
        fun all(loops: List<List<P2>>, extend: Double) = paths.flatMap { (path, closed) ->
            val f = frames(frames(path, closed, if (closed) 0.0 else extend))
            loops.map { "sweep(${pts(it)}, $f, $closed);" }
        }
        val solid = all(solids, 0.0).let { if (it.size == 1) it[0] else "union() { ${it.joinToString(" ")} }" }
        val cut = all(holes, 0.1)
        val body = if (cut.isEmpty()) solid else "difference() { $solid ${cut.joinToString(" ")} }"
        val (lo, hi) = bounds
        val s = size
        return "scale([$w / ${n(s.x)}, $d / ${n(s.y)}, $h / ${n(s.z)}]) translate([${n(-(lo.x + hi.x) / 2)}, ${n(-(lo.y + hi.y) / 2)}, 0]) $body"
    }

    companion object {
        /** The prelude module (faces wound like the loft's: the profile is its outline, the path its height). */
        val MODULE = listOf(
            "// Profile p ([sideways, up] points, counter-clockwise) swept along frames f ([x, y, side x, side y]: a path",
            "// point and its sideways direction, mitred at corners); closed joins the last frame back to the first.",
            "module sweep(p, f, closed) {",
            "    n = len(p);",
            "    m = len(f);",
            "    pts = [for (k = f) for (q = p) [k[0] + k[2] * q[0], k[1] + k[3] * q[0], q[1]]];",
            "    s = closed ? m : m - 1;",
            "    polyhedron(pts, concat(closed ? [] : [[for (j = [0:n - 1]) j], [for (j = [n - 1:-1:0]) (m - 1) * n + j]],",
            "        [for (i = [0:s - 1], j = [0:n - 1]) [i * n + j, (i + 1) % m * n + j, (i + 1) % m * n + (j + 1) % n, i * n + (j + 1) % n]]),",
            "        convexity = 10);",
            "}",
        )

        /** [profile] swept along every outline of [path] (open or closed), or null when either has nothing to use. */
        fun of(profile: Sketch, path: Sketch): Sweep? {
            // Repeated corners (and a repeated first one at the end) dropped, so no side or cap face is degenerate.
            fun clean(l: Loop): List<P2> {
                val p = l.polygon().fold(emptyList<P2>()) { acc, q -> if (acc.isNotEmpty() && acc.last().dist(q) < 1e-6) acc else acc + q }
                return if (p.size > 1 && p.first().dist(p.last()) < 1e-6) p.dropLast(1) else p
            }
            fun ccw(l: Loop) = clean(l).let { if (Sketch.area(it) < 0) it.reversed() else it }.takeIf { abs(Sketch.area(it)) > 1e-9 }
            val solid0 = profile.loops.filter { !it.hole }.mapNotNull(::ccw)
            val hole0 = profile.loops.filter { it.hole }.mapNotNull(::ccw)
            // Lifted so the lowest solid point is at 0 (holes move with them).
            val low = solid0.flatten().minOfOrNull { it.y } ?: return null
            fun lift(p: List<P2>) = p.map { P2(it.x, it.y - low) }
            val solids = solid0.map(::lift)
            val holes = hole0.map(::lift)
            val paths = path.loops.mapNotNull { l ->
                var p = l.polygon().fold(emptyList<P2>()) { acc, q -> if (acc.isNotEmpty() && acc.last().dist(q) < 1e-6) acc else acc + q }
                // An open path that ends where it starts is a closed one.
                val closed = !l.open || (p.size >= 4 && p.first().dist(p.last()) < 1e-6)
                if (closed && p.size > 1 && p.first().dist(p.last()) < 1e-6) p = p.dropLast(1)
                // Clockwise, so the profile's +x (left of the way it goes) points out of a closed path.
                if (closed && Sketch.area(p) > 0) p = p.reversed()
                when {
                    p.size < 2 || closed && (p.size < 3 || abs(Sketch.area(p)) < 1e-9) -> null
                    else -> p to closed
                }
            }
            if (solids.isEmpty() || paths.isEmpty()) return null
            return Sweep(solids, holes, paths)
        }

        /** The frames along [path]; an open path's ends are pushed out along it by [extend] mm. */
        fun frames(path: List<P2>, closed: Boolean, extend: Double = 0.0): List<Frame> {
            val n = path.size
            fun dir(a: P2, b: P2): P2 { val l = a.dist(b); return if (l == 0.0) P2(0.0, 0.0) else (b - a) * (1 / l) }
            fun left(d: P2) = P2(-d.y, d.x)
            return path.indices.map { i ->
                val inc = if (i > 0) dir(path[i - 1], path[i]) else if (closed) dir(path[n - 1], path[0]) else null
                val out = if (i < n - 1) dir(path[i], path[i + 1]) else if (closed) dir(path[n - 1], path[0]) else null
                val a = left(inc ?: out!!)
                val b = left(out ?: inc!!)
                val sum = a + b
                val len = hypot(sum.x, sum.y)
                // Mitre: the bisector, longer by 1 / cos(half the turn) (capped so a hairpin doesn't spike).
                val side = if (len < 1e-9) a else (sum * (1 / len)).let { m -> m * (1 / max(0.25, m.x * a.x + m.y * a.y)) }
                val p = when {
                    closed || extend == 0.0 -> path[i]
                    i == 0 -> path[i] - (out ?: inc!!) * extend
                    i == n - 1 -> path[i] + (inc ?: out!!) * extend
                    else -> path[i]
                }
                Frame(p, side)
            }
        }
    }
}
