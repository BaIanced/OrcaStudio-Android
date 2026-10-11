package app.orcaandroid.scad

import org.json.JSONArray
import org.json.JSONObject

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
        // Circular pitch (pitch diameter = teeth * pitch / 180)
        pitch = 300; // [100:10:600]
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
