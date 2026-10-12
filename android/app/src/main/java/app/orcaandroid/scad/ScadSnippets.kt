package app.orcaandroid.scad

import androidx.annotation.StringRes
import app.orcaandroid.R

/**
 * One entry of the editor's Insert menu. [code] is inserted on a line of its own at the cursor;
 * with text selected, a [wrap] snippet puts the selected lines inside it instead (`%s`).
 */
data class ScadSnippet(@StringRes val title: Int, @StringRes val description: Int, val code: String, val wrap: String? = null)

data class ScadSnippetGroup(@StringRes val title: Int, val items: List<ScadSnippet>)

object ScadSnippets {

    val GROUPS = listOf(
        ScadSnippetGroup(R.string.scad_ins_shapes, listOf(
            ScadSnippet(R.string.scad_sn_cube, R.string.scad_sn_cube_desc, "cube([20, 10, 5]); // width, depth, height"),
            ScadSnippet(R.string.scad_sn_cylinder, R.string.scad_sn_cylinder_desc, "cylinder(h = 10, d = 20); // height, diameter"),
            ScadSnippet(R.string.scad_sn_cone, R.string.scad_sn_cone_desc, "cylinder(h = 15, d1 = 20, d2 = 5); // height, bottom and top diameter"),
            ScadSnippet(R.string.scad_sn_sphere, R.string.scad_sn_sphere_desc, "sphere(d = 20);"),
            ScadSnippet(R.string.scad_sn_tube, R.string.scad_sn_tube_desc, """
                // Tube: a cylinder minus a thinner one
                difference() {
                    cylinder(h = 10, d = 20);
                    translate([0, 0, -1]) cylinder(h = 12, d = 16);
                }
            """.trimIndent()),
            ScadSnippet(R.string.scad_sn_rounded, R.string.scad_sn_rounded_desc, """
                // Box with rounded corners: wrap four posts
                hull() for (x = [3, 40 - 3], y = [3, 25 - 3])
                    translate([x, y, 0]) cylinder(h = 10, r = 3);
            """.trimIndent()),
            ScadSnippet(R.string.scad_sn_text, R.string.scad_sn_text_desc, """
                linear_extrude(2) text("Hello", size = 10, font = "Liberation Sans:style=Bold");
            """.trimIndent()),
        )),
        ScadSnippetGroup(R.string.scad_ins_position, listOf(
            ScadSnippet(R.string.scad_sn_move, R.string.scad_sn_move_desc,
                "translate([10, 0, 0]) cube(10); // move by X, Y, Z (mm)", "translate([10, 0, 0]) {\n%s\n}"),
            ScadSnippet(R.string.scad_sn_rotate, R.string.scad_sn_rotate_desc,
                "rotate([0, 0, 45]) cube(10); // turn around X, Y, Z (degrees)", "rotate([0, 0, 45]) {\n%s\n}"),
            ScadSnippet(R.string.scad_sn_scale, R.string.scad_sn_scale_desc,
                "scale([1, 1, 2]) sphere(d = 10); // stretch X, Y, Z (1 = unchanged)", "scale([1, 1, 2]) {\n%s\n}"),
            ScadSnippet(R.string.scad_sn_mirror, R.string.scad_sn_mirror_desc,
                "mirror([1, 0, 0]) translate([5, 0, 0]) cube(10); // flip across the Y-Z plane", "mirror([1, 0, 0]) {\n%s\n}"),
            ScadSnippet(R.string.scad_sn_center, R.string.scad_sn_center_desc, "cube([20, 10, 5], center = true);"),
        )),
        ScadSnippetGroup(R.string.scad_ins_combine, listOf(
            ScadSnippet(R.string.scad_sn_difference, R.string.scad_sn_difference_desc, """
                difference() {
                    cube([30, 30, 10]);                            // the part
                    translate([15, 15, -1]) cylinder(h = 12, d = 8); // what is cut away
                }
            """.trimIndent(), "difference() {\n%s\n    // shapes to cut away go here\n}"),
            ScadSnippet(R.string.scad_sn_union, R.string.scad_sn_union_desc, """
                union() {
                    cube([30, 10, 10]);
                    cylinder(h = 20, d = 10);
                }
            """.trimIndent(), "union() {\n%s\n}"),
            ScadSnippet(R.string.scad_sn_intersection, R.string.scad_sn_intersection_desc, """
                intersection() {
                    cube(20, center = true);
                    sphere(d = 26);
                }
            """.trimIndent(), "intersection() {\n%s\n}"),
            ScadSnippet(R.string.scad_sn_hull, R.string.scad_sn_hull_desc, """
                hull() {
                    cylinder(h = 5, d = 20);
                    translate([30, 0, 0]) cylinder(h = 5, d = 10);
                }
            """.trimIndent(), "hull() {\n%s\n}"),
            ScadSnippet(R.string.scad_sn_minkowski, R.string.scad_sn_minkowski_desc, """
                // Rounds all edges by 2 mm (the result grows by 2 mm on every side)
                minkowski() {
                    cube([30, 20, 6]);
                    sphere(r = 2, ${'$'}fn = 16);
                }
            """.trimIndent(), "minkowski() {\n%s\n    sphere(r = 2, \$fn = 16);\n}"),
        )),
        ScadSnippetGroup(R.string.scad_ins_repeat, listOf(
            ScadSnippet(R.string.scad_sn_row, R.string.scad_sn_row_desc, """
                // 5 copies, 15 mm apart
                for (i = [0 : 4])
                    translate([i * 15, 0, 0]) cube(10);
            """.trimIndent(), "for (i = [0 : 4])\n    translate([i * 15, 0, 0]) {\n%s\n    }"),
            ScadSnippet(R.string.scad_sn_grid, R.string.scad_sn_grid_desc, """
                // 4 x 3 copies, 12 mm apart
                for (x = [0 : 3], y = [0 : 2])
                    translate([x * 12, y * 12, 0]) cylinder(h = 5, d = 8);
            """.trimIndent()),
            ScadSnippet(R.string.scad_sn_circle, R.string.scad_sn_circle_desc, """
                // 6 copies around a circle of radius 20 mm
                for (a = [0 : 60 : 359])
                    rotate([0, 0, a]) translate([20, 0, 0]) cylinder(h = 5, d = 6);
            """.trimIndent()),
            ScadSnippet(R.string.scad_sn_mirror_copy, R.string.scad_sn_mirror_copy_desc, """
                // The shape and its mirror image
                for (m = [0, 1]) mirror([m, 0, 0])
                    translate([10, 0, 0]) cube(5);
            """.trimIndent(), "for (m = [0, 1]) mirror([m, 0, 0]) {\n%s\n}"),
        )),
        ScadSnippetGroup(R.string.scad_ins_more, listOf(
            ScadSnippet(R.string.scad_sn_module, R.string.scad_sn_module_desc, """
                // A reusable part with its own settings
                module peg(height = 10, diameter = 5) {
                    cylinder(h = height, d = diameter);
                }
                peg();
                translate([10, 0, 0]) peg(height = 20);
            """.trimIndent()),
            ScadSnippet(R.string.scad_sn_extrude, R.string.scad_sn_extrude_desc, """
                // Draw in 2D, then give it a height
                linear_extrude(height = 5)
                    polygon([[0, 0], [30, 0], [15, 20]]);
            """.trimIndent()),
            ScadSnippet(R.string.scad_sn_rotate_extrude, R.string.scad_sn_rotate_extrude_desc, """
                // Spin a 2D outline around the Z axis (a ring here)
                rotate_extrude() translate([15, 0]) circle(d = 5);
            """.trimIndent()),
            ScadSnippet(R.string.scad_sn_smooth, R.string.scad_sn_smooth_desc, "\$fn = 96; // more = smoother curves, slower"),
            ScadSnippet(R.string.scad_sn_debug, R.string.scad_sn_debug_desc, "echo(\"value is\", 10 * 2); // shows up in the console"),
        )),
    )

    /**
     * Inserts [snippet] into [text] at [selStart]..[selEnd]: wrapped around the selected lines when
     * there is a selection and the snippet can wrap, else on its own line below the cursor's (or in
     * place of an empty line), indented like it. Returns the new text and cursor position.
     */
    fun insert(text: String, selStart: Int, selEnd: Int, snippet: ScadSnippet): Pair<String, Int> {
        // No cursor placed yet (a fresh editor sits at 0, inside the header comment): append.
        if (selStart == 0 && selEnd == 0 && text.isNotBlank()) {
            val head = text.trimEnd() + "\n\n"
            return head + snippet.code + "\n" to head.length + snippet.code.length
        }
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', a - 1) + 1
        if (snippet.wrap != null && b > a) {
            // Whole lines from the selection's first to its last.
            val end = text.indexOf('\n', if (b > a && text.getOrNull(b - 1) == '\n') b - 1 else b).let { if (it < 0) text.length else it }
            val block = text.substring(lineStart, end)
            val indent = block.lineSequence().filter { it.isNotBlank() }.minOfOrNull { l -> l.takeWhile { it == ' ' }.length } ?: 0
            val pad = " ".repeat(indent)
            val inner = block.lines().joinToString("\n") { if (it.isBlank()) it else "    $it" }
            val wrapped = snippet.wrap.split("%s").let { (head, tail) ->
                indentLines(head, pad) + inner + indentLines(tail, pad)
            }
            return text.substring(0, lineStart) + wrapped + text.substring(end) to lineStart + wrapped.length
        }
        val lineEnd = text.indexOf('\n', a).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        var indent = line.takeWhile { it == ' ' }
        if (line.trimEnd().endsWith("{")) indent += "    "
        val body = indentLines(snippet.code, indent)
        return if (line.isBlank()) {
            text.substring(0, lineStart) + body + text.substring(lineEnd) to lineStart + body.length
        } else {
            text.substring(0, lineEnd) + "\n" + body + text.substring(lineEnd) to lineEnd + 1 + body.length
        }
    }

    private fun indentLines(code: String, indent: String) =
        code.split('\n').joinToString("\n") { if (it.isEmpty()) it else indent + it }

    /** OpenSCAD variable name for a label typed by the user ("Wall thickness" -> wall_thickness). */
    fun identifier(label: String): String {
        val id = label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return when {
            id.isEmpty() -> "value"
            id[0].isDigit() -> "v_$id"
            else -> id
        }
    }

    private val ANNOTATED = Regex("""^\s*[A-Za-z_]\w*\s*=[^;]*;\s*//\s*\[.*$""")
    // The Customizer stops at the first module / function, the hidden section or the first shape.
    private val STOP = Regex("""^\s*(module|function)\b|^\s*/\*\s*\[\s*Hidden\s*]\s*\*/|^\s*[A-Za-z_]\w*\s*\(""", RegexOption.IGNORE_CASE)

    /**
     * Adds a Customizer slider for a new variable. It goes after the last slider (above the hidden
     * section and the first module, where the Customizer stops looking) or below the comments at the
     * top. Returns the new text, the cursor position after the insertion and the variable's name.
     */
    fun addParameter(text: String, label: String, mm: Boolean, value: Double, min: Double, max: Double, step: Double): Triple<String, Int, String> {
        var name = identifier(label)
        val taken = { n: String -> Regex("""(?m)^\s*${Regex.escape(n)}\s*=""").containsMatchIn(text) }
        if (taken(name)) name = (2..99).map { "${name}_$it" }.first { !taken(it) }
        val lines = text.split('\n')
        val stop = lines.indexOfFirst { STOP.containsMatchIn(it) }.let { if (it < 0) lines.size else it }
        val lastParam = (0 until stop).lastOrNull { ANNOTATED.matches(lines[it]) }
        val at = lastParam?.plus(1)
            ?: lines.indexOfFirst { l -> l.isNotBlank() && !l.trimStart().startsWith("//") }.let { if (it < 0) lines.size else it }
        val f = ScadParam::format
        val caption = label.trim().ifEmpty { name } + if (mm) " (mm)" else ""
        val block = listOf("// $caption", "$name = ${f(value)}; // [${f(min)}:${f(step)}:${f(max)}]")
        val gap = if (lastParam == null) listOf("") else emptyList()
        val out = lines.take(at) + block + gap + lines.drop(at)
        val cursor = out.take(at + block.size).sumOf { it.length + 1 }
        return Triple(out.joinToString("\n"), cursor.coerceAtMost(out.joinToString("\n").length), name)
    }
}

/** A plain-language explanation of a console message (see [ScadHints.explain]). */
data class ScadHint(@StringRes val text: Int, val args: List<String> = emptyList(), val line: Int? = null)

/** Turns OpenSCAD's console messages into tips for people new to it. */
object ScadHints {
    private val LINE = Regex("""line (\d+)""")
    private val NAME = Regex("""['"]([^'"]+)['"]""")

    private data class Rule(val match: Regex, @StringRes val text: Int, val withName: Boolean)

    private val RULES = listOf(
        Rule(Regex("""Parser error|syntax error""", RegexOption.IGNORE_CASE), R.string.scad_hint_syntax, false),
        Rule(Regex("""unknown variable""", RegexOption.IGNORE_CASE), R.string.scad_hint_unknown_variable, true),
        Rule(Regex("""unknown module""", RegexOption.IGNORE_CASE), R.string.scad_hint_unknown_module, true),
        Rule(Regex("""unknown function""", RegexOption.IGNORE_CASE), R.string.scad_hint_unknown_function, true),
        Rule(Regex("""not specified as parameter""", RegexOption.IGNORE_CASE), R.string.scad_hint_bad_argument, true),
        Rule(Regex("""Can't open (library|include file|input file)""", RegexOption.IGNORE_CASE), R.string.scad_hint_missing_file, true),
        Rule(Regex("""was assigned on line""", RegexOption.IGNORE_CASE), R.string.scad_hint_reassigned, true),
        Rule(Regex("""undefined operation|undefined value|convert .*undef""", RegexOption.IGNORE_CASE), R.string.scad_hint_undef, false),
        Rule(Regex("""Recursion detected|stack overflow""", RegexOption.IGNORE_CASE), R.string.scad_hint_recursion, false),
        Rule(Regex("""top level object is empty|object is empty""", RegexOption.IGNORE_CASE), R.string.scad_hint_empty, false),
        Rule(Regex("""not a 3D object|2D object""", RegexOption.IGNORE_CASE), R.string.scad_hint_2d, false),
    )

    /** Tips for the errors and warnings in [log], most important first, one per kind and name. */
    fun explain(log: String): List<ScadHint> {
        val out = LinkedHashMap<String, ScadHint>()
        val lines = log.lineSequence().filter { it.startsWith("ERROR") || it.startsWith("WARNING") }
            .sortedBy { if (it.startsWith("ERROR")) 0 else 1 }
        for (l in lines) {
            val rule = RULES.firstOrNull { it.match.containsMatchIn(l) } ?: continue
            val name = if (rule.withName) NAME.find(l)?.groupValues?.get(1)?.substringAfterLast('/') ?: continue else null
            val line = LINE.find(l)?.groupValues?.get(1)?.toIntOrNull()
            out.getOrPut("${rule.text}:$name") { ScadHint(rule.text, listOf(name ?: line?.toString() ?: "?"), line) }
            if (out.size >= 3) break
        }
        return out.values.toList()
    }
}
