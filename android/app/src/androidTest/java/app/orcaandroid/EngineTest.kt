package app.orcaandroid

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orcaandroid.core.Engine
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.core.LayerGcode
import app.orcaandroid.core.LayerRange
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.PrinterSetup
import app.orcaandroid.core.Scene
import app.orcaandroid.core.SliceResult
import app.orcaandroid.core.Vec3
import app.orcaandroid.core.VolumeType
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * End-to-end tests of the native slicing engine on a device/emulator: printers of several
 * vendors, the option matrix of the process settings, scene tools, multi-material, calibration,
 * projects and presets. Run with `./gradlew connectedDebugAndroidTest`.
 *
 * The option matrix tests collect all failures and report them together, so one run shows every
 * broken combination instead of stopping at the first.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineTest {

    companion object {
        private const val TAG = "EngineTest"
        const val QIDI = "Qidi Q1 Pro 0.4 nozzle"
        const val A1 = "Bambu Lab A1 0.4 nozzle"
        const val X1C = "Bambu Lab X1 Carbon 0.4 nozzle"
        val PRINTERS = listOf(
            QIDI, A1, X1C, "Bambu Lab P1S 0.4 nozzle", "Prusa MK4 0.4 nozzle", "Prusa MINI 0.4 nozzle",
            "Voron 2.4 350 0.4 nozzle", "Creality Ender-3 V3 SE 0.4 nozzle", "Creality K1C 0.4 nozzle",
        )

        lateinit var engine: Engine
        lateinit var app: OrcaApp
        lateinit var models: File

        @BeforeClass
        @JvmStatic
        fun setUp() = runBlocking {
            app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OrcaApp
            val c = app.container
            c.resources.prepare()
            c.resources.setInstalledVendors(setOf("BBL", "Qidi", "Prusa", "Voron", "Creality"))
            engine = c.engine
            engine.init(c.resources.resourcesDir, c.resources.dataDir)
            engine.loadPresets()
            models = File(c.resources.resourcesDir, "handy_models")
        }
    }

    // --- Helpers -------------------------------------------------------------------------------------

    private val colors = listOf("#FF7F27", "#2F7FEF", "#2FBF4F", "#EF3F3F")

    /** Selects [printer] with its default process and [filaments] default filament slots. */
    private suspend fun use(printer: String, overrides: Map<String, String> = emptyMap(), filaments: Int = 1): PrinterSetup {
        val setup = engine.selectPrinter(printer)
        engine.setSelection(setup.defaultPrint, List(filaments) { FilamentSlot(setup.defaultFilament, colors[it]) }, overrides)
        return setup
    }

    /** Removes all objects and extra plates (and ends a calibration). */
    private suspend fun clear(): Scene {
        var s = engine.calibStop()
        for (i in s.objects.indices.reversed()) s = engine.deleteObject(i)
        while (s.plates.size > 1) s = engine.deletePlate(s.plates.lastIndex)
        return s
    }

    private suspend fun cube(size: Float = 12f, plate: Int = 0) = engine.addPrimitive("cube", Vec3(size, size, size), plate)

    private suspend fun slice(plate: Int = 0): Pair<SliceResult, String> {
        val r = engine.slice(plate) { _, _ -> }
        assertTrue("no layers", r.layers.isNotEmpty())
        assertTrue("no print time", r.printTimeSeconds > 0)
        val gcode = File(r.gcodeFile).readText()
        assertTrue("G-code without moves", gcode.contains("G1 "))
        return r to gcode
    }

    /** Runs [block] for every case, logging and collecting failures instead of stopping. */
    private fun <T> matrix(name: String, cases: List<T>, block: suspend (T) -> Unit) = runBlocking {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val t0 = System.currentTimeMillis()
            try {
                block(case)
                Log.i(TAG, "$name[$case] ok (${System.currentTimeMillis() - t0} ms)")
            } catch (e: Throwable) {
                Log.e(TAG, "$name[$case] FAILED: ${e.message}", e)
                failures += "$case: ${e.message}"
            }
        }
        assertTrue("$name failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Runs one step of a test, naming it in the failure. */
    private suspend fun <T> step(name: String, block: suspend () -> T): T {
        Log.i(TAG, "step $name")
        return try { block() } catch (e: Throwable) { throw AssertionError("step '$name': ${e.message}", e) }
    }

    private suspend fun enumValues(key: String) =
        engine.optionDefs(PresetType.PRINT).first { it.key == key }.enumValues

    // --- Presets and options -------------------------------------------------------------------------

    @Test
    fun a01_printersAndPresets() = runBlocking<Unit> {
        val names = engine.printerList().map { it.name }
        for (p in PRINTERS) assertTrue("missing printer $p", p in names)
        val setup = use(QIDI)
        assertTrue(setup.prints.isNotEmpty() && setup.filaments.isNotEmpty())
        assertTrue(setup.bed.size >= 6)
        for (type in PresetType.entries) assertTrue(engine.optionDefs(type).size > 50)
        val values = engine.presetValues(PresetType.PRINT, setup.defaultPrint)
        assertTrue(values.containsKey("layer_height"))
    }

    @Test
    fun a02_optionStatesFollowDesktopRules() = runBlocking<Unit> {
        use(QIDI, mapOf("enable_support" to "0"))
        val off = engine.optionStates()
        assertTrue("support_type should be greyed out without support", "support_type" in off.disabled)
        use(QIDI, mapOf("enable_support" to "1"))
        assertTrue("support_type should be editable with support", "support_type" !in engine.optionStates().disabled)
    }

    @Test
    fun a03_userPresets() = runBlocking<Unit> {
        val setup = use(QIDI)
        val name = "Engine test print"
        val saved = engine.savePreset(PresetType.PRINT, setup.defaultPrint, name, mapOf("layer_height" to "0.16"))
        assertTrue(saved.prints.any { it.name == name && !it.system })
        assertEquals("0.16", engine.presetValues(PresetType.PRINT, name)["layer_height"])
        val file = File(engine.presetFile(PresetType.PRINT, name))
        assertTrue(file.isFile)
        val copy = File(app.cacheDir, "preset_copy.json").also { file.copyTo(it, overwrite = true) }
        val afterDelete = engine.deletePreset(PresetType.PRINT, name)
        assertTrue(afterDelete.prints.none { it.name == name })
        val (_, imported) = engine.importPresets(listOf(copy.path))
        assertTrue("re-import", imported.prints.any { it.name == name })
        engine.deletePreset(PresetType.PRINT, name)
    }

    // --- Printers --------------------------------------------------------------------------------------

    @Test
    fun b01_defaultSliceOnEveryPrinter() = matrix("printer", PRINTERS) { printer ->
        use(printer)
        clear()
        cube(20f)
        val (r, gcode) = slice()
        assertTrue(r.filamentGrams > 0)
        assertTrue("$printer: no start G-code", gcode.lines().size > 200)
    }

    // --- Process option matrix (small cube, coarse layers to keep it fast) ------------------------------

    private suspend fun sliceCubeWith(overrides: Map<String, String>, model: suspend () -> Unit = { cube() }): String {
        use(QIDI, mapOf("layer_height" to "0.3", "initial_layer_print_height" to "0.3") + overrides)
        clear()
        model()
        return slice().second
    }

    @Test
    fun b02_sparseInfillPatterns() = runBlocking<Unit> {
        val values = enumValues("sparse_infill_pattern")
        matrix("sparse_infill_pattern", values) { v ->
            sliceCubeWith(mapOf("sparse_infill_pattern" to v, "sparse_infill_density" to "25%"))
        }
    }

    @Test
    fun b03_surfacePatterns() = runBlocking<Unit> {
        val cases = enumValues("top_surface_pattern").map { "top_surface_pattern" to it } +
            enumValues("bottom_surface_pattern").map { "bottom_surface_pattern" to it } +
            enumValues("internal_solid_infill_pattern").map { "internal_solid_infill_pattern" to it }
        matrix("surface pattern", cases) { (k, v) -> sliceCubeWith(mapOf(k to v)) }
    }

    @Test
    fun b04_wallsAndSeams() = runBlocking<Unit> {
        val cases = enumValues("wall_generator").flatMap { g -> enumValues("wall_sequence").map { g to it } }
        matrix("walls", cases) { (g, seq) -> sliceCubeWith(mapOf("wall_generator" to g, "wall_sequence" to seq, "wall_loops" to "3")) }
        matrix("seam_position", enumValues("seam_position")) { v -> sliceCubeWith(mapOf("seam_position" to v)) }
        matrix("seam options", listOf(mapOf("seam_slope_type" to "all"), mapOf("seam_gap" to "15%"), mapOf("wipe_on_loops" to "1"))) {
            sliceCubeWith(it)
        }
    }

    @Test
    fun b05_supports() = runBlocking<Unit> {
        val sphere: suspend () -> Unit = { engine.addPrimitive("sphere", Vec3(20f, 20f, 20f), 0) }
        // Styles offered per support type in the desktop (the others are hidden there).
        val styles = mapOf(
            "normal(auto)" to listOf("default", "grid", "snug"),
            "tree(auto)" to listOf("tree_slim", "tree_strong", "tree_hybrid"),
        )
        val cases = styles.flatMap { (type, list) -> list.map { type to it } }
        matrix("support", cases) { (type, style) ->
            val gcode = sliceCubeWith(mapOf("enable_support" to "1", "support_type" to type, "support_style" to style), sphere)
            assertTrue("no support extrusions", gcode.contains(";TYPE:Support"))
        }
        // Manual support only prints where enforcers are painted.
        matrix("manual support", enumValues("support_type").filter { it.contains("manual") }) { type ->
            val gcode = sliceCubeWith(mapOf("enable_support" to "1", "support_type" to type), sphere)
            assertTrue("support without enforcers", !gcode.contains(";TYPE:Support"))
        }
        // Organic trees on a model with real overhangs.
        matrix("organic on Benchy", listOf("default", "organic")) { style ->
            use(QIDI, mapOf("enable_support" to "1", "support_type" to "tree(auto)", "support_style" to style, "layer_height" to "0.3"))
            clear()
            engine.loadModels(listOf(File(models, "3DBenchy.drc").path), false, 0)
            assertTrue("no organic support", slice().second.contains(";TYPE:Support"))
        }
        matrix("support options", listOf(
            mapOf("support_on_build_plate_only" to "1"), mapOf("support_interface_top_layers" to "0"),
            mapOf("support_base_pattern" to "honeycomb"), mapOf("raft_layers" to "2"),
        )) { sliceCubeWith(mapOf("enable_support" to "1") + it, sphere) }
    }

    @Test
    fun b06_brimSkirtIroningFuzzy() = runBlocking<Unit> {
        matrix("brim_type", enumValues("brim_type")) { v ->
            val gcode = sliceCubeWith(mapOf("brim_type" to v, "brim_width" to "5"))
            // auto: only if needed, painted: only where painted, inner_only: only in holes.
            if (v == "outer_only" || v == "outer_and_inner") assertTrue("no brim", gcode.contains(";TYPE:Brim"))
        }
        matrix("skirt", listOf("1", "3")) { loops ->
            assertTrue(sliceCubeWith(mapOf("skirt_loops" to loops, "skirt_distance" to "3")).contains(";TYPE:Skirt"))
        }
        matrix("ironing_type", enumValues("ironing_type").filter { it != "no ironing" }) { v ->
            assertTrue("no ironing", sliceCubeWith(mapOf("ironing_type" to v)).contains(";TYPE:Ironing"))
        }
        matrix("fuzzy_skin", enumValues("fuzzy_skin")) { v -> sliceCubeWith(mapOf("fuzzy_skin" to v)) }
    }

    @Test
    fun b07_specialModes() = runBlocking<Unit> {
        val cylinder: suspend () -> Unit = { engine.addPrimitive("cylinder", Vec3(20f, 20f, 15f), 0) }
        matrix("vase", listOf(Unit)) { sliceCubeWith(mapOf("spiral_mode" to "1"), cylinder) }
        matrix("arc fitting", listOf(Unit)) {
            val gcode = sliceCubeWith(mapOf("enable_arc_fitting" to "1"), cylinder)
            assertTrue("no arcs", gcode.contains("\nG2 ") || gcode.contains("\nG3 "))
        }
        matrix("misc", listOf(
            mapOf("detect_thin_wall" to "1"), mapOf("only_one_wall_top" to "1"), mapOf("reduce_crossing_wall" to "1"),
            mapOf("enable_overhang_speed" to "0"), mapOf("slowdown_for_curled_perimeters" to "1"),
            mapOf("infill_combination" to "1"), mapOf("ensure_vertical_shell_thickness" to "none"),
            mapOf("xy_hole_compensation" to "0.1", "xy_contour_compensation" to "-0.05"), mapOf("elefant_foot_compensation" to "0.2"),
            mapOf("precise_outer_wall" to "1"), mapOf("gcode_label_objects" to "0"), mapOf("gcode_comments" to "1"),
        )) { sliceCubeWith(it) }
        matrix("sequential", listOf(Unit)) {
            sliceCubeWith(mapOf("print_sequence" to "by object")) { cube(10f); cube(10f) }
        }
    }

    @Test
    fun b08_layerHeights() = runBlocking<Unit> {
        val counts = listOf("0.12", "0.2", "0.28").associateWith { h ->
            use(QIDI, mapOf("layer_height" to h))
            clear()
            cube(12f)
            slice().first.layers.size
        }
        Log.i(TAG, "layer counts $counts")
        assertTrue(counts.getValue("0.12") > counts.getValue("0.2") && counts.getValue("0.2") > counts.getValue("0.28"))
    }

    // --- Scene tools -----------------------------------------------------------------------------------

    @Test
    fun c01_sceneTools() = runBlocking<Unit> {
        use(QIDI)
        clear()
        var s = engine.loadModels(listOf(File(models, "3DBenchy.drc").path), false, 0)
        assertEquals(1, s.objects.size)
        s = engine.duplicate(0, 2)
        assertEquals(3, s.objects[0].instances.size)
        s = engine.arrange(0)
        val offsets = s.objects[0].instances.map { it.offset.x to it.offset.y }.toSet()
        assertEquals("arranged copies overlap", 3, offsets.size)
        assertTrue("copies left the plate", s.objects[0].instances.all { it.plate == 0 })
        s = engine.deleteInstance(0, 2)
        s = engine.autoOrient(0)
        s = engine.setTransform(0, 0, rotation = Vec3(0f, 0f, 45f), scale = Vec3(1.2f, 1.2f, 1.2f))
        assertEquals(45f, s.objects[0].instances[0].rotation.z, 0.5f)
        val tris = s.objects[0].triangles
        s = engine.simplify(0, 0.5f)
        assertTrue("simplify", s.objects[0].triangles < tris)
        s = engine.repair(0)
        s = engine.undo(); s = engine.undo()
        assertEquals("undo simplify/repair", tris, s.objects[0].triangles)
        s = engine.redo()
        // Cut the first instance through its middle.
        val inst = s.objects[0].instances[0]
        s = engine.cut(0, 0, inst.min.z + inst.size.z / 2, keepUpper = true, keepLower = true, flipUpper = false)
        assertTrue("cut produced ${s.objects.size} objects", s.objects.size >= 2)
        assertTrue("cut parts not on the plate", s.objects.all { o -> o.instances.all { it.plate == 0 && it.min.z >= -0.01f } })
        slice()
        // Lay on face: tilt a cube and put it back on its side.
        clear()
        s = cube(15f)
        s = engine.setTransform(0, 0, rotation = Vec3(30f, 0f, 0f))
        val tilted = s.objects[0].instances[0].let { it.min + it.size * 0.5f }
        val face = engine.pick(Vec3(tilted.x, tilted.y + 3f, 100f), Vec3(0f, 0f, -1f))!!
        s = engine.layOnFace(0, 0, face.normal)
        assertEquals("lay on face", 15f, s.objects[0].instances[0].size.z, 0.2f)
        // Split a text into its letters.
        clear()
        s = engine.addText("Orca", app.container.resources.defaultFont(), 10f, 3f, 0)
        s = engine.split(0, toParts = false)
        assertTrue("split text into ${s.objects.size}", s.objects.size >= 4)
    }

    @Test
    fun c02_primitivesTextSvg() = runBlocking<Unit> {
        use(QIDI)
        clear()
        for (shape in listOf("cube", "cylinder", "sphere", "cone")) engine.addPrimitive(shape, Vec3(15f, 15f, 15f), 0)
        engine.addText("Test 123", app.container.resources.defaultFont(), 8f, 2f, 0)
        val svg = File(app.cacheDir, "test.svg").apply {
            writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"><path d="M5 5 L35 5 L20 35 Z" fill="black"/><circle cx="20" cy="15" r="5" fill="white"/></svg>""")
        }
        val s = engine.addSvg(svg.path, 30f, 2f, 0)
        assertEquals(6, s.objects.size)
        assertTrue(s.objects.all { o -> o.instances.all { it.plate == 0 } })
        slice()
    }

    @Test
    fun c03_volumesAndPerObjectSettings() = runBlocking<Unit> {
        use(QIDI)
        clear()
        cube(25f)
        step("modifier") { engine.addVolume(0, VolumeType.MODIFIER, "box", Vec3(10f, 10f, 10f)) }
        var s = step("negative") { engine.addVolume(0, VolumeType.NEGATIVE, "box", Vec3(6f, 6f, 30f)) }
        step("blocker") { engine.addVolume(0, VolumeType.SUPPORT_BLOCKER, "box", Vec3(5f, 5f, 5f)) }
        s = step("enforcer") { engine.addVolume(0, VolumeType.SUPPORT_ENFORCER, "box", Vec3(5f, 5f, 5f)) }
        assertEquals(5, s.objects[0].volumes.size)
        val modifier = s.objects[0].volumes.indexOfFirst { it.type == VolumeType.MODIFIER }
        s = step("modifier setting") { engine.setObjectSetting(0, modifier, "sparse_infill_density", "80%") }
        s = step("object setting") { engine.setObjectSetting(0, -1, "wall_loops", "5") }
        assertEquals("5", s.objects[0].settings["wall_loops"])
        s = step("layer range") { engine.setLayerRanges(0, listOf(LayerRange(5f, 10f, mapOf("sparse_infill_pattern" to "gyroid", "sparse_infill_density" to "40%")))) }
        assertEquals(1, s.objects[0].layerRanges.size)
        step("slice") { slice() }
        s = step("delete volume") { engine.deleteVolume(0, s.objects[0].volumes.lastIndex) }
        assertEquals(4, s.objects[0].volumes.size)
        s = step("reset setting") { engine.setObjectSetting(0, -1, "wall_loops", null) }
        assertTrue("wall_loops" !in s.objects[0].settings)
    }

    @Test
    fun c04_paintingAndVariableLayerHeight() = runBlocking<Unit> {
        use(QIDI, mapOf("enable_support" to "1", "support_type" to "normal(manual)"))
        clear()
        var s = engine.addPrimitive("sphere", Vec3(20f, 20f, 20f), 0)
        val c = s.objects[0].instances[0].let { it.min + it.size * 0.5f }
        // Paint support under the sphere from below, a seam and fuzzy skin from the side.
        val below = engine.pick(Vec3(c.x, c.y, -50f), Vec3(0f, 0f, 1f))!!
        s = engine.paint(below, Vec3(c.x, c.y, -50f), 6f, "support", 1, true)
        val side = engine.pick(Vec3(c.x - 50f, c.y, c.z), Vec3(1f, 0f, 0f))!!
        s = engine.paint(side, Vec3(c.x - 50f, c.y, c.z), 3f, "seam", 1, true)
        s = engine.paint(side, Vec3(c.x - 50f, c.y, c.z), 5f, "fuzzy", 1, true)
        assertTrue("paint mesh written", File(s.paintFile).length() > 0)
        assertTrue("manual support painted", slice().second.contains(";TYPE:Support"))
        s = engine.paintClear(0, "support")
        // Variable layer height on the Benchy.
        use(QIDI)
        clear()
        engine.loadModels(listOf(File(models, "3DBenchy.drc").path), false, 0)
        val base = slice().first.layers.size
        val (_, adaptive) = engine.layerAdaptive(0, 0.2f)
        assertTrue(adaptive.profile.isNotEmpty())
        val adaptiveLayers = slice().first.layers.size
        assertNotEquals("adaptive layer height had no effect", base, adaptiveLayers)
        engine.layerSmooth(0, 5, true)
        engine.layerAdjust(0, 10f, 0.05f, 4f)
        engine.layerReset(0)
        assertEquals("reset layer height", base, slice().first.layers.size)
    }

    // --- Multi-material, plates, custom G-code ---------------------------------------------------------

    @Test
    fun d01_multiMaterial() = runBlocking<Unit> {
        use(X1C, filaments = 2)
        clear()
        cube(15f)
        var s = cube(15f)
        s = engine.setObjectSetting(1, -1, "extruder", "2")
        assertEquals(4, engine.flushMatrix().size)
        s = engine.setWipeTower(0, 30f to 200f)
        assertEquals(30f, s.plates[0].wipeTower!!.first, 0.1f)
        val (r, gcode) = slice()
        assertTrue("no tool change", gcode.contains("T1"))
        assertTrue("no prime tower", gcode.contains("; FEATURE: Prime tower") || gcode.contains(";TYPE:Prime tower") || r.roles.any { it.role == 17 })
        assertEquals("filament use per slot", 2, r.filamentUse.count { it.first > 0f })
        // Colour painting: second filament on half a sphere.
        clear()
        s = engine.addPrimitive("sphere", Vec3(25f, 25f, 25f), 0)
        val c = s.objects[0].instances[0].let { it.min + it.size * 0.5f }
        val hit = engine.pick(Vec3(c.x, c.y, 100f), Vec3(0f, 0f, -1f))!!
        engine.paint(hit, Vec3(c.x, c.y, 100f), 10f, "color", 2, true)
        assertTrue("painted colour not printed", slice().second.contains("T1"))
    }

    @Test
    fun d02_layerGcodes() = runBlocking<Unit> {
        use(QIDI, filaments = 2)
        clear()
        cube(20f)
        val values = engine.presetValues(PresetType.PRINTER, QIDI)
        engine.setLayerGcodes(0, listOf(
            LayerGcode(5f, "pause"),
            LayerGcode(10f, "color", color = "#2F7FEF", extruder = 2),
            LayerGcode(15f, "custom", extra = "M117 Orca-Android test"),
        ))
        val gcode = slice().second
        assertTrue("custom G-code", gcode.contains("M117 Orca-Android test"))
        val pause = values["machine_pause_gcode"].orEmpty().trim('"').lineSequence().firstOrNull { it.isNotBlank() }
        if (!pause.isNullOrBlank()) assertTrue("pause G-code '$pause'", gcode.contains(pause))
        // A colour change is a change to filament 2 (T1), running the printer's filament change G-code.
        assertTrue("colour change", gcode.contains("\nT1"))
        engine.setLayerGcodes(0, emptyList())
    }

    @Test
    fun d03_platesAndBedTypes() = runBlocking<Unit> {
        val setup = use(A1)
        clear()
        assertTrue("A1 has plate types", setup.bedTypes.isNotEmpty())
        cube(15f)
        var s = engine.addPlate()
        assertEquals(2, s.plates.size)
        s = engine.loadModels(listOf(File(models, "calicat.drc").path), true, 1)
        assertTrue(s.objectsOn(1).isNotEmpty())
        val textured = setup.bedTypes.firstOrNull { it.contains("Textured", true) } ?: setup.bedTypes.last()
        s = engine.setPlateBedType(1, textured)
        assertEquals(textured, s.plates[1].bedType)
        val (r1, g1) = slice(0)
        val (r2, g2) = slice(1)
        assertNotEquals(r1.gcodeFile, r2.gcodeFile)
        assertNotEquals(g1.length, g2.length)
        s = engine.deletePlate(1)
        assertEquals(1, s.plates.size)
    }

    // --- Calibration -----------------------------------------------------------------------------------

    @Test
    fun e01_calibrations() {
        val cases = listOf<Pair<String, Map<String, Any?>>>(
            "temp" to mapOf("start" to 230, "end" to 200, "step" to 5),
            "flow" to mapOf("pass" to 1), "flow" to mapOf("pass" to 2), "flow" to mapOf("linear" to true, "pass" to 1),
            "pa_line" to mapOf("start" to 0.0, "end" to 0.08, "step" to 0.005),
            "pa_tower" to mapOf("start" to 0.0, "end" to 0.1, "step" to 0.002),
            "retraction" to mapOf("start" to 0.0, "end" to 2.0, "step" to 0.1),
            "max_volumetric" to mapOf("start" to 5, "end" to 20, "step" to 0.5),
            "vfa" to mapOf("start" to 40, "end" to 200, "step" to 10),
            // Frequency test: frequency range per axis at a fixed damping (start); damping test: damping
            // range at a fixed frequency – as the desktop dialogs fill Calib_Params.
            "input_shaping_freq" to mapOf("start" to 0.15, "end" to 0.15, "freq_start_x" to 15, "freq_end_x" to 110,
                "freq_start_y" to 15, "freq_end_y" to 110, "model" to 0),
            "input_shaping_damp" to mapOf("start" to 0.0, "end" to 0.4, "freq_start_x" to 30, "freq_end_x" to 30,
                "freq_start_y" to 30, "freq_end_y" to 30, "model" to 1),
            "cornering" to mapOf("start" to 1, "end" to 15, "model" to 0),
        )
        matrix("calibration", cases) { (type, params) ->
            use(QIDI)
            clear()
            val (scene, calib) = engine.calibStart(type, params)
            assertTrue("$type: no model", scene.objects.isNotEmpty())
            assertEquals(type, calib.type)
            val gcode = slice().second
            when (type) {
                "pa_tower" -> assertTrue("no PA changes", gcode.contains("SET_PRESSURE_ADVANCE") || gcode.contains("M900"))
                "temp" -> assertTrue("no temperature steps", gcode.contains("M104 S205"))
            }
            engine.calibStop()
        }
        runBlocking {
            use(A1)
            clear()
            engine.calibStart("pa_line", mapOf("start" to 0.0, "end" to 0.05, "step" to 0.005))
            slice()
            engine.calibStop()
        }
    }

    // --- Files -----------------------------------------------------------------------------------------

    @Test
    fun f01_projectRoundTrip() = runBlocking<Unit> {
        use(QIDI, filaments = 2)
        clear()
        engine.loadModels(listOf(File(models, "calicat.drc").path), false, 0)
        cube(10f)
        engine.setObjectSetting(1, -1, "wall_loops", "4")
        engine.setObjectSetting(1, -1, "extruder", "2")
        engine.addPlate()
        cube(10f, plate = 1)
        engine.setLayerGcodes(0, listOf(LayerGcode(3f, "pause")))
        val project = File(app.cacheDir, "roundtrip.3mf")
        engine.saveProject(project.path)
        assertTrue(project.length() > 0)
        clear()
        val (s, info) = engine.loadProject(project.path)
        assertEquals(3, s.objects.size)
        assertEquals(2, s.plates.size)
        assertEquals("4", s.objects.first { it.name.startsWith("Cube") || it.name.contains("cube", true) || it.settings["wall_loops"] == "4" }.settings["wall_loops"])
        assertEquals(1, s.plates[0].layerGcodes.size)
        assertEquals(QIDI, info.printer)
        assertEquals(2, info.filaments.size)
    }

    @Test
    fun f02_exportsAndGcodeViewer() = runBlocking<Unit> {
        use(QIDI)
        clear()
        engine.loadModels(listOf(File(models, "OrcaCube_v2.drc").path), false, 0)
        val stl = File(app.cacheDir, "plate.stl")
        engine.exportStl(stl.path, 0)
        assertTrue(stl.length() > 1000)
        val (r, _) = slice()
        val archive = File(app.cacheDir, "plate.gcode.3mf")
        engine.exportGcode3mf(0, r.gcodeFile, archive.path)
        ZipFile(archive).use { z -> assertTrue("gcode in 3mf", z.getEntry("Metadata/plate_1.gcode") != null) }
        val copy = File(app.cacheDir, "external.gcode").also { File(r.gcodeFile).copyTo(it, overwrite = true) }
        val viewed = engine.viewGcode(copy.path) { _, _ -> }
        assertTrue(viewed.external)
        assertEquals(r.layers.size.toFloat(), viewed.layers.size.toFloat(), 2f)
    }

    @Test
    fun f02b_gcode3mfSliceInfo() = runBlocking<Unit> {
        use(A1)
        clear()
        cube(15f)
        val (r, _) = slice()
        val archive = File(app.cacheDir, "a1.gcode.3mf")
        engine.exportGcode3mf(0, r.gcodeFile, archive.path)
        val info = ZipFile(archive).use { z -> z.getInputStream(z.getEntry("Metadata/slice_info.config")).reader().readText() }
        assertTrue(info, Regex("key=\"prediction\" value=\"[1-9]").containsMatchIn(info))
        assertTrue(info, Regex("key=\"weight\" value=\"[0-9.]+\"").containsMatchIn(info))
        assertTrue(info, Regex("key=\"printer_model_id\" value=\"\\w+\"").containsMatchIn(info))
        // Printers know Bambu's filament ids (GF..), not OrcaSlicer's re-keyed ones.
        assertTrue(info, Regex("<filament id=\"1\" tray_info_idx=\"GF\\w+\" type=\"PLA\"").containsMatchIn(info))
    }

    @Test
    fun f03_cancelSlicing() = runBlocking<Unit> {
        use(QIDI, mapOf("layer_height" to "0.08"))
        clear()
        engine.loadModels(listOf(File(models, "3DBenchy.drc").path), false, 0)
        val job = async { runCatching { engine.slice(0) { _, _ -> } } }
        delay(1500)
        engine.cancelSlicing()
        val result = job.await()
        assertTrue("slicing was not cancelled", result.isFailure)
        // The engine is usable afterwards.
        use(QIDI)
        slice()
    }

    /** Slices after each kind of part and a height range, so a crash points at its cause. */
    @Test
    fun c05_sliceAfterEveryVolumeAndRange() = runBlocking<Unit> {
        use(QIDI)
        clear()
        cube(25f)
        step("slice plain") { slice() }
        step("modifier") { engine.addVolume(0, VolumeType.MODIFIER, "box", Vec3(10f, 10f, 10f)) }
        step("slice modifier") { slice() }
        step("negative") { engine.addVolume(0, VolumeType.NEGATIVE, "box", Vec3(6f, 6f, 30f)) }
        step("slice negative") { slice() }
        step("blocker") { engine.addVolume(0, VolumeType.SUPPORT_BLOCKER, "box", Vec3(5f, 5f, 5f)) }
        step("slice blocker") { slice() }
        val s = step("enforcer") { engine.addVolume(0, VolumeType.SUPPORT_ENFORCER, "box", Vec3(5f, 5f, 5f)) }
        step("slice enforcer") { slice() }
        val modifier = s.objects[0].volumes.indexOfFirst { it.type == VolumeType.MODIFIER }
        step("modifier setting") { engine.setObjectSetting(0, modifier, "sparse_infill_density", "80%") }
        step("slice modifier setting") { slice() }
        step("layer range") { engine.setLayerRanges(0, listOf(LayerRange(5f, 10f, mapOf("sparse_infill_density" to "40%")))) }
        step("slice layer range") { slice() }
    }
}
