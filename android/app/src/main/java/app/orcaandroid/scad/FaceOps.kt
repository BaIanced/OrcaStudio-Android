package app.orcaandroid.scad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A push / pull on one flat region of a MESH shape's model: the region's outline extruded [d] mm along its outward
 * [normal] (d > 0 adds a prism on the face, d < 0 cuts one into it). The frame is in the model FILE's own coordinates:
 * [outline] is the outer loop then its holes, in (u, v) plane coordinates about [origin], v = normal x u.
 */
data class FaceOp(val origin: D3, val normal: D3, val u: D3, val outline: List<List<P2>>, val d: Double) {
    val v get() = cross(normal, u)

    /** Plane point (a, b) lifted [t] mm along the normal, in file coordinates. */
    fun at(p: P2, t: Double = 0.0) = D3(
        origin.x + p.x * u.x + p.y * v.x + t * normal.x,
        origin.y + p.x * u.y + p.y * v.y + t * normal.y,
        origin.z + p.x * u.z + p.y * v.z + t * normal.z,
    )

    fun json(): JSONObject = JSONObject().put("origin", origin.json()).put("normal", normal.json()).put("u", u.json()).put("d", d)
        .put("outline", JSONArray().apply { outline.forEach { l -> put(JSONArray().apply { l.forEach { put(it.x); put(it.y) } }) } })

    companion object {
        fun of(o: JSONObject): FaceOp = FaceOp(
            D3.of(o.optJSONArray("origin")), D3.of(o.optJSONArray("normal"), D3(0.0, 0.0, 1.0)), D3.of(o.optJSONArray("u"), D3(1.0, 0.0, 0.0)),
            o.optJSONArray("outline")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { l -> (0 until l.length() / 2).map { P2(l.optDouble(2 * it), l.optDouble(2 * it + 1)) } }
            } ?: emptyList(),
            o.optDouble("d", 0.0),
        )

        fun cross(a: D3, b: D3) = D3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
        fun dot(a: D3, b: D3) = a.x * b.x + a.y * b.y + a.z * b.z
        fun unit(a: D3): D3 = sqrt(dot(a, a)).let { l -> if (l < 1e-12) a else D3(a.x / l, a.y / l, a.z / l) }
    }
}

/** Face ops in OpenSCAD and in a mesh's bounds, and the flat region of a mesh under a point. */
object FaceOps {
    /** Overlap past the face so the prism never shares a plane with it (same idea as the holes' 0.02 growth). */
    private const val SEAM = 0.01

    /** [base] (a solid's code) with [ops] applied: pushes joined, then pulls cut. */
    fun scad(base: String, ops: List<FaceOp>): String {
        fun n(x: Double) = ShapeDesign.n(x)
        fun prism(o: FaceOp, from: Double, len: Double, grow: Boolean): String {
            val loops = o.outline.filter { it.size >= 3 }
            var i = 0
            val pts = loops.flatten().joinToString(", ") { "[${n(it.x)}, ${n(it.y)}]" }
            val paths = loops.joinToString(", ") { l -> l.indices.joinToString(", ", "[", "]") { (i++).toString() } }
            val u = o.u
            val v = o.v
            val m = o.normal
            val g = o.origin
            val f = "[[${n(u.x)}, ${n(v.x)}, ${n(m.x)}, ${n(g.x)}], [${n(u.y)}, ${n(v.y)}, ${n(m.y)}, ${n(g.y)}], " +
                "[${n(u.z)}, ${n(v.z)}, ${n(m.z)}, ${n(g.z)}], [0, 0, 0, 1]]"
            val poly = "polygon([$pts], [$paths]);"
            // A cut grows by the seam too, so its walls don't share planes with the sides of the region it cuts.
            return "multmatrix($f) translate([0, 0, ${n(from)}]) linear_extrude(${n(len)}) ${if (grow) "offset(delta = ${n(SEAM)}) " else ""}$poly"
        }
        val live = ops.filter { o -> o.d != 0.0 && o.outline.firstOrNull()?.let { it.size >= 3 } == true }
        val pushes = live.filter { it.d > 0 }.map { prism(it, -SEAM, it.d + SEAM, false) }
        val pulls = live.filter { it.d < 0 }.map { prism(it, it.d, -it.d + SEAM, true) }
        var code = base
        if (pushes.isNotEmpty()) code = "union() { $code ${pushes.joinToString(" ")} }"
        if (pulls.isNotEmpty()) code = "difference() { $code ${pulls.joinToString(" ")} }"
        return code
    }

    /** [file] bounds (min x / y / z, max x / y / z) grown to hold the pushes' prisms. Pulls never grow it. */
    fun bounds(file: List<Double>, ops: List<FaceOp>): List<Double> {
        val b = file.toMutableList()
        for (o in ops) if (o.d > 0) for (p in o.outline.firstOrNull().orEmpty()) for (t in doubleArrayOf(0.0, o.d)) {
            val q = o.at(p, t)
            for (i in 0..2) { b[i] = min(b[i], q[i]); b[i + 3] = max(b[i + 3], q[i]) }
        }
        return b
    }

    /**
     * The op whose cap [p] (file coordinates) lies on: on its plane moved by d, inside its outline. Dragging there
     * edits that op instead of stacking a new one on the cap. -1 when none.
     */
    fun capAt(ops: List<FaceOp>, p: D3, tol: Double = 0.05): Int = ops.indexOfLast { o ->
        val r = p - o.origin
        abs(FaceOp.dot(r, o.normal) - o.d) < tol && inside(o.outline, P2(FaceOp.dot(r, o.u), FaceOp.dot(r, o.v)))
    }

    /** Even-odd test over all loops (outer and holes). */
    fun inside(loops: List<List<P2>>, q: P2): Boolean {
        var odd = false
        for (l in loops) for (i in l.indices) {
            val a = l[i]
            val b = l[(i + 1) % l.size]
            if ((a.y > q.y) != (b.y > q.y) && q.x < a.x + (q.y - a.y) / (b.y - a.y) * (b.x - a.x)) odd = !odd
        }
        return odd
    }

    /**
     * The flat region of mesh [tris] (9 numbers per triangle, outward winding) under [hit] (file coordinates):
     * the triangle there flood-filled through neighbours that face the same way (within [angle] degrees) and lie on
     * the same plane (within [plane] mm), its boundary turned into loops (largest = outer). An op with d = 0, or null
     * when no triangle lies under [hit].
     */
    fun region(tris: FloatArray, hit: D3, angle: Double = 0.5, plane: Double = 0.01, tol: Double = 0.05): FaceOp? {
        val count = tris.size / 9
        fun vert(t: Int, k: Int) = D3(tris[t * 9 + k * 3].toDouble(), tris[t * 9 + k * 3 + 1].toDouble(), tris[t * 9 + k * 3 + 2].toDouble())
        val normals = arrayOfNulls<D3>(count)
        for (t in 0 until count) {
            val c = FaceOp.cross(vert(t, 1) - vert(t, 0), vert(t, 2) - vert(t, 0))
            if (FaceOp.dot(c, c) > 1e-18) normals[t] = FaceOp.unit(c)
        }
        // The seed: the triangle nearest to the point's plane that holds the point.
        var seed = -1
        var best = tol
        for (t in 0 until count) {
            val nt = normals[t] ?: continue
            val a = vert(t, 0)
            val dist = abs(FaceOp.dot(hit - a, nt))
            if (dist < best && holds(a, vert(t, 1), vert(t, 2), nt, hit, tol)) { best = dist; seed = t }
        }
        if (seed < 0) return null
        val n = normals[seed]!!
        val off = FaceOp.dot(vert(seed, 0), n)
        // Corners welded to 1e-4 mm, so neighbours are found by shared edges.
        val ids = HashMap<Triple<Long, Long, Long>, Int>()
        val pos = ArrayList<D3>()
        fun id(p: D3) = ids.getOrPut(Triple(Math.round(p.x * 1e4), Math.round(p.y * 1e4), Math.round(p.z * 1e4))) { pos.add(p); pos.size - 1 }
        val corner = Array(count) { t -> IntArray(3) { id(vert(t, it)) } }
        val byEdge = HashMap<Long, MutableList<Int>>()
        fun key(a: Int, b: Int) = min(a, b).toLong() shl 32 or max(a, b).toLong()
        for (t in 0 until count) if (normals[t] != null) for (k in 0..2) byEdge.getOrPut(key(corner[t][k], corner[t][(k + 1) % 3])) { ArrayList(2) }.add(t)
        val cosMax = cos(Math.toRadians(angle))
        fun flat(t: Int) = normals[t]?.let { FaceOp.dot(it, n) > cosMax && (0..2).all { k -> abs(FaceOp.dot(vert(t, k), n) - off) < plane } } == true
        val inRegion = BooleanArray(count)
        inRegion[seed] = true
        val stack = ArrayDeque(listOf(seed))
        while (stack.isNotEmpty()) {
            val t = stack.removeLast()
            for (k in 0..2) for (o in byEdge[key(corner[t][k], corner[t][(k + 1) % 3])].orEmpty())
                if (!inRegion[o] && flat(o)) { inRegion[o] = true; stack.add(o) }
        }
        // Boundary: region edges no other region triangle shares, kept in their triangle's direction.
        val next = HashMap<Int, MutableList<Int>>()
        var edges = 0
        for (t in 0 until count) if (inRegion[t]) for (k in 0..2) {
            val a = corner[t][k]
            val b = corner[t][(k + 1) % 3]
            if (byEdge[key(a, b)]!!.count { inRegion[it] } == 1) { next.getOrPut(a) { ArrayList(1) }.add(b); edges++ }
        }
        val loops3 = ArrayList<List<Int>>()
        while (edges > 0) {
            val start = next.entries.first { it.value.isNotEmpty() }.key
            val loop = ArrayList<Int>()
            var at = start
            do {
                loop.add(at)
                val outs = next[at] ?: break
                if (outs.isEmpty()) break
                at = outs.removeAt(outs.size - 1)
                edges--
            } while (at != start)
            next.entries.removeAll { it.value.isEmpty() }
            if (loop.size >= 3 && at == start) loops3.add(loop)
        }
        if (loops3.isEmpty()) return null
        // Frame: origin on the first loop, u along its first side.
        val origin = pos[loops3[0][0]]
        val e = pos[loops3[0][1]] - origin
        val u = FaceOp.unit(FaceOp.dot(e, n).let { k -> D3(e.x - n.x * k, e.y - n.y * k, e.z - n.z * k) })
        val v = FaceOp.cross(n, u)
        val flatLoops = loops3.map { l -> simplify(l.map { pos[it] - origin }.map { P2(FaceOp.dot(it, u), FaceOp.dot(it, v)) }) }
            .filter { it.size >= 3 }
            .sortedByDescending { abs(area(it)) }
        if (flatLoops.isEmpty()) return null
        return FaceOp(origin, n, u, flatLoops, 0.0)
    }

    /** [p] inside triangle a b c (normal [n]) to [tol] mm. */
    private fun holds(a: D3, b: D3, c: D3, n: D3, p: D3, tol: Double): Boolean {
        fun side(x: D3, y: D3): Double {
            val e = FaceOp.unit(y - x)
            return FaceOp.dot(FaceOp.cross(e, p - x), n)
        }
        return side(a, b) > -tol && side(b, c) > -tol && side(c, a) > -tol
    }

    /** Corners on a straight run (and repeats) dropped. */
    private fun simplify(l: List<P2>): List<P2> {
        var pts = l
        var changed = true
        while (changed && pts.size > 3) {
            changed = false
            for (i in pts.indices) {
                val a = pts[(i + pts.size - 1) % pts.size]
                val b = pts[i]
                val c = pts[(i + 1) % pts.size]
                val ab = b - a
                val bc = c - b
                val len = sqrt(ab.x * ab.x + ab.y * ab.y) * sqrt(bc.x * bc.x + bc.y * bc.y)
                if (b.dist(a) < 1e-6 || abs(ab.x * bc.y - ab.y * bc.x) <= 1e-5 * max(len, 1e-12) && ab.x * bc.x + ab.y * bc.y > 0) {
                    pts = pts.filterIndexed { k, _ -> k != i }
                    changed = true
                    break
                }
            }
        }
        return pts
    }

    fun area(p: List<P2>): Double = p.indices.sumOf { i -> val a = p[i]; val b = p[(i + 1) % p.size]; a.x * b.y - b.x * a.y } / 2
}
