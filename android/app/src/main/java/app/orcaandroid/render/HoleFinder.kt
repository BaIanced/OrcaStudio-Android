package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Round holes along X, Y or Z in the scene mesh (View > Dimensions, phase 2). Pure Kotlin on the uploaded triangle soup
 * (8 floats per vertex: position, normal, object id, type), no engine call.
 *
 * Per axis: the wall triangles (face normal across the axis) are welded and grouped over shared edges, split at creases
 * sharper than [CREASE_DEG] so a box's sides stay apart from a hole in it. Each group gets a least-squares circle in the
 * plane across the axis; a group is a hole when the circle fits, the wall wraps most of the way round and its normals
 * face the axis (a peg's face away). Coaxial pieces (a hole cut by a cross hole) merge into one.
 */
object HoleFinder {
    /** A hole: [lo] / [hi] = the axis points at its two ends, [axis] 0 / 1 / 2 = X / Y / Z. */
    data class Hole(val axis: Int, val lo: Vec3, val hi: Vec3, val diameter: Float)

    class Result(val holes: List<Hole>, val boxLo: Vec3, val boxHi: Vec3)

    private const val CREASE_DEG = 50.0
    private const val WALL_TOLERANCE = 0.08 // |normal . axis| below this = a wall
    private const val MIN_COVERAGE = 0.6 // share of the 36 ten-degree sectors the wall covers
    private const val MAX_TRIANGLES = 600_000

    fun find(mesh: FloatData, ids: Set<Int>): Result? {
        val f = mesh.data.duplicate()
        val triCount = mesh.count / 3
        // Selected model triangles (same type filter as the renderer's selection box).
        val pos = ArrayList<FloatArray>()
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (t in 0 until triCount) {
            val b = t * 24
            val type = Math.round(f.get(b + 7))
            if (type != 0 && type != 99 && type < 100) continue
            if (Math.round(f.get(b + 6)) !in ids) continue
            val p = FloatArray(9) { f.get(b + (it / 3) * 8 + it % 3) }
            for (k in 0..8) { lo[k % 3] = min(lo[k % 3], p[k]); hi[k % 3] = max(hi[k % 3], p[k]) }
            pos += p
            if (pos.size > MAX_TRIANGLES) return null
        }
        if (pos.isEmpty()) return null
        val holes = ArrayList<Hole>()
        for (axis in 0..2) holes += findAlong(pos, axis)
        return Result(holes, Vec3(lo[0], lo[1], lo[2]), Vec3(hi[0], hi[1], hi[2]))
    }

    private class Wall(val p: FloatArray, val n: DoubleArray, val area: Double, val v: IntArray)

    private class Piece(val cu: Double, val cv: Double, val r: Double, var aLo: Double, var aHi: Double)

    private fun findAlong(tris: List<FloatArray>, axis: Int): List<Hole> {
        val u = (axis + 1) % 3
        val w = (axis + 2) % 3
        // Weld at 1 µm so the walls share edges.
        val weld = HashMap<Long, Int>()
        fun vid(p: FloatArray, i: Int): Int {
            val key = ((p[i * 3] * 1000).roundToLong() and 0x1FFFFF) or
                (((p[i * 3 + 1] * 1000).roundToLong() and 0x1FFFFF) shl 21) or
                (((p[i * 3 + 2] * 1000).roundToLong() and 0x1FFFFF) shl 42)
            return weld.getOrPut(key) { weld.size }
        }
        val walls = ArrayList<Wall>()
        for (p in tris) {
            val ax = (p[3] - p[0]).toDouble(); val ay = (p[4] - p[1]).toDouble(); val az = (p[5] - p[2]).toDouble()
            val bx = (p[6] - p[0]).toDouble(); val by = (p[7] - p[1]).toDouble(); val bz = (p[8] - p[2]).toDouble()
            val n = doubleArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
            val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (len < 1e-9) continue
            for (k in 0..2) n[k] /= len
            if (abs(n[axis]) > WALL_TOLERANCE) continue
            walls += Wall(p, n, len / 2, IntArray(3) { vid(p, it) })
        }
        if (walls.size < 6) return emptyList()

        // Group over shared edges unless the crease between the two is sharp.
        val parent = IntArray(walls.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        val creaseCos = kotlin.math.cos(Math.toRadians(CREASE_DEG))
        val edges = HashMap<Long, Int>()
        for ((i, t) in walls.withIndex()) for (e in 0..2) {
            val a = t.v[e]; val b = t.v[(e + 1) % 3]
            val key = (min(a, b).toLong() shl 32) or max(a, b).toLong()
            val j = edges.put(key, i) ?: continue
            val o = walls[j]
            if (t.n[0] * o.n[0] + t.n[1] * o.n[1] + t.n[2] * o.n[2] >= creaseCos) parent[root(i)] = root(j)
        }
        val groups = walls.indices.groupBy { root(it) }.values.filter { it.size >= 6 }

        val pieces = ArrayList<Piece>()
        for (g in groups) fitHole(g.map { walls[it] }, axis, u, w)?.let { pieces += it }
        // Merge coaxial pieces of the same size.
        val merged = ArrayList<Piece>()
        for (p in pieces.sortedBy { it.aLo }) {
            val m = merged.firstOrNull { abs(it.r - p.r) < 0.02 * p.r + 0.02 && hypot(it.cu - p.cu, it.cv - p.cv) < 0.05 * p.r + 0.02 }
            if (m == null) merged += p else { m.aLo = min(m.aLo, p.aLo); m.aHi = max(m.aHi, p.aHi) }
        }
        return merged.map { p ->
            fun at(a: Double): Vec3 {
                val c = FloatArray(3); c[axis] = a.toFloat(); c[u] = p.cu.toFloat(); c[w] = p.cv.toFloat()
                return Vec3(c[0], c[1], c[2])
            }
            Hole(axis, at(p.aLo), at(p.aHi), (2 * p.r).toFloat())
        }
    }

    private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

    /** Circle through the group's corners across [axis], or null when it isn't a round hole. */
    private fun fitHole(g: List<Wall>, axis: Int, u: Int, w: Int): Piece? {
        // Kasa fit (x² + y² + Dx + Ey + F = 0, least squares) on centred coordinates.
        var mu = 0.0; var mv = 0.0; var n = 0
        for (t in g) for (i in 0..2) { mu += t.p[i * 3 + u]; mv += t.p[i * 3 + w]; n++ }
        mu /= n; mv /= n
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        for (t in g) for (i in 0..2) {
            val x = t.p[i * 3 + u] - mu; val y = t.p[i * 3 + w] - mv; val z = x * x + y * y
            sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y; sxz += x * z; syz += y * z; sz += z
        }
        // [sxx sxy sx; sxy syy sy; sx sy n] (D E F) = -(sxz syz sz)
        val m = arrayOf(doubleArrayOf(sxx, sxy, sx, -sxz), doubleArrayOf(sxy, syy, sy, -syz), doubleArrayOf(sx, sy, n.toDouble(), -sz))
        val sol = solve3(m) ?: return null
        val cx = -sol[0] / 2; val cy = -sol[1] / 2
        val r2 = cx * cx + cy * cy - sol[2]
        if (r2 <= 0) return null
        val r = sqrt(r2)
        if (r < 0.2 || r > 300) return null
        // Every corner on the circle.
        var worst = 0.0
        for (t in g) for (i in 0..2) worst = max(worst, abs(hypot(t.p[i * 3 + u] - mu - cx, t.p[i * 3 + w] - mv - cy) - r))
        if (worst > max(0.02 * r, 0.02)) return null
        // Wraps most of the way round: the sectors each triangle spans.
        val sectors = BooleanArray(36)
        var inward = 0.0
        var aLo = Double.MAX_VALUE; var aHi = -Double.MAX_VALUE
        for (t in g) {
            val angles = DoubleArray(3) { atan2(t.p[it * 3 + w] - mv - cy, t.p[it * 3 + u] - mu - cx) }
            var a0 = angles.min(); var a1 = angles.max()
            if (a1 - a0 > Math.PI) { // wraps past ±180°
                val shifted = angles.map { if (it < 0) it + 2 * Math.PI else it }
                a0 = shifted.min(); a1 = shifted.max()
            }
            var s = Math.floorDiv(Math.toDegrees(a0).toInt() + 360, 10)
            val e = Math.floorDiv(Math.toDegrees(a1).toInt() + 360, 10)
            while (s <= e) { sectors[s % 36] = true; s++ }
            // Facing the axis = the solid is outside = a hole.
            var gu = 0.0; var gv = 0.0
            for (i in 0..2) { gu += t.p[i * 3 + u]; gv += t.p[i * 3 + w] }
            inward += t.area * (t.n[u] * (mu + cx - gu / 3) + t.n[w] * (mv + cy - gv / 3))
            for (i in 0..2) { aLo = min(aLo, t.p[i * 3 + axis].toDouble()); aHi = max(aHi, t.p[i * 3 + axis].toDouble()) }
        }
        if (sectors.count { it } < MIN_COVERAGE * 36 || inward <= 0) return null
        return Piece(mu + cx, mv + cy, r, aLo, aHi)
    }

    /** Gaussian elimination with partial pivoting on a 3x4 augmented matrix. */
    private fun solve3(m: Array<DoubleArray>): DoubleArray? {
        for (c in 0..2) {
            val p = (c..2).maxBy { abs(m[it][c]) }
            if (abs(m[p][c]) < 1e-12) return null
            val tmp = m[c]; m[c] = m[p]; m[p] = tmp
            for (r in 0..2) if (r != c) {
                val k = m[r][c] / m[c][c]
                for (j in c..3) m[r][j] -= k * m[c][j]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }
}
