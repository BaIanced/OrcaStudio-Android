package app.orcaandroid.core

import kotlin.math.pow

/** The per-slot options that make a filament slot a mix of other slots (upstream's mixed filament). */
object Mix {
    const val IS_MIXED = "filament_is_mixed"
    const val COMPONENTS = "filament_mixed_components"
    const val RATIOS = "filament_mixed_sublayer_ratios"
    val KEYS = setOf(IS_MIXED, COMPONENTS, RATIOS)

    fun isMixed(slot: FilamentSlot) = slot.overrides[IS_MIXED] == "1"

    /** [f]'s overrides, plus the mix recipe [same] (the saved slot holding the same preset) has when [f] has none (a 3MF carries none). */
    fun carry(f: FilamentSlot, same: FilamentSlot?): Map<String, String> =
        if (isMixed(f) || same == null) f.overrides else f.overrides + same.overrides.filterKeys { it in KEYS }

    /** (1-based component slot, share 0..1) pairs of a mixed slot. */
    fun parts(slot: FilamentSlot): List<Pair<Int, Float>> {
        if (!isMixed(slot)) return emptyList()
        val comps = slot.overrides[COMPONENTS].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        val ratios = slot.overrides[RATIOS].orEmpty().split(',').mapNotNull { it.trim().toFloatOrNull() }
        return comps.mapIndexed { i, c -> c to (ratios.getOrNull(i) ?: (1f / comps.size)) }
    }

    /** Blends [colors] by [weights] in linear light, like paint-free light mixing of the swatches. */
    fun blend(colors: List<String?>, weights: List<Float>): String {
        val total = weights.sum().takeIf { it > 0f } ?: return "#808080"
        val sum = FloatArray(3)
        colors.forEachIndexed { i, hex ->
            val v = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: 0x808080
            for (c in 0..2) {
                val s = ((v shr (16 - 8 * c)) and 0xFF) / 255f
                sum[c] += s.toDouble().pow(2.2).toFloat() * weights[i] / total
            }
        }
        val out = sum.map { (it.toDouble().pow(1 / 2.2) * 255).toInt().coerceIn(0, 255) }
        return "#%02X%02X%02X".format(out[0], out[1], out[2])
    }
}
