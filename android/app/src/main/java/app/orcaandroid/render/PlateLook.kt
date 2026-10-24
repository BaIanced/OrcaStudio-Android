package app.orcaandroid.render

/**
 * Upstream's bed-type decals (Layout lab "HD plate"): the vector lettering strips laid on a plate's left and front edges,
 * rasterised once to assets/plate_decals/<name>.png. [left]/[bottom] are asset names, null = none.
 */
class PlateLook(val left: String?, val bottom: String?) {
    companion object {
        fun of(bedType: String?): PlateLook = when (bedType) {
            "Cool Plate" -> PlateLook("pc_left", "pc_bottom")
            "Textured Cool Plate" -> PlateLook("pct_left", "pc_bottom")
            "Engineering Plate" -> PlateLook("ep_left", "ep_bottom")
            "High Temp Plate" -> PlateLook("pei_left", "pei_bottom")
            "Textured PEI Plate" -> PlateLook("pte_left", "pte_bottom")
            "Supertack Plate" -> PlateLook("st_left", "st_bottom")
            else -> PlateLook(null, null)
        }
    }
}
