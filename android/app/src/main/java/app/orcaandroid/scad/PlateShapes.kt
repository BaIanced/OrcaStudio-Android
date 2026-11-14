package app.orcaandroid.scad

/**
 * What the + Shape and + Text dialogs ask for, as the one-shape [ShapeDesign] the builder would hold, so the object
 * they add reopens in the builder with its parameters (T-043). Pure: the controller renders and places it.
 */
object PlateShapes {
    /** The dialogs' own limits (PrepareDialogs sizeOk): a design wider than this is refused there. */
    const val MAX_MM = 1000.0

    /** The dialog's shape ids -> the builder's kinds (a cone is the builder's cone with a pointed top). */
    fun kind(shape: String) = when (shape) {
        "cylinder" -> Shape.Kind.CYLINDER
        "sphere" -> Shape.Kind.SPHERE
        "cone" -> Shape.Kind.CONE
        else -> Shape.Kind.BOX
    }

    /**
     * One [shape] of the box [w] x [d] x [h] (mm), standing on the plate at the design's origin. [round] becomes the
     * builder's edge size on kinds that have edges (box, cylinder, cone), kept under half the smallest side so the
     * rounded shape stays solid; a sphere has no edge to round.
     */
    fun primitive(shape: String, w: Double, d: Double, h: Double, round: Double, name: String): ShapeDesign {
        val kind = kind(shape)
        val base = ShapeDesign.create(kind, 1, name)
        val edge = if (kind in Shape.EDGED) round.coerceIn(0.0, minOf(w, d, h) / 2) else 0.0
        val extra = if (edge > 0.0) base.extra + (Shape.EDGE to edge) else base.extra
        return ShapeDesign(listOf(base.copy(size = D3(w, d, h), extra = extra)), nextId = 2)
    }

    /**
     * [text] as the builder's text shape: [height] mm tall (the box round its letters), [depth] mm thick. The builder
     * stretches the text to its box, so the width follows the letters: [natural] is the text's own box at size 10
     * ([ShapeDesign.textBody]); without it the width is a guess from the character count.
     */
    fun text(text: String, height: Double, depth: Double, natural: D3?, name: String): ShapeDesign {
        val base = ShapeDesign.create(Shape.Kind.TEXT, 1, name).copy(text = text.replace('\n', ' '))
        return ShapeDesign(listOf(base.copy(size = D3(textWidth(text, height, natural), height, depth))), nextId = 2)
    }

    /** The width of [text] at box height [height]: the natural box scaled to it, else 0.6 x height per character. */
    fun textWidth(text: String, height: Double, natural: D3?): Double {
        val w = if (natural != null && natural.x > 0.0 && natural.y > 0.0) natural.x * height / natural.y
            else 0.6 * height * text.trim().codePointCount(0, text.trim().length)
        return w.coerceIn(0.1, MAX_MM)
    }

    /** OpenSCAD code of [text] at size 10, whose box [textWidth] reads. */
    fun textProbe(text: String) = ShapeDesign.textBody(text)

    /** The design's name for [text]: its first 32 characters with what a file name can't hold turned into "_" ("Text" when blank). */
    fun textName(text: String): String {
        val t = text.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "_")
        val cut = if (t.length <= 32) t else t.substring(0, if (Character.isHighSurrogate(t[31])) 31 else 32)
        return cut.trim().ifEmpty { "Text" }
    }
}
