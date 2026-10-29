package app.orcaandroid.core

import org.json.JSONObject

/** Plain-text report of the engine's `checkMesh` result (the Check mesh dialog and the object_check_mesh MCP tool). */
object MeshReport {
    fun text(r: JSONObject): String {
        val parts = r.optJSONArray("parts") ?: return "No model parts to check."
        val thickness = r.optDouble("thickness")
        if (parts.length() == 0) return "No model parts to check."
        val out = StringBuilder()
        var problems = 0
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val name = p.optString("name").ifBlank { "Part ${p.optInt("volume") + 1}" }
            val open = p.optInt("openEdges")
            val degenerate = p.optInt("degenerate")
            val shells = p.optInt("shells")
            val thin = p.optInt("thinFaces")
            val crossing = p.optInt("intersections")
            val sharp = p.optInt("sharpEdges")
            val volume = p.optDouble("volumeMm3")
            out.appendLine("$name: ${p.optInt("faces")} faces, $shells shell${if (shells == 1) "" else "s"}, volume ${"%.0f".format(volume)} mm3")
            if (open > 0) { problems++; out.appendLine("  - $open open edges (the mesh has holes; Repair may help)") }
            if (degenerate > 0) { problems++; out.appendLine("  - $degenerate zero-area faces (Repair removes them)") }
            if (volume < 0) { problems++; out.appendLine("  - negative volume (faces point inward)") }
            if (crossing > 0) { problems++; out.appendLine("  - $crossing pairs of faces cut through each other (self-intersections)") }
            if (sharp > 0) { problems++; out.appendLine("  - $sharp sharp edges (faces folded back on each other, over 160 degrees)") }
            if (thin > 0) { problems++; out.appendLine("  - $thin faces with a wall thinner than ${"%.2f".format(thickness)} mm") }
        }
        out.appendLine(if (problems == 0) "No problems found." else "$problems problem${if (problems == 1) "" else "s"} found.")
        return out.toString().trimEnd()
    }

    /** Marker kinds of the point shader: thin wall, open edge, self-intersection, sharp edge. */
    private val KINDS = listOf("thinPoints" to 5f, "openPoints" to 6f, "crossPoints" to 7f, "sharpPoints" to 8f)

    /** The problem spots of a `checkMesh` result as x, y, z, kind quadruples (empty when it found none). */
    fun markers(r: JSONObject): FloatArray {
        val out = ArrayList<Float>()
        for ((key, kind) in KINDS) {
            val pts = r.optJSONArray(key) ?: continue
            for (i in 0 until pts.length() / 3) {
                for (k in 0 until 3) out += pts.getDouble(i * 3 + k).toFloat()
                out += kind
            }
        }
        return out.toFloatArray()
    }
}
