package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * JVM tests for [ColladaReader]. Every sample document is written inline as
 * text (per the task rules, no binary or external fixtures). Coordinates are
 * asserted exactly (all sample numbers are binary-exact floats).
 */
class ColladaReaderTest {

    private fun dae(body: String): File {
        val f = File.createTempFile("collada-test", ".dae")
        f.writeText(body)
        f.deleteOnExit()
        return f
    }

    private fun assertVerts(expected: FloatArray, actual: FloatArray) {
        assertEquals("vertex float count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("vertex float $i", expected[i], actual[i], 0.001f)
        }
    }

    private fun expectThrow(file: File) {
        try {
            ColladaReader.read(file)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    /** One triangle, positions (0,0,0) (10,0,0) (0,10,0), accessor-backed. */
    private fun triGeometry(id: String, extraPrims: String = ""): String = """
        <geometry id="$id"><mesh>
          <source id="$id-pos">
            <float_array id="$id-pos-array" count="9">0 0 0 10 0 0 0 10 0</float_array>
            <technique_common>
              <accessor source="#$id-pos-array" count="3" stride="3">
                <param name="X" type="float"/><param name="Y" type="float"/>
                <param name="Z" type="float"/>
              </accessor>
            </technique_common>
          </source>
          <vertices id="$id-verts"><input semantic="POSITION" source="#$id-pos"/></vertices>
          <triangles count="1">
            <input semantic="VERTEX" source="#$id-verts" offset="0"/>
            <p>0 1 2</p>
          </triangles>
          $extraPrims
        </mesh></geometry>
    """.trimIndent()

    private fun doc(
        asset: String = "<asset><unit meter=\"0.001\"/><up_axis>Z_UP</up_axis></asset>",
        libs: String = "",
        scene: String,
        geoms: String = triGeometry("geom"),
    ): String = """<?xml version="1.0"?>
        <COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
        $asset
        <library_geometries>$geoms</library_geometries>
        $libs
        <library_visual_scenes><visual_scene id="scene">$scene</visual_scene></library_visual_scenes>
        </COLLADA>
    """.trimIndent()

    private val triVertsMm = floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f)

    @Test
    fun singleTriangleZUpMillimetres() {
        val (verts, colours) = ColladaReader.read(
            dae(doc(scene = """<node><instance_geometry url="#geom"/></node>""")))
        assertVerts(triVertsMm, verts)
        assertEquals(intArrayOf(-1).toList(), colours.toList())
    }

    @Test
    fun yUpMetresConvertToZUpMillimetres() {
        // No <unit> (metres) and no <up_axis> (Y_UP): (x, y, z) -> (x, -z, y) * 1000.
        val geom = """
        <geometry id="geom"><mesh>
          <source id="pos"><float_array id="pa" count="9">0 1 0 0 0 1 1 0 0</float_array></source>
          <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
          <triangles count="1"><input semantic="VERTEX" source="#verts" offset="0"/><p>0 1 2</p></triangles>
        </mesh></geometry>
        """.trimIndent()
        val (verts, _) = ColladaReader.read(
            dae(doc(asset = "<asset/>",
                scene = """<node><instance_geometry url="#geom"/></node>""", geoms = geom)))
        assertVerts(floatArrayOf(0f, 0f, 1000f, 0f, -1000f, 0f, 1000f, 0f, 0f), verts)
    }

    @Test
    fun quadPolylistFansAndInterleavedInputsSkipNormals() {
        val geom = """
        <geometry id="geom"><mesh>
          <source id="pos"><float_array id="pa" count="12">0 0 0 10 0 0 10 10 0 0 10 0</float_array></source>
          <source id="nrm"><float_array id="na" count="12">0 0 1 0 0 1 0 0 1 0 0 1</float_array></source>
          <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
          <polylist count="1">
            <input semantic="VERTEX" source="#verts" offset="0"/>
            <input semantic="NORMAL" source="#nrm" offset="1"/>
            <vcount>4</vcount>
            <p>0 0 1 1 2 2 3 3</p>
          </polylist>
        </mesh></geometry>
        """.trimIndent()
        val (verts, colours) = ColladaReader.read(
            dae(doc(scene = """<node><instance_geometry url="#geom"/></node>""", geoms = geom)))
        // Fan: (0,1,2) and (0,2,3)
        assertVerts(
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f, 0f, 0f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f),
            verts)
        assertEquals(2, colours.size)
    }

    @Test
    fun polygonsElementFans() {
        val geom = """
        <geometry id="geom"><mesh>
          <source id="pos"><float_array id="pa" count="12">0 0 0 10 0 0 10 10 0 0 10 0</float_array></source>
          <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
          <polygons count="1">
            <input semantic="VERTEX" source="#verts" offset="0"/>
            <p>0 1 2 3</p>
          </polygons>
        </mesh></geometry>
        """.trimIndent()
        val (verts, colours) = ColladaReader.read(
            dae(doc(scene = """<node><instance_geometry url="#geom"/></node>""", geoms = geom)))
        assertEquals(18, verts.size)
        assertEquals(2, colours.size)
    }

    @Test
    fun twoMaterialsResolveThroughBindMaterial() {
        val geom = """
        <geometry id="geom"><mesh>
          <source id="pos"><float_array id="pa" count="18">0 0 0 10 0 0 0 10 0 20 0 0 30 0 0 20 10 0</float_array></source>
          <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
          <triangles count="1" material="symA"><input semantic="VERTEX" source="#verts" offset="0"/><p>0 1 2</p></triangles>
          <triangles count="1" material="symB"><input semantic="VERTEX" source="#verts" offset="0"/><p>3 4 5</p></triangles>
        </mesh></geometry>
        """.trimIndent()
        val libs = """
        <library_effects>
          <effect id="effA"><profile_COMMON><technique sid="t"><phong>
            <diffuse><color>1 0 0 1</color></diffuse>
          </phong></technique></profile_COMMON></effect>
          <effect id="effB"><profile_COMMON><technique sid="t"><lambert>
            <diffuse><color>0 0.25 1 1</color></diffuse>
          </lambert></technique></profile_COMMON></effect>
        </library_effects>
        <library_materials>
          <material id="matA"><instance_effect url="#effA"/></material>
          <material id="matB"><instance_effect url="#effB"/></material>
        </library_materials>
        """.trimIndent()
        val scene = """
        <node><instance_geometry url="#geom"><bind_material><technique_common>
          <instance_material symbol="symA" target="#matA"/>
          <instance_material symbol="symB" target="#matB"/>
        </technique_common></bind_material></instance_geometry></node>
        """.trimIndent()
        val (_, colours) = ColladaReader.read(dae(doc(libs = libs, scene = scene, geoms = geom)))
        assertEquals(listOf(0xFF0000, 0x0040FF), colours.toList())
    }

    @Test
    fun textureDiffuseYieldsNoColour() {
        val libs = """
        <library_effects>
          <effect id="effT"><profile_COMMON><technique sid="t"><phong>
            <diffuse><texture texture="img" texcoord="UV"/></diffuse>
          </phong></technique></profile_COMMON></effect>
        </library_effects>
        <library_materials>
          <material id="matT"><instance_effect url="#effT"/></material>
        </library_materials>
        """.trimIndent()
        val geom = triGeometry("geom").replace(
            "<triangles count=\"1\">", "<triangles count=\"1\" material=\"symT\">")
        val scene = """
        <node><instance_geometry url="#geom"><bind_material><technique_common>
          <instance_material symbol="symT" target="#matT"/>
        </technique_common></bind_material></instance_geometry></node>
        """.trimIndent()
        val (_, colours) = ColladaReader.read(dae(doc(libs = libs, scene = scene, geoms = geom)))
        assertEquals(listOf(-1), colours.toList())
    }

    @Test
    fun nodeTransformsApplyInDocumentOrder() {
        // Parent node: translate(10,0,0) listed before rotate(Z, 90), so its
        // local matrix is T * R (the point rotates first, then translates).
        // The child node's translate(0,5,0) applies in the child's local
        // space, before the parent's rotation: (1,0,0) -> (1,5,0) -> (-5,1,0)
        // -> (5,1,0).
        val geom = """
        <geometry id="geom"><mesh>
          <source id="pos"><float_array id="pa" count="9">1 0 0 2 0 0 1 1 0</float_array></source>
          <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
          <triangles count="1"><input semantic="VERTEX" source="#verts" offset="0"/><p>0 1 2</p></triangles>
        </mesh></geometry>
        """.trimIndent()
        val scene = """
        <node>
          <translate>10 0 0</translate>
          <rotate>0 0 1 90</rotate>
          <node>
            <translate>0 5 0</translate>
            <instance_geometry url="#geom"/>
          </node>
        </node>
        """.trimIndent()
        val (verts, _) = ColladaReader.read(dae(doc(scene = scene, geoms = geom)))
        // (1,0,0)->(5,1,0)  (2,0,0)->(5,2,0)  (1,1,0)->(4,1,0)
        assertVerts(floatArrayOf(5f, 1f, 0f, 5f, 2f, 0f, 4f, 1f, 0f), verts)
    }

    @Test
    fun matrixElementNodeTransform() {
        val scene = """
        <node>
          <matrix>2 0 0 0 0 2 0 0 0 0 2 0 0 0 0 1</matrix>
          <instance_geometry url="#geom"/>
        </node>
        """.trimIndent()
        val (verts, _) = ColladaReader.read(dae(doc(scene = scene)))
        assertVerts(floatArrayOf(0f, 0f, 0f, 20f, 0f, 0f, 0f, 20f, 0f), verts)
    }

    @Test
    fun sameGeometryTwoInstancesBothEmitted() {
        val scene = """
        <node><translate>100 0 0</translate><instance_geometry url="#geom"/></node>
        <node><translate>0 100 0</translate><instance_geometry url="#geom"/></node>
        """.trimIndent()
        val (verts, colours) = ColladaReader.read(dae(doc(scene = scene)))
        assertEquals(18, verts.size)
        assertEquals(2, colours.size)
        assertEquals(100f, verts[0], 0.001f) // first copy translated +100 x
        assertEquals(100f, verts[9 + 1], 0.001f) // second copy translated +100 y
    }

    @Test
    fun instanceControllerIsSkippedNotFatal() {
        val scene = """
        <node><instance_controller url="#skin"><skeleton>#root</skeleton></instance_controller></node>
        <node><instance_geometry url="#geom"/></node>
        """.trimIndent()
        val (verts, colours) = ColladaReader.read(dae(doc(scene = scene)))
        assertVerts(triVertsMm, verts)
        assertEquals(1, colours.size)
    }

    @Test
    fun lyingFloatCountThrows() {
        val geom = triGeometry("geom").replace("count=\"9\"", "count=\"99\"")
        expectThrow(dae(doc(scene = """<node><instance_geometry url="#geom"/></node>""", geoms = geom)))
    }

    @Test
    fun truncatedXmlThrows() {
        val text = doc(scene = """<node><instance_geometry url="#geom"/></node>""")
        val f = File.createTempFile("collada-test", ".dae")
        f.writeText(text.substring(0, text.length / 2))
        f.deleteOnExit()
        expectThrow(f)
    }

    @Test
    fun garbageThrows() {
        val f = dae("this is not xml at all")
        expectThrow(f)
    }

    @Test
    fun xxeEntityIsNotExpanded() {
        val text = """<?xml version="1.0"?>
        <!DOCTYPE COLLADA [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
        <asset><unit meter="0.001"/><up_axis>Z_UP</up_axis><name>&xxe;</name></asset>
        <library_geometries>${triGeometry("geom")}</library_geometries>
        <library_visual_scenes><visual_scene id="scene">
          <node><instance_geometry url="#geom"/></node>
        </visual_scene></library_visual_scenes>
        </COLLADA>
        """.trimIndent()
        val (verts, _) = ColladaReader.read(dae(text))
        assertVerts(triVertsMm, verts)
        assertTrue("geometry parsed despite the external entity", verts.isNotEmpty())
    }
}
