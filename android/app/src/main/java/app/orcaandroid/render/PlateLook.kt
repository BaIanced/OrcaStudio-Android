package app.orcaandroid.render

/**
 * How a build plate looks in the 3D view (View menu "Plate surface"): base colour and surface texture per Orca bed type.
 * [texture]: 0 smooth (a faint satin sheen), 1 fine pebble grain (textured PEI), 2 powder grain (coarser, matte).
 * Colours are approximations of the real plates, not measured.
 */
class PlateLook(val r: Float, val g: Float, val b: Float, val texture: Int) {
    companion object {
        private val DEFAULT = PlateLook(0.55f, 0.56f, 0.58f, 0)

        fun of(bedType: String?): PlateLook = when (bedType) {
            "Cool Plate" -> PlateLook(0.62f, 0.68f, 0.74f, 0)
            "Textured Cool Plate" -> PlateLook(0.60f, 0.66f, 0.72f, 1)
            "Engineering Plate" -> PlateLook(0.70f, 0.68f, 0.63f, 0)
            "High Temp Plate" -> PlateLook(0.56f, 0.47f, 0.30f, 0)
            "Textured PEI Plate" -> PlateLook(0.62f, 0.51f, 0.31f, 2)
            "Supertack Plate" -> PlateLook(0.76f, 0.74f, 0.70f, 0)
            else -> DEFAULT
        }
    }
}
