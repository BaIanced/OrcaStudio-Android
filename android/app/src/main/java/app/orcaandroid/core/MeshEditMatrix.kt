package app.orcaandroid.core

import app.orcaandroid.scad.D3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The [Engine.meshEdit] "move" matrix (row-major world 4x4, applied about the selection's pivot): translate [move] mm after
 * rotating Z * Y * X ([rotate] degrees) after scaling ([scale] percent). Used by the Edit mesh fields and MCP `mesh_edit`.
 */
fun meshEditMatrix(move: D3?, scale: D3?, rotate: D3?): List<Double> {
    val s = scale ?: D3(100.0, 100.0, 100.0)
    val (ax, ay, az) = (rotate ?: D3(0.0, 0.0, 0.0)).let { listOf(it.x, it.y, it.z).map { d -> Math.toRadians(d) } }
    val (cx, sx) = cos(ax) to sin(ax); val (cy, sy) = cos(ay) to sin(ay); val (cz, sz) = cos(az) to sin(az)
    val r = arrayOf( // Rz * Ry * Rx
        doubleArrayOf(cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx),
        doubleArrayOf(sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx),
        doubleArrayOf(-sy, cy * sx, cy * cx))
    val k = doubleArrayOf(s.x / 100, s.y / 100, s.z / 100)
    val t = move ?: D3(0.0, 0.0, 0.0)
    val tv = doubleArrayOf(t.x, t.y, t.z)
    return (0..2).flatMap { i -> (0..2).map { j -> r[i][j] * k[j] } + tv[i] } + listOf(0.0, 0.0, 0.0, 1.0)
}
