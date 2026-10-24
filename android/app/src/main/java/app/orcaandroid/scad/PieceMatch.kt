package app.orcaandroid.scad

/**
 * The pure core of the Build workspace's piece matching (see `ScadController.matched`): which of an object's
 * volumes hold which of the design's pieces, decided from the pieces and the volumes alone — no files, no
 * controller state. A piece counts as placed when its render was not empty (only blank text renders empty);
 * the object must hold exactly the placed old pieces' volumes, parts and negative volumes in the pieces'
 * order, or nothing matches at all.
 */
object PieceMatch {
    /**
     * New piece index -> (the old piece it reuses, the index of the volume that holds it). [oldEmpty] and
     * [newEmpty] say which old / new pieces rendered empty, in list order like the piece lists; an empty old
     * piece holds no volume, an empty new piece matches nothing. [volumes] is the object's part and negative
     * volumes in object order, as (volume index, isHole). A new piece reuses the first placed old piece with
     * the same shape, copy and code — moving or turning a piece keeps its code, so it still matches (whether
     * it also stayed in place is the caller's question, answered by comparing matrices).
     */
    fun match(
        oldPieces: List<ShapeDesign.Piece>,
        oldEmpty: List<Boolean>,
        newPieces: List<ShapeDesign.Piece>,
        newEmpty: List<Boolean>,
        volumes: List<Pair<Int, Boolean>>,
    ): Map<Int, Pair<ShapeDesign.Piece, Int>> {
        val placed = oldPieces.filterIndexed { i, _ -> !oldEmpty[i] }
        if (volumes.size != placed.size || volumes.zip(placed).any { (v, p) -> v.second != p.hole }) return emptyMap()
        return newPieces.indices.mapNotNull { i ->
            if (newEmpty[i]) return@mapNotNull null
            val p = newPieces[i]
            val j = placed.indexOfFirst { it.shapeId == p.shapeId && it.copy == p.copy && it.scad == p.scad }
            if (j < 0) null else i to (placed[j] to volumes[j].first)
        }.toMap()
    }
}
