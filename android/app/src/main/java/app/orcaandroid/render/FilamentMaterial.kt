package app.orcaandroid.render

/**
 * How a filament's surface looks in the Prepare view's model shader (MESH_FS uMatA / uMatB).
 * [a] = highlight power, highlight strength, metallic tint (0 white highlight .. 1 tinted by the colour), sky reflection.
 * [b] = grazing sheen, wrap lighting (light reaching round the terminator, translucent plastics), diffuse factor,
 * transmission (realistic mode only: 0 opaque .. 1 clear; > 0 draws the part blended after the opaque ones).
 * [p] = the realistic render mode's physically based parameters (uMatP): perceptual roughness, metallic, clear coat,
 * clear coat roughness (Filament's standard material model); [b]'s sheen and wrap are used there too.
 * [BASIC] is the look every part had before materials existed.
 */
enum class FilamentMaterial(val a: FloatArray, val b: FloatArray, val p: FloatArray) {
    BASIC(floatArrayOf(36f, 0.16f, 0f, 0f), floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0.45f, 0f, 0f, 0f)),
    MATTE(floatArrayOf(8f, 0.03f, 0f, 0f), floatArrayOf(0.12f, 0.08f, 0.97f, 0f), floatArrayOf(0.85f, 0f, 0f, 0f)),
    SILK(floatArrayOf(18f, 0.6f, 0.85f, 0.45f), floatArrayOf(0.08f, 0f, 0.72f, 0f), floatArrayOf(0.32f, 0.7f, 0f, 0f)),
    GLOSSY(floatArrayOf(110f, 0.5f, 0f, 0.3f), floatArrayOf(0f, 0.12f, 1f, 0f), floatArrayOf(0.3f, 0f, 0.8f, 0.06f)),
    TRANSLUCENT(floatArrayOf(110f, 0.5f, 0f, 0.3f), floatArrayOf(0f, 0.6f, 1f, 0.7f), floatArrayOf(0.2f, 0f, 0.7f, 0.05f)),
    SATIN(floatArrayOf(22f, 0.12f, 0f, 0.04f), floatArrayOf(0.04f, 0.04f, 1f, 0f), floatArrayOf(0.4f, 0f, 0.15f, 0.3f));

    companion object {
        /**
         * The look of a filament from its preset name and its filament_type override, if any ("Bambu PLA Silk+",
         * "Generic PETG", "Bambu PLA Matte @BBL A1"). Words in the name win over the type: a "PETG Translucent"
         * is translucent, a "PLA Matte" matte.
         */
        fun of(preset: String, type: String? = null): FilamentMaterial {
            val s = "$preset ${type.orEmpty()}".lowercase()
            fun has(vararg w: String) = w.any { it in s }
            return when {
                has("silk", "metal", "galaxy", "sparkle", "glitter") -> SILK
                has("translucent", "transparent", "clear", "crystal") -> TRANSLUCENT
                has("matte", "-cf", " cf", "-gf", " gf", "wood", "marble", "stone", "aero") -> MATTE
                has("tpu", "tpe", "flex") -> MATTE
                has("petg", "pctg", "pet ", "pc ", "pc-", "gloss") -> GLOSSY
                has("abs", "asa", "pa ", "pa-", "nylon", "pa6", "pa12", "hips") -> SATIN
                else -> BASIC
            }
        }
    }
}
