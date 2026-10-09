package app.orcaandroid.render

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
            // Copied, not memory-mapped: the engine rewrites these files in place (each paint stroke,
            // a new slice) while the GL thread may still upload the previous data, and a mapping of
            // a truncated file raises SIGBUS in glBufferData.
            return RandomAccessFile(file, "r").use { f ->
                val bytes = ByteBuffer.allocateDirect(f.length().toInt()).order(ByteOrder.LITTLE_ENDIAN)
                while (bytes.hasRemaining() && f.channel.read(bytes) > 0) Unit
                // A file that shrank while being read keeps whole records only.
                val record = 4 * stride
                bytes.limit(bytes.position() / record * record)
                bytes.position(0)
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
