package app.orcaandroid.scad

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the bounds and triangles of an STL file (binary or ASCII), for a mesh shape's box and face ops. */
object Stl {
    private val cache = object : LinkedHashMap<String, FloatArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 2
    }

    /**
     * The triangles (9 floats each: 3 corners x, y, z), or null when the file isn't an STL with one. Cached per path:
     * the builder's mesh files are named by content hash, so they never change under it.
     */
    fun triangles(file: File): FloatArray? {
        synchronized(cache) { cache[file.absolutePath]?.let { return it } }
        val out = if (binary(file)) {
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val count = (buf.limit() - 84) / 50
            FloatArray(count * 9).also { a ->
                for (t in 0 until count) for (k in 0 until 9) a[t * 9 + k] = buf.getFloat(84 + t * 50 + 12 + k * 4)
            }
        } else {
            val v = ArrayList<Float>()
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val t = line.trim()
                    if (t.startsWith("vertex")) {
                        val p = t.split(Regex("\\s+"))
                        if (p.size >= 4) for (i in 1..3) v.add(p[i].toFloatOrNull() ?: Float.NaN)
                    }
                }
            }
            FloatArray(v.size / 9 * 9) { v[it] }
        }
        if (out.isEmpty() || out.any { !it.isFinite() }) return null
        synchronized(cache) { cache[file.absolutePath] = out }
        return out
    }

    private fun binary(file: File): Boolean {
        val len = file.length()
        return len >= 84 && file.inputStream().use { input ->
            val head = ByteArray(84)
            input.read(head) == 84 && 84L + 50L * (ByteBuffer.wrap(head, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL) == len
        }
    }

    /** Bounds as min x / y / z, max x / y / z (mm); null when the file isn't an STL with at least one triangle. */
    fun bounds(file: File): List<Double>? {
        val b = DoubleArray(6) { if (it < 3) Double.MAX_VALUE else -Double.MAX_VALUE }
        fun add(x: Double, y: Double, z: Double) {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
            b[0] = minOf(b[0], x); b[1] = minOf(b[1], y); b[2] = minOf(b[2], z)
            b[3] = maxOf(b[3], x); b[4] = maxOf(b[4], y); b[5] = maxOf(b[5], z)
        }
        if (binary(file)) {
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            var at = 84
            while (at + 50 <= buf.limit()) {
                for (v in 0 until 3) {
                    val p = at + 12 + v * 12
                    add(buf.getFloat(p).toDouble(), buf.getFloat(p + 4).toDouble(), buf.getFloat(p + 8).toDouble())
                }
                at += 50
            }
        } else {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val t = line.trim()
                    if (t.startsWith("vertex")) {
                        val p = t.split(Regex("\\s+"))
                        if (p.size >= 4) add(p[1].toDoubleOrNull() ?: Double.NaN, p[2].toDoubleOrNull() ?: Double.NaN, p[3].toDoubleOrNull() ?: Double.NaN)
                    }
                }
            }
        }
        return if (b[0] <= b[3]) b.toList() else null
    }
}
