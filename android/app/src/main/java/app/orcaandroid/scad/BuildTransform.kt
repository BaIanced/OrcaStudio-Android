package app.orcaandroid.scad

import app.orcaandroid.core.Instance
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.RotateGizmo
import kotlin.math.abs

/**
 * The Build workspace's gizmos act on the plate; these map a plate resize or turn back into the shapes' fields. A copy of
 * the design object puts design point p at offset + R * scale * mirror * p (the volumes are the design frame, step 3).
 */
object BuildTransform {
    /** Where the shape's fields go after a gizmo: pos, rot, size and repeat, as [ShapeDesign] reads them. */
    data class Pose(val pos: D3, val rot: D3, val size: D3, val repeat: Repeat?)

    /** Plate point [w] in the design frame of copy [inst]. */
    fun toDesign(inst: Instance, w: Vec3): D3 {
        val r = RotateGizmo.fromEuler(inst.rotation)
        val q = doubleArrayOf((w.x - inst.offset.x).toDouble(), (w.y - inst.offset.y).toDouble(), (w.z - inst.offset.z).toDouble())
        // R^T, then 1 / scale and the mirror.
        fun axis(i: Int, scale: Float, mirror: Float) = (0..2).sumOf { r[it * 3 + i] * q[it] } / scale * (if (mirror < 0f) -1.0 else 1.0)
        return D3(axis(0, inst.scale.x, inst.mirror.x), axis(1, inst.scale.y, inst.mirror.y), axis(2, inst.scale.z, inst.mirror.z))
    }

    /** Plate turn [m] (row-major 3 x 3) in the design frame of copy [inst]: mirror * R^T * m * R * mirror (its scale taken as even). */
    fun designTurn(inst: Instance, m: FloatArray): Array<DoubleArray> {
        val r = RotateGizmo.fromEuler(inst.rotation)
        val f = doubleArrayOf(sign(inst.mirror.x), sign(inst.mirror.y), sign(inst.mirror.z))
        val mr = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { m[i * 3 + it].toDouble() * r[it * 3 + j] } } }
        return Array(3) { i -> DoubleArray(3) { j -> f[i] * f[j] * (0..2).sumOf { r[it * 3 + i] * mr[it][j] } } }
    }

    /**
     * Plate scale factors [f] along the plate axes in the design frame of copy [inst]; null when the copy is turned off
     * the axes (only an even scale maps then).
     */
    fun designScale(inst: Instance, f: Vec3): D3? {
        val r = RotateGizmo.fromEuler(inst.rotation)
        val m = Array(3) { i -> DoubleArray(3) { j -> r[i * 3 + j].toDouble() } }
        val axes = axesOf(m) ?: return null
        val out = DoubleArray(3)
        val fw = doubleArrayOf(f.x.toDouble(), f.y.toDouble(), f.z.toDouble())
        for (i in 0..2) out[axes[i]] = fw[i]
        return D3(out[0], out[1], out[2])
    }

    /**
     * Whether copy [inst], the groups holding [shapes] ([ShapeDesign.parentFrame]) and every shape in [shapes] lie along
     * the axes, so X, Y and Z can be scaled alone.
     */
    fun axesFree(d: ShapeDesign, inst: Instance, shapes: List<Shape>): Boolean {
        val r = RotateGizmo.fromEuler(inst.rotation)
        return axesOf(Array(3) { i -> DoubleArray(3) { j -> r[i * 3 + j].toDouble() } }) != null &&
            shapes.all { axesOf(linear(d.parentFrame(it.id))) != null && axesOf(ShapeDesign.matrix(d.rot(it))) != null }
    }

    /** Plate point [p] of design point [q] on copy [inst] (offset + R * scale * mirror * q): [toDesign]'s inverse. */
    fun toPlate(inst: Instance, q: D3): Vec3 {
        val r = RotateGizmo.fromEuler(inst.rotation)
        val l = doubleArrayOf(q.x * inst.scale.x * sign(inst.mirror.x), q.y * inst.scale.y * sign(inst.mirror.y), q.z * inst.scale.z * sign(inst.mirror.z))
        fun axis(i: Int) = (0..2).sumOf { r[i * 3 + it] * l[it] }.toFloat()
        return Vec3(inst.offset.x + axis(0), inst.offset.y + axis(1), inst.offset.z + axis(2))
    }

    /** Design point [p] in [frame] (rigid row-major 4 x 4, [ShapeDesign.parentFrame]). */
    fun inFrame(frame: DoubleArray, p: D3): D3 {
        val q = D3(p.x - frame[3], p.y - frame[7], p.z - frame[11])
        return D3((0..2).sumOf { frame[it * 4] * q[it] }, (0..2).sumOf { frame[it * 4 + 1] * q[it] }, (0..2).sumOf { frame[it * 4 + 2] * q[it] })
    }

    /** Design turn [m] in [frame]: A^T * m * A (A = the frame's turn and flip). */
    fun turnInFrame(frame: DoubleArray, m: Array<DoubleArray>): Array<DoubleArray> {
        val a = linear(frame)
        val ma = mul(m, a)
        return Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { a[it][i] * ma[it][j] } } }
    }

    /** Design scale factors [f] along the axes of [frame]; null when they are uneven and the frame is turned off the axes. */
    fun scaleInFrame(frame: DoubleArray, f: D3): D3? {
        if (abs(f.x - f.y) < 1e-9 && abs(f.y - f.z) < 1e-9) return f
        val axes = axesOf(linear(frame)) ?: return null
        val out = DoubleArray(3)
        for (i in 0..2) out[axes[i]] = f[i]
        return D3(out[0], out[1], out[2])
    }

    private fun linear(m: DoubleArray) = Array(3) { i -> DoubleArray(3) { j -> m[i * 4 + j] } }

    /** Shape [s] turned by [m] about point [c], both in its parent's frame (the design's at the top level: [turnInFrame]). */
    fun turned(d: ShapeDesign, s: Shape, m: Array<DoubleArray>, c: D3): Pose {
        // The shape turns about its middle (pos + pivot): move pos so its middle turns about c instead.
        val pivot = D3(0.0, 0.0, d.height(s) / 2)
        val pos = apply(m, d.pos(s) + pivot - c) + c - pivot
        val rot = ShapeDesign.angles(mul(m, ShapeDesign.matrix(d.rot(s))))
        val repeat = s.repeat?.let { r -> if (r.circular) r.copy(center = apply(m, r.center - c) + c) else r.copy(step = apply(m, r.step)) }
        return Pose(round(pos), rot, d.size(s), repeat?.let { it.copy(step = round(it.step), center = round(it.center)) })
    }

    /**
     * Shape [s] scaled by [f] about point [p], both in its parent's frame ([scaleInFrame]); null for a group, or when [f] is uneven
     * and the shape is turned off the axes — or has turned copies (a circular repeat) and X and Y differ, which an
     * uneven scale cannot express either.
     */
    fun scaled(d: ShapeDesign, s: Shape, f: D3, p: D3): Pose? {
        if (s.isGroup) return null
        // The factors along the shape's own axes: design axis j holds own axis k where its turn has a non-zero.
        val even = abs(f.x - f.y) < 1e-9 && abs(f.y - f.z) < 1e-9
        // A circular repeat's copies are the shape turned about the centre's Z axis, which commutes only with a
        // scale even in X and Y: otherwise each copy would come out scaled along the wrong axes. Z alone is fine.
        if (abs(f.x - f.y) >= 1e-9 && s.repeat?.let { it.circular && it.count > 1 } == true) return null
        val own = if (even) f else {
            val axes = axesOf(ShapeDesign.matrix(d.rot(s))) ?: return null
            D3(f[axes.indexOf(0)], f[axes.indexOf(1)], f[axes.indexOf(2)])
        }
        val size = d.size(s).let { D3(it.x * own.x, it.y * own.y, it.z * own.z) }
        val middle = d.pos(s) + D3(0.0, 0.0, d.height(s) / 2)
        fun about(q: D3) = D3(p.x + (q.x - p.x) * f.x, p.y + (q.y - p.y) * f.y, p.z + (q.z - p.z) * f.z)
        val pos = about(middle) - D3(0.0, 0.0, size.z / 2)
        val repeat = s.repeat?.let { r ->
            if (r.circular) r.copy(center = round(about(r.center))) else r.copy(step = round(D3(r.step.x * f.x, r.step.y * f.y, r.step.z * f.z)))
        }
        return Pose(round(pos), d.rot(s), round(size), repeat)
    }

    /**
     * A tap: the index into [hit] (the shapes under it, outermost first: [ShapeDesign.pathAt]) to pick while [sel] (the
     * selected shape's groups, outermost first, then it) is selected: one below the deepest shape the two share, so a tap
     * on the selection or inside it goes one level deeper, a tap beside it stays on its level.
     */
    fun pickLevel(sel: List<Int>, hit: List<Int>): Int {
        var k = 0
        while (k < sel.size && k < hit.size && sel[k] == hit[k]) k++
        return k.coerceAtMost(hit.lastIndex)
    }

    /** For each row of [m] the column of its one non-zero, if [m] only swaps and flips axes; else null. */
    private fun axesOf(m: Array<DoubleArray>): IntArray? {
        val out = IntArray(3)
        for (i in 0..2) {
            val big = (0..2).filter { abs(m[i][it]) > 1e-4 }
            if (big.size != 1 || abs(abs(m[i][big[0]]) - 1.0) > 1e-4) return null
            out[i] = big[0]
        }
        return out.takeIf { it.toSet().size == 3 }
    }

    private fun sign(v: Float) = if (v < 0f) -1.0 else 1.0
    private fun apply(m: Array<DoubleArray>, p: D3) = D3((0..2).sumOf { m[0][it] * p[it] }, (0..2).sumOf { m[1][it] * p[it] }, (0..2).sumOf { m[2][it] * p[it] })
    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { a[i][it] * b[it][j] } } }
    private fun round(v: Double) = Math.round(v * 1000.0) / 1000.0 + 0.0
    private fun round(p: D3) = D3(round(p.x), round(p.y), round(p.z))
}
