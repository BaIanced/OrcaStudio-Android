package app.orcaandroid.scad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Build workspace's piece matching, pure: unchanged pieces reuse their old volumes, anything else does not. */
class PieceMatchTest {
    private val identity = listOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun piece(id: Int, copy: Int = 0, hole: Boolean = false, scad: String = "scad$id", matrix: List<Double> = identity) =
        ShapeDesign.Piece(id, copy, hole, scad, matrix)

    private fun noneEmpty(pieces: List<ShapeDesign.Piece>) = pieces.map { false }

    @Test fun unchangedPiecesReuseTheirVolumes() {
        val old = listOf(piece(1), piece(2))
        val m = PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), listOf(10 to false, 11 to false))
        assertEquals(mapOf(0 to (old[0] to 10), 1 to (old[1] to 11)), m)
    }

    @Test fun editedPieceMatchesNothingButItsNeighboursStillDo() {
        val old = listOf(piece(1), piece(2))
        val new = listOf(piece(1), piece(2, scad = "edited"))
        val m = PieceMatch.match(old, noneEmpty(old), new, noneEmpty(new), listOf(10 to false, 11 to false))
        assertEquals(mapOf(0 to (old[0] to 10)), m)
    }

    @Test fun movedPieceStillMatches() {
        // A move changes only the matrix, never the code: the mesh is reused (staying in place is anchors' call).
        val old = listOf(piece(1))
        val moved = piece(1, matrix = identity.mapIndexed { i, v -> if (i == 3) v + 5.0 else v })
        val m = PieceMatch.match(old, noneEmpty(old), listOf(moved), listOf(false), listOf(7 to false))
        assertEquals(mapOf(0 to (old[0] to 7)), m)
    }

    @Test fun emptyNewPieceMatchesNothing() {
        val old = listOf(piece(1), piece(2))
        val m = PieceMatch.match(old, noneEmpty(old), old, listOf(false, true), listOf(10 to false, 11 to false))
        assertEquals(mapOf(0 to (old[0] to 10)), m)
    }

    @Test fun emptyOldPieceHoldsNoVolume() {
        // A blank text piece rendered empty: the object holds volumes for the other two pieces only.
        val old = listOf(piece(1), piece(2, scad = "// (no text)"), piece(3))
        val m = PieceMatch.match(old, listOf(false, true, false), old, listOf(false, true, false), listOf(10 to false, 12 to false))
        assertEquals(mapOf(0 to (old[0] to 10), 2 to (old[2] to 12)), m)
    }

    @Test fun volumeCountMismatchMatchesNothingAtAll() {
        val old = listOf(piece(1), piece(2))
        assertTrue(PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), listOf(10 to false)).isEmpty())
        assertTrue(PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), listOf(10 to false, 11 to false, 12 to false)).isEmpty())
    }

    @Test fun holeFlagMismatchMatchesNothingAtAll() {
        val old = listOf(piece(1), piece(2, hole = true))
        // The hole's volume listed as a part, and the two volumes swapped: either way the line-up is broken.
        assertTrue(PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), listOf(10 to false, 11 to false)).isEmpty())
        assertTrue(PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), listOf(10 to true, 11 to false)).isEmpty())
    }

    @Test fun holesMatchNegativeVolumes() {
        val old = listOf(piece(1), piece(2, hole = true))
        val m = PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), listOf(5 to false, 7 to true))
        assertEquals(mapOf(0 to (old[0] to 5), 1 to (old[1] to 7)), m)
    }

    @Test fun identicalCodeKeepsItsOwnVolume() {
        // Two shapes with the same code: the shape id, not the code alone, says which volume is whose.
        val old = listOf(piece(1, scad = "same"), piece(2, scad = "same"))
        val m = PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), listOf(10 to false, 11 to false))
        assertEquals(mapOf(0 to (old[0] to 10), 1 to (old[1] to 11)), m)
    }

    @Test fun reorderedPiecesMatchByShapeNotByPosition() {
        val old = listOf(piece(1), piece(2))
        val new = listOf(piece(2), piece(1))
        val m = PieceMatch.match(old, noneEmpty(old), new, noneEmpty(new), listOf(10 to false, 11 to false))
        assertEquals(mapOf(0 to (old[1] to 11), 1 to (old[0] to 10)), m)
    }

    @Test fun copiesMatchTheirOwnCopy() {
        val old = (0 until 3).map { piece(1, copy = it, scad = "row") }
        val vols = listOf(10 to false, 11 to false, 12 to false)
        val m = PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), vols)
        assertEquals((0 until 3).associate { it to (old[it] to vols[it].first) }, m)
        // One copy fewer: the survivors keep their volumes. One copy more: the new copy matches nothing.
        val fewer = old.dropLast(1)
        assertEquals((0 until 2).associate { it to (old[it] to vols[it].first) },
            PieceMatch.match(old, noneEmpty(old), fewer, noneEmpty(fewer), vols))
        val more = old + piece(1, copy = 3, scad = "row")
        assertEquals((0 until 3).associate { it to (old[it] to vols[it].first) },
            PieceMatch.match(old, noneEmpty(old), more, noneEmpty(more), vols))
    }

    @Test fun duplicatePiecesBothMatchTheFirstToday() {
        // indexOfFirst semantics: two identical pieces (same shape, copy and code — a design cannot make
        // them, ids are unique) both claim the first one's volume. Pinned, not fixed.
        val old = listOf(piece(1, scad = "same"), piece(1, scad = "same"))
        val m = PieceMatch.match(old, noneEmpty(old), old, noneEmpty(old), listOf(10 to false, 11 to false))
        assertEquals(mapOf(0 to (old[0] to 10), 1 to (old[0] to 10)), m)
    }

    @Test fun nothingPlacedMatchesNothing() {
        val new = listOf(piece(1))
        assertTrue(PieceMatch.match(emptyList(), emptyList(), new, listOf(false), emptyList()).isEmpty())
        assertTrue(PieceMatch.match(new, listOf(true), new, listOf(false), emptyList()).isEmpty())
    }
}
