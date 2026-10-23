package app.orcaandroid.ui.prepare

import app.orcaandroid.ui.Tool

/**
 * The colour-paint task modes (Brush / Fill / Bands / Object in [ColorPaintOptions]),
 * derived from the paint tool's shape. Pure JVM, so the mapping the UI shows can be
 * unit-tested: the mode itself lives in the tool (in `UiState`), not in a composable,
 * so it survives the paint shop leaving composition — switching to Preview and back
 * used to lose Object mode and land on Fill, because Object was a `rememberSaveable`
 * flag inside `ColorPaintOptions` while its shape is plain "fill".
 */
object PaintMode {
    const val BRUSH = "brush"
    const val FILL = "fill"
    const val BANDS = "bands"
    const val OBJECT = "object"

    /** The mode a [shape] belongs to on its own: fill-like shapes are Fill, the height band is Bands, every brush shape is Brush. */
    fun ofShape(shape: String): String = when (shape) {
        "fill", "regions" -> FILL
        "height" -> BANDS
        else -> BRUSH
    }

    /** The mode to show: Object only while the tool says so *and* its fill shape is still active. */
    fun of(shape: String, objectMode: Boolean): String =
        if (objectMode && shape == "fill") OBJECT else ofShape(shape)

    /**
     * The tool after switching from mode [from] to mode [to] (Task 11, the Task 7
     * follow-up). The per-mode shapes and the fill settings Object mode overrides live
     * on the tool itself ([Tool.Paint.brushShape], [Tool.Paint.fillShape] and the
     * `fillKeep*` snapshot), not in composable state, so they survive the paint shop
     * leaving composition — a Preview round-trip used to reset them the same way it
     * lost Object mode. Entering Object saves the current fill settings into the
     * snapshot and applies Object's overrides; leaving Object restores the snapshot.
     * Erase has no meaning for a whole part, so entering Object from erase selects
     * filament 1, as before.
     */
    fun entered(tool: Tool.Paint, from: String, to: String): Tool.Paint {
        if (to == from) return tool
        val base = if (from == OBJECT) tool.copy(
            edgeAngle = tool.fillKeepEdgeAngle, overColours = tool.fillKeepOverColours, guardOn = tool.fillKeepGuardOn,
        ) else tool
        val saved = if (to == OBJECT) base.copy(
            fillKeepEdgeAngle = tool.edgeAngle, fillKeepOverColours = tool.overColours, fillKeepGuardOn = tool.guardOn,
        ) else base
        val next = when (to) {
            BRUSH -> saved.copy(shape = saved.brushShape)
            FILL -> saved.copy(
                shape = saved.fillShape,
                edgeAngle = if (saved.fillShape == "regions" && saved.edgeAngle <= 0f) 30f else saved.edgeAngle,
            )
            BANDS -> saved.copy(shape = "height")
            else -> saved.copy(shape = "fill", edgeAngle = 0f, overColours = true, guardOn = false)
        }
        return next.copy(
            back = next.shape, objectMode = to == OBJECT,
            state = if (to == OBJECT && next.state == 0) 1 else next.state,
        )
    }

    /**
     * The tool to open paint [kind] with (menu or shortcut, filament / state [state]): the one
     * it was last left with ([last], session-only), so Done -> Preview -> paint again keeps the
     * mode, the per-mode shapes, the sizes and the fill settings (WSA r373: it reopened on
     * Brush / Sphere). Dropped as belonging to that visit: an armed eyedropper, the guard plane
     * (its height is per object) and hidden / locked layers.
     */
    fun reopened(last: Tool.Paint?, kind: String, state: Int): Tool.Paint =
        last?.takeIf { it.kind == kind }?.let {
            it.copy(state = state, shape = if (it.shape == "pick") it.back else it.shape, guardOn = false, hidden = 0, locked = 0)
        } ?: Tool.Paint(kind, state, 3f)
}
