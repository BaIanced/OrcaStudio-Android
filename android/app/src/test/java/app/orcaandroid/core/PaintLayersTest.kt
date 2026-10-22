package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.FloatBuffer

/**
 * Hand-computed expectations for [PaintLayers]. Layers: first 0.2 mm, then 0.2 mm, so layer tops are 0.2, 0.4, 0.6, ...
 * and mid heights (cut planes) 0.1, 0.3, 0.5, 0.7, 0.9.
 */
class PaintLayersTest {
    private fun span(f: Int, a: Float, b: Float) = PaintLayers.Span(f, a, b)

    @Test fun sideBandAboveBase() {
        // Base 0 over 0..1 mm (5 layers); filament 1 painted on the side from 0.4 to 1.0: mids 0.5, 0.7, 0.9 = layers 2-4.
        val l = PaintLayers.layers(listOf(span(0, 0f, 1f), span(1, 0.4f, 1f)), 0.2f, 0.2f)
        assertEquals(listOf(listOf(0), listOf(0), listOf(0, 1), listOf(0, 1), listOf(0, 1)), l)
    }

    @Test fun flatTopGoesOnTheTopLayer() {
        // A flat painted top at z = 1.0 crosses no mid: it lands on the layer holding it, layer 4 (0.8-1.0).
        val l = PaintLayers.layers(listOf(span(0, 0f, 1f), span(2, 1f, 1f)), 0.2f, 0.2f)
        assertEquals(listOf(0, 2), l[4])
        assertEquals(5, l.size)
        assertEquals(listOf(0), l[3])
    }

    @Test fun firstLayerDiffers() {
        // First layer 0.3 (mid 0.15), then 0.2 (mids 0.4, 0.6). A span 0.2..0.45 contains mid 0.4 only (0.15 < 0.2):
        // layer 1. The base spans 0..0.7 = 3 layers (tops 0.3, 0.5, 0.7).
        val l = PaintLayers.layers(listOf(span(0, 0f, 0.7f), span(1, 0.2f, 0.45f)), 0.3f, 0.2f)
        assertEquals(listOf(listOf(0), listOf(0, 1), listOf(0)), l)
    }

    @Test fun emptyOrBadInput() {
        assertEquals(emptyList<List<Int>>(), PaintLayers.layers(emptyList(), 0.2f, 0.2f))
        assertEquals(emptyList<List<Int>>(), PaintLayers.layers(listOf(span(0, 0f, 1f)), 0.2f, 0f))
    }

    @Test fun spansReadColourTrianglesOfKeptCopies() {
        // Three triangles: colour filament 1 on object 1 copy 2, support paint (code 6) on the same copy, colour
        // filament 0 on object 0. Keeping object 1 only leaves the first, z from 0.5 to 2.5.
        fun tri(z: List<Float>, obj: Int, inst: Int, type: Int) = z.flatMap {
            listOf(0f, 0f, it, 0f, 0f, 1f, (obj * 4096 + inst).toFloat(), type.toFloat())
        }
        val floats = tri(listOf(1f, 0.5f, 2.5f), 1, 2, 11) + tri(listOf(0f, 0f, 0f), 1, 2, 6) + tri(listOf(3f, 3f, 3f), 0, 0, 10)
        val spans = PaintLayers.spans(FloatBuffer.wrap(floats.toFloatArray())) { obj, _ -> obj == 1 }
        assertEquals(listOf(span(1, 0.5f, 2.5f)), spans)
    }

    @Test fun forPlateAddsBaseFilamentOfCopiesOnThePlate() {
        // Object 0 (filament 2) on plate 0, 1 mm high, painted filament 0 at z 0.6..1; object 1 sits on plate 1 only.
        val v = Vec3(0f, 0f, 0f)
        fun inst(i: Int, plate: Int, h: Float) = Instance(i, plate, v, v, v, v, Vec3(10f, 10f, h), v)
        fun obj(i: Int, ext: String?, vararg inst: Instance) =
            SceneObject(i, "o$i", 12, ext?.let { mapOf("extruder" to it) } ?: emptyMap(), emptyList(), inst.toList(), emptyList(), false)
        val objects = listOf(obj(0, "2", inst(0, 0, 1f)), obj(1, null, inst(0, 1, 2f)))
        val paint = listOf(0.6f, 1f, 1f).flatMap { listOf(0f, 0f, it, 0f, 0f, 1f, 0f, 10f) }
        val layers = PaintLayers.forPlate(FloatBuffer.wrap(paint.toFloatArray()), objects, 0, 0.2f, 0.2f)
        assertEquals(listOf(listOf(1), listOf(1), listOf(1), listOf(0, 1), listOf(0, 1)), layers)
    }
}
