package app.orcaandroid.render

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * Float data prepared off the GL thread and uploaded by [PlateRenderer]: the scene/paint meshes
 * (8 floats per vertex) and the preview buffers written by the native core.
 */
class FloatData(val data: FloatBuffer, val stride: Int) {
    val count get() = data.limit() / stride

    companion object {
        fun load(path: String, stride: Int): FloatData? {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return FloatData(directFloats(0), stride)
            // Memory-mapped, not copied: a big slice's preview runs to hundreds of MB, and a copy in a
            // direct buffer counts against the Java heap (OutOfMemoryError). The engine writes every
            // one of these files to a temporary name and renames it over the old one, so an existing
            // mapping keeps the previous data instead of seeing a truncated file (SIGBUS).
            return RandomAccessFile(file, "r").use { f ->
                val record = 4L * stride
                val bytes = f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length() / record * record).order(ByteOrder.LITTLE_ENDIAN)
                FloatData(bytes.asFloatBuffer(), stride)
            }
        }

        fun directFloats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(maxOf(n, 1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.limit(n) }
    }
}

/** The three preview buffers of one slice result. */
class PreviewData(val extrusions: FloatData, val travels: FloatData, val markers: FloatData) {
    companion object {
        const val EXTRUSION_STRIDE = 15
        const val TRAVEL_STRIDE = 6
        const val MARKER_STRIDE = 4

        fun load(dir: String) = PreviewData(
            FloatData.load("$dir/extrusions.bin", EXTRUSION_STRIDE)!!,
            FloatData.load("$dir/travels.bin", TRAVEL_STRIDE)!!,
            FloatData.load("$dir/markers.bin", MARKER_STRIDE)!!,
        )
    }

    /** G-code line of extrusion [index] (for the G-code text view). */
    fun gcodeLine(index: Int): Int = if (index in 0 until extrusions.count) extrusions.data.get(index * EXTRUSION_STRIDE + 14).toInt() else -1
}
