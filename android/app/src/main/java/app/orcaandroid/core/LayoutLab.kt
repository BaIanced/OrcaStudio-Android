package app.orcaandroid.core

import org.json.JSONObject

/** How the Prepare screen switches plates. */
enum class PlateSwitcher { TABS, FILMSTRIP, CHIPS }

/** How the OpenSCAD editor shows the design: alone on the main canvas, or as a ghost on the real plate. */
enum class ScadView { CANVAS, GHOST }

/**
 * Temporary "Layout lab" (user, 2026-10-13): layout variants to try on the device side by side.
 * Once the user has picked, the losing variants and this class go away.
 */
data class LayoutLab(
    val plates: PlateSwitcher = PlateSwitcher.TABS,
    /** "Plate n of m" label at the top left (filmstrip). */
    val plateLabel: Boolean = false,
    /** Opacity of the control backgrounds over the 3D view. */
    val overlayAlpha: Float = 0.85f,
    /** Portrait: tool buttons in a column on the left instead of a row at the bottom. */
    val toolsLeft: Boolean = true,
    /** Wide layouts: the settings panel is tucked away to a thin edge. */
    val panelTucked: Boolean = false,
    val scadView: ScadView = ScadView.CANVAS,
    /** Hide the status and navigation bars in portrait too (landscape always hides them). */
    val fullscreenPortrait: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("plates", plates.name).put("plateLabel", plateLabel).put("overlayAlpha", overlayAlpha.toDouble())
        .put("toolsLeft", toolsLeft).put("panelTucked", panelTucked).put("scadView", scadView.name)
        .put("fullscreenPortrait", fullscreenPortrait).toString()

    companion object {
        fun fromJson(json: String?): LayoutLab {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return LayoutLab()
            val d = LayoutLab()
            return LayoutLab(
                plates = runCatching { PlateSwitcher.valueOf(o.getString("plates")) }.getOrDefault(d.plates),
                plateLabel = o.optBoolean("plateLabel", d.plateLabel),
                overlayAlpha = o.optDouble("overlayAlpha", d.overlayAlpha.toDouble()).toFloat().coerceIn(0.2f, 1f),
                toolsLeft = o.optBoolean("toolsLeft", d.toolsLeft),
                panelTucked = o.optBoolean("panelTucked", d.panelTucked),
                scadView = runCatching { ScadView.valueOf(o.getString("scadView")) }.getOrDefault(d.scadView),
                fullscreenPortrait = o.optBoolean("fullscreenPortrait", d.fullscreenPortrait),
            )
        }
    }
}
