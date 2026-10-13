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
    /** Wide layouts: the settings panel's width in dp, set by dragging its edge (0 = default). */
    val panelWidth: Float = 0f,
    /** Text size, on top of the system font size. */
    val textScale: Float = 1f,
    /** Size of the whole interface (buttons, icons, spacing and text). */
    val uiScale: Float = 1f,
    /** Size of the controls floating over the 3D view only. */
    val overlayScale: Float = 1f,
    val scadView: ScadView = ScadView.CANVAS,
    /** Hide the status and navigation bars in portrait too (landscape always hides them). */
    val fullscreenPortrait: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("plates", plates.name).put("plateLabel", plateLabel).put("overlayAlpha", overlayAlpha.toDouble())
        .put("toolsLeft", toolsLeft).put("panelTucked", panelTucked).put("panelWidth", panelWidth.toDouble())
        .put("textScale", textScale.toDouble()).put("uiScale", uiScale.toDouble()).put("overlayScale", overlayScale.toDouble())
        .put("scadView", scadView.name)
        .put("fullscreenPortrait", fullscreenPortrait).toString()

    companion object {
        val TEXT_SCALES = 0.7f..1.3f
        val UI_SCALES = 0.7f..1.3f
        val OVERLAY_SCALES = 0.6f..1.2f

        fun fromJson(json: String?): LayoutLab {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return LayoutLab()
            val d = LayoutLab()
            return LayoutLab(
                plates = runCatching { PlateSwitcher.valueOf(o.getString("plates")) }.getOrDefault(d.plates),
                plateLabel = o.optBoolean("plateLabel", d.plateLabel),
                overlayAlpha = o.optDouble("overlayAlpha", d.overlayAlpha.toDouble()).toFloat().coerceIn(0.2f, 1f),
                toolsLeft = o.optBoolean("toolsLeft", d.toolsLeft),
                panelTucked = o.optBoolean("panelTucked", d.panelTucked),
                panelWidth = o.optDouble("panelWidth", 0.0).toFloat().coerceAtLeast(0f),
                textScale = o.optDouble("textScale", 1.0).toFloat().coerceIn(TEXT_SCALES.start, TEXT_SCALES.endInclusive),
                uiScale = o.optDouble("uiScale", 1.0).toFloat().coerceIn(UI_SCALES.start, UI_SCALES.endInclusive),
                overlayScale = o.optDouble("overlayScale", 1.0).toFloat().coerceIn(OVERLAY_SCALES.start, OVERLAY_SCALES.endInclusive),
                scadView = runCatching { ScadView.valueOf(o.getString("scadView")) }.getOrDefault(d.scadView),
                fullscreenPortrait = o.optBoolean("fullscreenPortrait", d.fullscreenPortrait),
            )
        }
    }
}
