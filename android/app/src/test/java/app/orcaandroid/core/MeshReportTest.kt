package app.orcaandroid.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [MeshReport]'s wording against hand-built `checkMesh` results. Problems are
 * counted per problem *kind* per part (5 open edges are one problem, not five).
 * Marker quadruples are x, y, z, kind with kinds 5 = thin, 6 = open, 7 = crossing,
 * always emitted in thin / open / cross order whatever the JSON key order is.
 */
class MeshReportTest {

    private fun part(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v) } }

    private fun report(vararg parts: JSONObject, extra: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("parts", JSONArray(parts.toList())).also(extra)

    @Test fun emptyPartsMessage() {
        assertEquals("No model parts to check.", MeshReport.text(report()))
    }

    @Test fun missingPartsMessage() {
        // A checkMesh result without "parts" at all reads the same as an empty one.
        assertEquals("No model parts to check.", MeshReport.text(JSONObject("{}")))
    }

    @Test fun cleanPartSingularShellNoProblems() {
        val r = report(part("name" to "Cube", "faces" to 100, "shells" to 1, "volumeMm3" to 1234.4))
        assertEquals("Cube: 100 faces, 1 shell, volume 1234 mm3\nNo problems found.", MeshReport.text(r))
    }

    @Test fun pluralShellsAndBlankNameFallsBackToVolumeIndex() {
        val r = report(part("name" to "", "volume" to 2, "faces" to 12, "shells" to 3, "volumeMm3" to 0.0))
        assertEquals("Part 3: 12 faces, 3 shells, volume 0 mm3\nNo problems found.", MeshReport.text(r))
    }

    @Test fun allProblemKindsCountedOnceEachInOrder() {
        val r = report(
            part(
                "name" to "Bad", "faces" to 10, "shells" to 1, "volumeMm3" to -5.0,
                "openEdges" to 3, "degenerate" to 2, "intersections" to 4, "thinFaces" to 6,
            ),
        ) { put("thickness", 0.4) }
        val expected = """
            Bad: 10 faces, 1 shell, volume -5 mm3
              - 3 open edges (the mesh has holes; Repair may help)
              - 2 zero-area faces (Repair removes them)
              - negative volume (faces point inward)
              - 4 pairs of faces cut through each other (self-intersections)
              - 6 faces with a wall thinner than 0.40 mm
            5 problems found.
        """.trimIndent()
        assertEquals(expected, MeshReport.text(r))
    }

    @Test fun singleProblemSingularWording() {
        val r = report(part("name" to "A", "faces" to 1, "shells" to 1, "volumeMm3" to 1.0, "openEdges" to 1))
        assertTrue(MeshReport.text(r).endsWith("1 problem found."))
    }

    @Test fun problemsAccumulateAcrossParts() {
        val r = report(
            part("name" to "A", "faces" to 1, "shells" to 1, "volumeMm3" to 1.0, "openEdges" to 1),
            part("name" to "B", "faces" to 1, "shells" to 1, "volumeMm3" to 1.0, "degenerate" to 9),
        )
        assertTrue(MeshReport.text(r).endsWith("2 problems found."))
    }

    @Test fun thinLineWithoutThicknessShowsNaNToday() {
        // optDouble("thickness") is NaN when the key is missing, and the thin line
        // formats it verbatim. The engine always sends a thickness; pinned, not fixed.
        val r = report(part("name" to "A", "faces" to 1, "shells" to 1, "volumeMm3" to 1.0, "thinFaces" to 2))
        assertTrue(MeshReport.text(r).contains("thinner than NaN mm"))
    }

    @Test fun markersLayoutAndKindOrder() {
        // Keys deliberately not in thin/open/cross order in the JSON.
        val r = JSONObject()
            .put("crossPoints", JSONArray(listOf(7.0, 8.0, 9.0)))
            .put("thinPoints", JSONArray(listOf(1.0, 2.0, 3.0)))
            .put("openPoints", JSONArray(listOf(4.0, 5.0, 6.0, 10.0, 11.0, 12.0)))
        val m = MeshReport.markers(r)
        assertEquals(16, m.size)
        assertEquals(listOf(1f, 2f, 3f, 5f), m.slice(0..3))
        assertEquals(listOf(4f, 5f, 6f, 6f, 10f, 11f, 12f, 6f), m.slice(4..11))
        assertEquals(listOf(7f, 8f, 9f, 7f), m.slice(12..15))
    }

    @Test fun markersIncompleteTripleDroppedAndMissingKeysEmpty() {
        val r = JSONObject().put("thinPoints", JSONArray(listOf(1.0, 2.0, 3.0, 4.0, 5.0)))
        val m = MeshReport.markers(r)
        assertEquals(4, m.size) // the trailing x,y pair has no z and is dropped
        assertEquals(listOf(1f, 2f, 3f, 5f), m.toList())
        assertEquals(0, MeshReport.markers(JSONObject("{}")).size)
        assertEquals(0, MeshReport.markers(JSONObject().put("openPoints", JSONArray())).size)
    }

    @Test fun markersConvertThroughFloat() {
        // Doubles go through Float -> Double -> Float storage: 0.1 does not survive
        // exactly, it becomes the nearest Float.
        val r = JSONObject().put("openPoints", JSONArray(listOf(0.1, 0.0, 0.0)))
        assertEquals(0.1f, MeshReport.markers(r)[0])
    }
}
