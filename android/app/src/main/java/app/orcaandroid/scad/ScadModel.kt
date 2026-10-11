package app.orcaandroid.scad

import app.orcaandroid.core.SceneObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One Customizer parameter, from OpenSCAD's parameter JSON (src/io/export_param.cc). */
data class ScadParam(
    val name: String,
    val caption: String,
    val group: String,
    val kind: Kind,
    /** Double, List<Double>, Boolean or String, as [kind]. */
    val initial: Any,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    /** Label to value for a drop-down. */
    val options: List<Pair<String, Any>> = emptyList(),
    val maxLength: Int? = null,
) {
    enum class Kind { NUMBER, VECTOR, BOOLEAN, STRING, CHOICE }

    /** Decimals the step implies (0.25 -> 2). */
    val decimals: Int get() = step?.let { s ->
        java.math.BigDecimal.valueOf(s).toPlainString().substringAfter('.', "").trimEnd('0').length
    } ?: 2

    companion object {
        fun parse(json: JSONObject?): List<ScadParam> {
            val list = json?.optJSONArray("parameters") ?: return emptyList()
            return (0 until list.length()).mapNotNull { i -> runCatching { one(list.getJSONObject(i)) }.getOrNull() }
        }

        private fun one(o: JSONObject): ScadParam {
            val type = o.optString("type")
            val raw = o.get("initial")
            val options = o.optJSONArray("options")?.let { a ->
                (0 until a.length()).map { j -> a.getJSONObject(j).let { it.optString("name") to value(it.get("value")) } }
            }.orEmpty()
            val kind = when {
                options.isNotEmpty() -> Kind.CHOICE
                type == "boolean" -> Kind.BOOLEAN
                type == "string" -> Kind.STRING
                raw is JSONArray -> Kind.VECTOR
                else -> Kind.NUMBER
            }
            return ScadParam(
                name = o.getString("name"),
                caption = o.optString("caption"),
                group = o.optString("group"),
                kind = kind,
                initial = value(raw),
                min = o.optDouble("min").takeUnless { it.isNaN() },
                max = o.optDouble("max").takeUnless { it.isNaN() },
                step = o.optDouble("step").takeUnless { it.isNaN() },
                options = options,
                maxLength = o.optInt("maxLength", -1).takeIf { it > 0 },
            )
        }

        private fun value(v: Any): Any = when (v) {
            is Boolean -> v
            is Number -> v.toDouble()
            is JSONArray -> (0 until v.length()).map { v.getDouble(it) }
            else -> v.toString()
        }

        /**
         * The flat JSON object scad_render takes (bridge apply_params): ptree reads every value as
         * text, so vectors go as "[a, b, c]" like OpenSCAD's own parameter sets.
         */
        fun encode(values: Map<String, Any>): String = JSONObject().apply {
            values.forEach { (k, v) ->
                put(k, when (v) {
                    is List<*> -> v.joinToString(", ", "[", "]") { format(it as Double) }
                    is Double -> format(v)
                    else -> v
                })
            }
        }.toString()

        fun format(d: Double): String =
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

        fun decode(json: String?): Map<String, Any> {
            val o = runCatching { JSONObject(json ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
            return o.keys().asSequence().associateWith { k ->
                when (val v = o.get(k)) {
                    is Boolean -> v
                    is Number -> v.toDouble()
                    is String -> if (v.startsWith("[")) v.trim('[', ']').split(',').mapNotNull { it.trim().toDoubleOrNull() } else v
                    else -> v.toString()
                }
            }
        }
    }
}

/**
 * The OpenSCAD design an object on the plate was rendered from. Kept in the object's
 * ModelObject::module_name ("openscad:" + compact JSON), which the 3MF stores per object, so a
 * reopened project can be edited again. Compact JSON escapes newlines, which XML attributes would
 * otherwise turn into spaces.
 */
data class ScadDesign(
    val name: String,
    val source: String,
    val values: Map<String, Any>,
    /** Local include<> / use<> files, by path relative to the main file's folder (see [ScadDeps]). */
    val files: Map<String, String> = emptyMap(),
) {
    fun encode(): String = PREFIX + JSONObject()
        .put("name", name)
        .put("source", source)
        .put("values", ScadParam.encode(values))
        .apply { if (files.isNotEmpty()) put("files", JSONObject(files)) }
        .toString()

    /**
     * Writes the main source and its [files] under [root] so include<> resolves as it did where the
     * design was made; returns the main file. Paths leaving the main folder ("../lib.scad") get as
     * many folder levels above the main file as they need.
     */
    fun unpack(root: File): File {
        val up = files.keys.maxOfOrNull { k -> k.split('/').takeWhile { it == ".." }.size } ?: 0
        val mainDir = (1..up).fold(root) { d, i -> File(d, "d$i") }
        val main = File(mainDir, name.substringAfterLast('/').ifEmpty { "design.scad" })
        root.deleteRecursively()
        mainDir.mkdirs()
        main.writeText(source)
        for ((rel, text) in files) {
            val f = File(mainDir, rel).canonicalFile
            if (!f.path.startsWith(root.canonicalPath + File.separator)) continue
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        return main
    }

    companion object {
        private const val PREFIX = "openscad:"

        fun of(obj: SceneObject?): ScadDesign? {
            val module = obj?.module?.takeIf { it.startsWith(PREFIX) } ?: return null
            val o = runCatching { JSONObject(module.substring(PREFIX.length)) }.getOrNull() ?: return null
            val files = o.optJSONObject("files")?.let { f -> f.keys().asSequence().associateWith { f.optString(it) } } ?: emptyMap()
            return ScadDesign(o.optString("name").ifEmpty { obj.name }, o.optString("source"), ScadParam.decode(o.optString("values")), files)
        }
    }
}

/**
 * The local files a design pulls in with include<> / use<>, so they can travel inside the project.
 * OpenSCAD looks next to the including file first, then in the library path (find_valid_path in
 * src/core/parsersettings.cc); only the first kind is collected, library files (MCAD) stay out.
 * Data files for import() / surface() are not collected.
 */
object ScadDeps {
    private val INCLUDE = Regex("""\b(?:include|use)\s*<([^>\n]+)>""")
    private const val MAX_BYTES = 2 shl 20

    fun collect(source: String, mainFile: String): Map<String, String> {
        val base = File(mainFile).absoluteFile.parentFile ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        var total = 0
        fun scan(text: String, dir: File) {
            for (m in INCLUDE.findAll(stripComments(text))) {
                val ref = m.groupValues[1].trim()
                if (ref.isEmpty() || File(ref).isAbsolute) continue
                val f = File(dir, ref).canonicalFile
                if (!f.isFile || !seen.add(f.path)) continue
                total += f.length().toInt()
                if (total > MAX_BYTES) return
                val content = runCatching { f.readText() }.getOrNull() ?: continue
                out[f.relativeTo(base.canonicalFile).invariantSeparatorsPath] = content
                scan(content, f.parentFile!!)
            }
        }
        scan(source, base)
        return out
    }

    private fun stripComments(s: String) =
        s.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")
}

/** Built-in starting points: each shows something the editor and renderer can do. */
object ScadExamples {
    data class Example(val title: String, val file: String, val source: String)

    val BOX = Example("Parametric box", "box.scad", """
        // A parametric box. Change the code or the values in the Customizer,
        // then Render to put it on the plate (Ctrl+Enter / F6).

        /* [Size] */
        // Outer width (mm)
        width = 60; // [10:1:200]
        // Outer depth (mm)
        depth = 40; // [10:1:200]
        // Outer height (mm)
        height = 30; // [5:1:150]

        /* [Walls] */
        // Wall thickness (mm)
        wall = 2; // [0.8:0.2:6]
        // Corner radius (mm)
        radius = 4; // [0:0.5:20]
        // Hollow inside
        hollow = true;

        /* [Hidden] */
        ${'$'}fn = 48;

        module rounded_box(size, r) {
            if (r <= 0) cube(size);
            else hull() for (x = [r, size.x - r], y = [r, size.y - r])
                translate([x, y, 0]) cylinder(r = r, h = size.z);
        }

        difference() {
            rounded_box([width, depth, height], radius);
            if (hollow)
                translate([wall, wall, wall])
                    rounded_box([width - 2 * wall, depth - 2 * wall, height], max(radius - wall, 0));
        }
    """.trimIndent() + "\n")

    val GEAR = Example("Spur gear (MCAD)", "gear.scad", """
        // A spur gear from the bundled MCAD library.
        use <MCAD/involute_gears.scad>

        /* [Gear] */
        // Number of teeth
        teeth = 24; // [8:1:80]
        // Tooth size: mm along the pitch circle per tooth (diameter = teeth * pitch / pi)
        pitch = 5; // [2:0.5:12]
        // Thickness (mm)
        thickness = 6; // [2:0.5:20]
        // Bore diameter (mm)
        bore = 5; // [0:0.5:20]

        gear(number_of_teeth = teeth, circular_pitch = pitch,
             gear_thickness = thickness, rim_thickness = thickness, hub_thickness = thickness,
             bore_diameter = bore);
    """.trimIndent() + "\n")

    val TAG = Example("Name tag (text)", "name_tag.scad", """
        // A name tag: raised text on a plate that follows its outline.

        /* [Text] */
        label = "OrcaStudio";
        // Text height (mm)
        size = 10; // [4:1:30]
        font = "Liberation Sans"; // [Liberation Sans, Liberation Serif, Liberation Mono]
        bold = true;

        /* [Plate] */
        // Plate thickness (mm)
        thickness = 2; // [1:0.5:6]
        // Text height above the plate (mm)
        emboss = 1.2; // [0.4:0.2:4]
        // Border around the text (mm)
        margin = 4; // [1:1:15]

        /* [Hidden] */
        ${'$'}fn = 32;
        face = str(font, bold ? ":style=Bold" : "");

        module label_2d() text(label, size = size, font = face, halign = "center", valign = "center");

        linear_extrude(thickness) offset(r = margin) hull() label_2d();
        translate([0, 0, thickness]) linear_extrude(emboss) label_2d();
    """.trimIndent() + "\n")

    val ALL = listOf(BOX, GEAR, TAG)
}
