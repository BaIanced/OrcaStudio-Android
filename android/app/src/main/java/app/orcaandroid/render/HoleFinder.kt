package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Round holes and slots in the scene mesh (View > Dimensions, phase 2). Pure Kotlin on the uploaded triangle soup
 * (8 floats per vertex: position, normal, object id, type), no engine call.
 *
 * Walls are welded and grouped over shared edges, split at creases sharper than [CREASE_DEG] so a box's sides stay apart
 * from a hole in it. Pass 1 looks along X, Y and Z (only triangles whose normal is across that axis, so a countersink or
 * chamfer next to the wall doesn't matter). Pass 2 takes every smooth group whose normals lie in one plane and uses that
 * plane's normal as the axis, which finds tilted holes. Each group gets a least-squares circle in the plane across the
 * axis, or failing that a slot (two half circles joined by straight sides); it is a hole when the outline fits, the wall
 * wraps most of the way round and its normals face the inside (a peg's face away). Coaxial pieces (a hole cut by a cross
 * hole) merge into one.
 */
object HoleFinder {
    /**
     * A hole: [lo] / [hi] = the axis points at its two ends, [dir] = unit axis from [lo] to [hi], [axis] 0 / 1 / 2 = the
     * principal axis [dir] is nearest to. Round: [length] == [diameter]. Slot: [diameter] = width, [length] = overall
     * length along [major] (unit, across the axis).
     */
    data class Hole(val axis: Int, val lo: Vec3, val hi: Vec3, val diameter: Float, val dir: Vec3, val length: Float, val major: Vec3) {
        val isSlot get() = length > diameter * 1.001f
    }

    class Result(val holes: List<Hole>, val boxLo: Vec3, val boxHi: Vec3)

    private const val CREASE_DEG = 50.0
    private const val WALL_TOLERANCE = 0.08 // |normal . axis| below this = a wall
    private const val MIN_COVERAGE = 0.6 // share of the 36 sectors of the outline the wall covers
    private const val MAX_TRIANGLES = 600_000
    private const val TILT_MAX_TRIANGLES = 250_000 // pass 2 (tilted holes) skips bigger meshes
    private const val PLANAR = 0.004 // smallest / total eigenvalue of the normals: they lie in one plane
    private const val SPREAD = 0.08 // middle / total: the normals aren't all alike (a flat face)

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
        val pieces = ArrayList<Piece>()
        for (axis in 0..2) {
            val d = DoubleArray(3).also { it[axis] = 1.0 }
            val fr = frame(d)
            for (g in wallGroups(pos) { abs(it[axis]) <= WALL_TOLERANCE }) fitHole(g, fr)?.let { pieces += it }
        }
        if (pos.size <= TILT_MAX_TRIANGLES) {
            for (g in wallGroups(pos) { true }) {
                val d = planeNormal(g) ?: continue
                if (1 - (0..2).maxOf { abs(d[it]) } < 1e-5) continue // along a principal axis: pass 1's
                fitHole(g, frame(d))?.let { pieces += it }
            }
        }
        return Result(merge(pieces), Vec3(lo[0], lo[1], lo[2]), Vec3(hi[0], hi[1], hi[2]))
    }

    private class Wall(val p: FloatArray, val n: DoubleArray, val area: Double) { val v = IntArray(3) }

    /** The hole's axis [d] (unit) and two unit vectors across it. */
    private class Frame(val d: DoubleArray, val e1: DoubleArray, val e2: DoubleArray)

    /** One fitted outline: [c] = centre in the plane through the origin across [d], [core] = half the straight part (0 = round). */
    private class Piece(val c: DoubleArray, val d: DoubleArray, val r: Double, val core: Double, val major: DoubleArray, var aLo: Double, var aHi: Double)

    /** A group's corners in its frame: plane coordinates [pa] / [pb], along the axis [ph], normals in the plane [na] / [nb]. */
    private class Prof(val n: Int, val area: DoubleArray, val pa: DoubleArray, val pb: DoubleArray, val ph: DoubleArray, val na: DoubleArray, val nb: DoubleArray)

    /** Open-addressing Long -> Int map (the weld and edge tables of a big mesh must not box). Values are >= 0. */
    private class LongIntMap(expected: Int) {
        private val mask: Int
        private val keys: LongArray
        private val vals: IntArray

        init {
            var cap = 16
            while (cap < expected * 2) cap *= 2
            mask = cap - 1
            keys = LongArray(cap)
            vals = IntArray(cap) { -1 }
        }

        /** Stores [v] under [k] and returns the value it replaced, or -1. */
        fun put(k: Long, v: Int): Int {
            var i = slot(k)
            val old = vals[i]
            keys[i] = k; vals[i] = v
            return old
        }

        /** The value stored under [k]; stores [v] and returns it when there was none. */
        fun getOrPut(k: Long, v: Int): Int {
            val i = slot(k)
            if (vals[i] >= 0) return vals[i]
            keys[i] = k; vals[i] = v
            return v
        }

        private fun slot(k: Long): Int {
            var i = ((k * -7046029254386353131L) ushr 32).toInt() and mask
            while (vals[i] != -1 && keys[i] != k) i = (i + 1) and mask
            return i
        }
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

    private fun frame(d: DoubleArray): Frame {
        val k = (0..2).minBy { abs(d[it]) }
        val h = DoubleArray(3).also { it[k] = 1.0 }
        val e1 = cross(h, d)
        val l = sqrt(dot(e1, e1))
        for (i in 0..2) e1[i] /= l
        return Frame(d, e1, cross(d, e1))
    }

    /** Walls (triangles passing [accept] on their unit normal) welded and grouped over shared smooth edges, groups of 6+. */
    private fun wallGroups(tris: List<FloatArray>, accept: (DoubleArray) -> Boolean): List<List<Wall>> {
        val walls = ArrayList<Wall>()
        for (p in tris) {
            val ax = (p[3] - p[0]).toDouble(); val ay = (p[4] - p[1]).toDouble(); val az = (p[5] - p[2]).toDouble()
            val bx = (p[6] - p[0]).toDouble(); val by = (p[7] - p[1]).toDouble(); val bz = (p[8] - p[2]).toDouble()
            val n = doubleArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
            val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (len < 1e-9) continue
            for (k in 0..2) n[k] /= len
            if (accept(n)) walls += Wall(p, n, len / 2)
        }
        if (walls.size < 6) return emptyList()
        // Weld at 1 µm so the walls share edges.
        val weld = LongIntMap(walls.size * 3)
        var next = 0
        for (t in walls) for (i in 0..2) {
            val key = ((t.p[i * 3] * 1000).roundToLong() and 0x1FFFFF) or
                (((t.p[i * 3 + 1] * 1000).roundToLong() and 0x1FFFFF) shl 21) or
                (((t.p[i * 3 + 2] * 1000).roundToLong() and 0x1FFFFF) shl 42)
            val id = weld.getOrPut(key, next)
            if (id == next) next++
            t.v[i] = id
        }
        // Group over shared edges unless the crease between the two is sharp.
        val parent = IntArray(walls.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        val creaseCos = cos(Math.toRadians(CREASE_DEG))
        val edges = LongIntMap(walls.size * 3)
        for ((i, t) in walls.withIndex()) for (e in 0..2) {
            val a = t.v[e]; val b = t.v[(e + 1) % 3]
            val key = (min(a, b).toLong() shl 32) or max(a, b).toLong()
            val j = edges.put(key, i)
            if (j < 0) continue
            val o = walls[j]
            if (t.n[0] * o.n[0] + t.n[1] * o.n[1] + t.n[2] * o.n[2] >= creaseCos) parent[root(i)] = root(j)
        }
        return walls.indices.groupBy { root(it) }.values.filter { it.size >= 6 }.map { g -> g.map { walls[it] } }
    }

    /** The axis the group's normals are perpendicular to (largest component positive), or null when they aren't flat in one plane. */
    private fun planeNormal(g: List<Wall>): DoubleArray? {
        val m = Array(3) { DoubleArray(3) }
        var tr = 0.0
        for (t in g) {
            for (i in 0..2) for (j in 0..2) m[i][j] += t.area * t.n[i] * t.n[j]
            tr += t.area
        }
        val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } } // columns = eigenvectors
        for (sweep in 0 until 30) {
            var p = 0; var q = 1
            if (abs(m[0][2]) > abs(m[p][q])) { p = 0; q = 2 }
            if (abs(m[1][2]) > abs(m[p][q])) { p = 1; q = 2 }
            if (abs(m[p][q]) < 1e-12 * tr) break
            val theta = (m[q][q] - m[p][p]) / (2 * m[p][q])
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(t * t + 1); val s = t * c
            val app = m[p][p]; val aqq = m[q][q]; val apq = m[p][q]
            m[p][p] = app - t * apq; m[q][q] = aqq + t * apq; m[p][q] = 0.0; m[q][p] = 0.0
            val r = 3 - p - q
            val arp = m[r][p]; val arq = m[r][q]
            m[r][p] = c * arp - s * arq; m[p][r] = m[r][p]
            m[r][q] = c * arq + s * arp; m[q][r] = m[r][q]
            for (k in 0..2) { val vp = v[k][p]; val vq = v[k][q]; v[k][p] = c * vp - s * vq; v[k][q] = s * vp + c * vq }
        }
        val order = (0..2).sortedBy { m[it][it] }
        if (m[order[0]][order[0]] > PLANAR * tr || m[order[1]][order[1]] < SPREAD * tr) return null
        val d = DoubleArray(3) { v[it][order[0]] }
        val big = (0..2).maxBy { abs(d[it]) }
        if (d[big] < 0) for (i in 0..2) d[i] = -d[i]
        return d
    }

    private fun fitHole(g: List<Wall>, f: Frame): Piece? {
        val n = g.size
        val q = Prof(n, DoubleArray(n), DoubleArray(n * 3), DoubleArray(n * 3), DoubleArray(n * 3), DoubleArray(n), DoubleArray(n))
        for ((k, t) in g.withIndex()) {
            q.area[k] = t.area
            q.na[k] = dot(t.n, f.e1); q.nb[k] = dot(t.n, f.e2)
            for (i in 0..2) {
                val p = doubleArrayOf(t.p[i * 3].toDouble(), t.p[i * 3 + 1].toDouble(), t.p[i * 3 + 2].toDouble())
                q.pa[k * 3 + i] = dot(p, f.e1); q.pb[k * 3 + i] = dot(p, f.e2); q.ph[k * 3 + i] = dot(p, f.d)
            }
        }
        return fitCircle(q, f) ?: fitSlot(q, f)
    }

    private fun piece(q: Prof, f: Frame, ca: Double, cb: Double, r: Double, core: Double, phi: Double): Piece {
        var aLo = Double.MAX_VALUE; var aHi = -Double.MAX_VALUE
        for (h in q.ph) { aLo = min(aLo, h); aHi = max(aHi, h) }
        val c = DoubleArray(3) { f.e1[it] * ca + f.e2[it] * cb }
        val major = DoubleArray(3) { f.e1[it] * cos(phi) + f.e2[it] * sin(phi) }
        return Piece(c, f.d, r, core, major, aLo, aHi)
    }

    /** Circle through the group's corners across the axis, or null when it isn't a round hole. */
    private fun fitCircle(q: Prof, f: Frame): Piece? {
        // Kasa fit (x² + y² + Dx + Ey + F = 0, least squares) on centred coordinates.
        val n = q.n * 3
        var mu = 0.0; var mv = 0.0
        for (i in 0 until n) { mu += q.pa[i]; mv += q.pb[i] }
        mu /= n; mv /= n
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        for (i in 0 until n) {
            val x = q.pa[i] - mu; val y = q.pb[i] - mv; val z = x * x + y * y
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
        for (i in 0 until n) worst = max(worst, abs(hypot(q.pa[i] - mu - cx, q.pb[i] - mv - cy) - r))
        if (worst > max(0.02 * r, 0.02)) return null
        val ca = mu + cx; val cb = mv + cy
        val ok = wraps(q, { a, b ->
            val t = atan2(b - cb, a - ca) / (2 * PI)
            (if (t < 0) t + 1 else t).coerceAtMost(0.999999)
        }, { _, _, out -> out[0] = ca; out[1] = cb })
        return if (ok) piece(q, f, ca, cb, r, 0.0, 0.0) else null
    }

    /** Stadium (two half circles joined by straight sides) through the group's corners, or null. */
    private fun fitSlot(q: Prof, f: Frame): Piece? {
        val n = q.n * 3
        var ma = 0.0; var mb = 0.0
        for (i in 0 until n) { ma += q.pa[i]; mb += q.pb[i] }
        ma /= n; mb /= n
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0 until n) { val x = q.pa[i] - ma; val y = q.pb[i] - mb; sxx += x * x; sxy += x * y; syy += y * y }
        val phi = 0.5 * atan2(2 * sxy, sxx - syy) // the long direction
        val cp = cos(phi); val sp = sin(phi)
        var sMin = Double.MAX_VALUE; var sMax = -Double.MAX_VALUE; var tMin = Double.MAX_VALUE; var tMax = -Double.MAX_VALUE
        for (i in 0 until n) {
            val x = q.pa[i] - ma; val y = q.pb[i] - mb
            val s = cp * x + sp * y; val t = -sp * x + cp * y
            sMin = min(sMin, s); sMax = max(sMax, s); tMin = min(tMin, t); tMax = max(tMax, t)
        }
        val r = (tMax - tMin) / 2
        val core = (sMax - sMin) / 2 - r
        if (r < 0.2 || r > 300 || core < 0.05 * r || core > 3000) return null
        val cs = (sMin + sMax) / 2; val ct = (tMin + tMax) / 2
        var worst = 0.0
        for (i in 0 until n) {
            val x = q.pa[i] - ma; val y = q.pb[i] - mb
            val s = cp * x + sp * y - cs; val t = -sp * x + cp * y - ct
            worst = max(worst, abs(hypot(max(abs(s) - core, 0.0), t) - r))
        }
        if (worst > max(0.02 * r, 0.02)) return null
        val perim = 4 * core + 2 * PI * r
        val ok = wraps(q, { a, b ->
            val x = a - ma; val y = b - mb
            val s = cp * x + sp * y - cs; val t = -sp * x + cp * y - ct
            val p = when {
                s > core -> r * (atan2(t, s - core) + PI / 2)
                s < -core -> PI * r + 2 * core + r * ((atan2(t, s + core).let { if (it < 0) it + 2 * PI else it }) - PI / 2)
                t >= 0 -> PI * r + (core - s)
                else -> 2 * PI * r + 2 * core + (s + core)
            }
            (p / perim).coerceIn(0.0, 0.999999)
        }, { a, b, out ->
            val x = a - ma; val y = b - mb
            val s = (cp * x + sp * y - cs).coerceIn(-core, core) + cs // nearest point of the straight middle
            out[0] = ma + cp * s - sp * ct; out[1] = mb + sp * s + cp * ct
        })
        if (!ok) return null
        return piece(q, f, ma + cp * cs - sp * ct, mb + sp * cs + cp * ct, r, core, phi)
    }

    /**
     * The wall wraps most of the way round the outline ([param] maps a plane point to 0..1 along it) and its normals face
     * the inside ([target] = the point the hole's inside is nearest to, written to its out array).
     */
    private fun wraps(q: Prof, param: (Double, Double) -> Double, target: (Double, Double, DoubleArray) -> Unit): Boolean {
        val sectors = BooleanArray(36)
        var inward = 0.0
        val tg = DoubleArray(2)
        val ps = DoubleArray(3)
        for (k in 0 until q.n) {
            for (i in 0..2) ps[i] = param(q.pa[k * 3 + i], q.pb[k * 3 + i])
            var a0 = min(ps[0], min(ps[1], ps[2])); var a1 = max(ps[0], max(ps[1], ps[2]))
            if (a1 - a0 > 0.5) { // wraps past the start
                for (i in 0..2) if (ps[i] < 0.5) ps[i] += 1.0
                a0 = min(ps[0], min(ps[1], ps[2])); a1 = max(ps[0], max(ps[1], ps[2]))
            }
            var s = floor(a0 * 36).toInt()
            val e = floor(a1 * 36).toInt()
            while (s <= e) { sectors[s % 36] = true; s++ }
            val ga = (q.pa[k * 3] + q.pa[k * 3 + 1] + q.pa[k * 3 + 2]) / 3
            val gb = (q.pb[k * 3] + q.pb[k * 3 + 1] + q.pb[k * 3 + 2]) / 3
            target(ga, gb, tg)
            inward += q.area[k] * (q.na[k] * (tg[0] - ga) + q.nb[k] * (tg[1] - gb))
        }
        return sectors.count { it } >= MIN_COVERAGE * 36 && inward > 0
    }

    /** Coaxial pieces of the same outline (a hole cut by a cross hole, or one found by both passes) become one hole. */
    private fun merge(pieces: List<Piece>): List<Hole> {
        class Acc(val p: Piece, var lo: Double, var hi: Double)
        val acc = ArrayList<Acc>()
        for (p in pieces) {
            val m = acc.firstOrNull { a ->
                val o = a.p
                if (abs(dot(o.d, p.d)) < 0.9995 || abs(o.r - p.r) > 0.02 * p.r + 0.02 || abs(o.core - p.core) > 0.02 * p.r + 0.02) return@firstOrNull false
                if (o.core > 0 && abs(dot(o.major, p.major)) < 0.999) return@firstOrNull false
                val v = DoubleArray(3) { p.c[it] - o.c[it] }
                val along = dot(v, o.d)
                sqrt(max(dot(v, v) - along * along, 0.0)) < 0.05 * p.r + 0.02
            }
            // Ends in the frame of the first piece.
            val lo = dot(p.c, (m?.p ?: p).d) + p.aLo * dot(p.d, (m?.p ?: p).d)
            val hi = dot(p.c, (m?.p ?: p).d) + p.aHi * dot(p.d, (m?.p ?: p).d)
            if (m == null) acc += Acc(p, min(lo, hi), max(lo, hi)) else { m.lo = min(m.lo, min(lo, hi)); m.hi = max(m.hi, max(lo, hi)) }
        }
        return acc.map { a ->
            val p = a.p
            fun at(x: Double) = Vec3((p.c[0] + p.d[0] * x).toFloat(), (p.c[1] + p.d[1] * x).toFloat(), (p.c[2] + p.d[2] * x).toFloat())
            Hole(
                (0..2).maxBy { abs(p.d[it]) }, at(a.lo), at(a.hi), (2 * p.r).toFloat(),
                Vec3(p.d[0].toFloat(), p.d[1].toFloat(), p.d[2].toFloat()), (2 * (p.r + p.core)).toFloat(),
                Vec3(p.major[0].toFloat(), p.major[1].toFloat(), p.major[2].toFloat()),
            )
        }
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
