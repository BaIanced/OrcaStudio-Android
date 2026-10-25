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

    @Test fun paintDepthCarriesFlatFacesIn() {
        // A 2 mm cube (0.2 mm layers = 10): top face filament 1, bottom face filament 2, base filament 0.
        val v = Vec3(0f, 0f, 0f)
        fun cube(settings: Map<String, String>) =
            listOf(SceneObject(0, "c", 12, settings, emptyList(), listOf(Instance(0, 0, v, v, v, v, Vec3(2f, 2f, 2f), v)), emptyList(), false))
        fun face(z: Float, nz: Float, type: Int) = List(3) { listOf(0f, 0f, z, 0f, 0f, nz, 0f, type.toFloat()) }.flatten()
        val paint = FloatBuffer.wrap((face(2f, 1f, 11) + face(0f, -1f, 12)).toFloatArray())
        /** Layers holding [filament]. */
        fun with(filament: Int, settings: Map<String, String>, top: Int = 3, bottom: Int = 2): List<Int> {
            val layers = PaintLayers.forPlate(paint.duplicate(), cube(settings), 0, 0.2f, 0.2f, top, bottom)
            return layers.indices.filter { filament in layers[it] }
        }
        // Like shells: process shells (3 top, 2 bottom), then the object's own shell count.
        assertEquals(listOf(7, 8, 9), with(1, emptyMap()))
        assertEquals(listOf(0, 1), with(2, emptyMap()))
        assertEquals(listOf(5, 6, 7, 8, 9), with(1, mapOf("top_shell_layers" to "5")))
        // N layers, all the way through, and no depth from the process.
        assertEquals(listOf(8, 9), with(1, mapOf("mmu_top_paint_layers" to "2")))
        assertEquals((0..9).toList(), with(2, mapOf("mmu_bottom_paint_layers" to "-1")))
        assertEquals(listOf(9), with(1, emptyMap(), top = 0))
    }

    @Test fun spanEdgeExactlyOnAMidKeepsThatLayer() {
        // Mids for first = layer = 0.2 are 0.1, 0.3, ..., 1.3 (layer 6), 1.5 (layer 7). The slicer projects a facet
        // onto every layer whose slice_z lies in [min_z, max_z], endpoints included (`MultiMaterialSegmentation.cpp`
        // upper_bound over slice_z at :2119-2123, skip only when slice_z is outside at :2132), so a span ending
        // exactly on a mid keeps that layer. In Float, (1.3 - 0.2) / 0.2 + 0.5 lands just below 6 and (1.5 - 0.2) /
        // 0.2 + 0.5 just below 7, so an unguarded floor drops the layer.
        val ends = PaintLayers.layers(listOf(span(0, 0f, 2f), span(1, 0f, 1.3f)), 0.2f, 0.2f)
        assertEquals(listOf(0, 1), ends[6])
        assertEquals(listOf(0), ends[7])
        val ends15 = PaintLayers.layers(listOf(span(0, 0f, 2f), span(1, 0f, 1.5f)), 0.2f, 0.2f)
        assertEquals(listOf(0, 1), ends15[7])
        assertEquals(listOf(0), ends15[8])
        // A span starting exactly on a mid starts on that layer, not the next.
        val starts = PaintLayers.layers(listOf(span(0, 0f, 2f), span(1, 1.3f, 2f)), 0.2f, 0.2f)
        assertEquals(listOf(0), starts[5])
        assertEquals(listOf(0, 1), starts[6])
    }

    /** One paint-mesh triangle: 3 vertices x `x, y, z, nx, ny, nz, objCode, typeCode`. */
    private fun tri(verts: List<Triple<Float, Float, Float>>, nz: Float, type: Int): List<Float> =
        verts.flatMap { (x, y, z) -> listOf(x, y, z, 0f, 0f, nz, 0f, type.toFloat()) }

    /** A cube of XY size 2 x 2 and height [h] on plate 0, base filament 0 (no `extruder` setting). */
    private fun cube(h: Float) = listOf(
        SceneObject(0, "c", 12, emptyMap(), emptyList(),
            listOf(Instance(0, 0, Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f), Vec3(2f, 2f, h), Vec3(0f, 0f, 0f))),
            emptyList(), false),
    )

    /** The two triangles of a fully painted flat face of the 2 x 2 cube at height [z]; [x1] limits coverage in x. */
    private fun flatFace(z: Float, nz: Float, type: Int, x1: Float = 2f) =
        tri(listOf(Triple(0f, 0f, z), Triple(x1, 0f, z), Triple(x1, 2f, z)), nz, type) +
            tri(listOf(Triple(0f, 0f, z), Triple(x1, 2f, z), Triple(0f, 2f, z)), nz, type)

    @Test fun fullyPaintedTopFaceTakesTheTopLayerAlone() {
        // 2 mm cube (10 layers of 0.2), top face filament 1, paint depth = 3 shells. The slicer appends the top
        // face's projection un-inset to its own layer and only insets the projections below it
        // (`MultiMaterialSegmentation.cpp:1439-1445`), so on the top layer the face covers the whole slice and the
        // base filament has no region there; below, the inset core plus the base rim keep the layer mixed.
        val paint = FloatBuffer.wrap(flatFace(2f, 1f, 11).toFloatArray())
        val layers = PaintLayers.forPlate(paint, cube(2f), 0, 0.2f, 0.2f, topShells = 3, bottomShells = 0)
        assertEquals(listOf(1), layers[9])
        assertEquals(listOf(0, 1), layers[8])
        assertEquals(listOf(0, 1), layers[7])
        assertEquals(listOf(0), layers[6])
    }

    @Test fun fullyPaintedBottomFaceTakesTheBottomLayerAlone() {
        // Mirror image at the bottom: bottom face filament 2, depth = 2 shells (`MultiMaterialSegmentation.cpp:
        // 1459-1465` appends the bottom projection un-inset to its own layer).
        val paint = FloatBuffer.wrap(flatFace(0f, -1f, 12).toFloatArray())
        val layers = PaintLayers.forPlate(paint, cube(2f), 0, 0.2f, 0.2f, topShells = 0, bottomShells = 2)
        assertEquals(listOf(2), layers[0])
        assertEquals(listOf(0, 2), layers[1])
        assertEquals(listOf(0), layers[2])
    }

    @Test fun partiallyPaintedTopFaceKeepsTheBaseOnTheTopLayer() {
        // Only half the top face is painted (x 0..1 of 2): the other half still prints with the base filament,
        // so the top layer stays mixed.
        val paint = FloatBuffer.wrap(flatFace(2f, 1f, 11, x1 = 1f).toFloatArray())
        val layers = PaintLayers.forPlate(paint, cube(2f), 0, 0.2f, 0.2f, topShells = 3, bottomShells = 0)
        assertEquals(listOf(0, 1), layers[9])
    }

    @Test fun singleLayerObjectWithFullyPaintedTopHasNoBaseLayer() {
        // A one-layer object whose only layer is the fully painted top layer: the base span goes away entirely
        // instead of surviving through the no-mid fallback in `layers`.
        val paint = FloatBuffer.wrap(flatFace(0.2f, 1f, 11).toFloatArray())
        val layers = PaintLayers.forPlate(paint, cube(0.2f), 0, 0.2f, 0.2f, topShells = 1, bottomShells = 0)
        assertEquals(listOf(listOf(1)), layers)
    }

    @Test fun paintedTopCubeEstimateMatchesTheSlicedCount() {
        // The Task 16 plate in miniature: a 1.6 mm cube (8 layers of 0.2), sides and top painted filament 1,
        // paint depth = shell layers (3 here). Hand count of the old layer lists (base on all 8 layers, every
        // layer mixed): load 0 free, then one switch per layer = 8 changes. With the top layer paint-only the
        // loaded filament entering it is already filament 1 (layer 6 ends on it), so it costs no switch: 7.
        val side = tri(listOf(Triple(0f, 0f, 0f), Triple(2f, 0f, 0f), Triple(2f, 0f, 1.6f)), 0f, 11)
        val matrix = arrayOf(doubleArrayOf(0.0, 100.0), doubleArrayOf(200.0, 0.0))
        fun changes(topX: Float): Int {
            val paint = FloatBuffer.wrap((side + flatFace(1.6f, 1f, 11, x1 = topX)).toFloatArray())
            val layers = PaintLayers.forPlate(paint, cube(1.6f), 0, 0.2f, 0.2f, topShells = 3, bottomShells = 0)
            return ColourChangeEstimate.estimate(layers, matrix, 1.0, listOf(1.0, 1.0), null).changes
        }
        assertEquals(7, changes(2f))
        // The same cube with only half its top painted keeps the mixed top layer and the 8th change.
        assertEquals(8, changes(1f))
    }
}
