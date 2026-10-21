package app.orcaandroid.core

import org.json.JSONObject

/** Plain-text report of the engine's `checkMesh` result (the Check mesh dialog and the object_check_mesh MCP tool). */
object MeshReport {
    fun text(r: JSONObject): String {
        val parts = r.getJSONArray("parts")
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
            val volume = p.optDouble("volumeMm3")
            out.appendLine("$name: ${p.optInt("faces")} faces, $shells shell${if (shells == 1) "" else "s"}, volume ${"%.0f".format(volume)} mm3")
            if (open > 0) { problems++; out.appendLine("  - $open open edges (the mesh has holes; Repair may help)") }
            if (degenerate > 0) { problems++; out.appendLine("  - $degenerate zero-area faces (Repair removes them)") }
            if (volume < 0) { problems++; out.appendLine("  - negative volume (faces point inward)") }
            if (thin > 0) { problems++; out.appendLine("  - $thin faces with a wall thinner than ${"%.2f".format(thickness)} mm") }
        }
        out.appendLine(if (problems == 0) "No problems found." else "$problems problem${if (problems == 1) "" else "s"} found.")
        return out.toString().trimEnd()
    }
}
