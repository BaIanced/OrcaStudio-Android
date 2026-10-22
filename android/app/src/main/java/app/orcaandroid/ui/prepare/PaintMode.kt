package app.orcaandroid.ui.prepare

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
}
