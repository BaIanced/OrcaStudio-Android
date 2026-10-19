package app.orcaandroid.core

import org.json.JSONObject

/** How the Prepare screen switches plates. */
enum class PlateSwitcher { TABS, FILMSTRIP, CHIPS }

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
    /** Portrait: tool row collapsed into just the + button. */
    val toolsCollapsed: Boolean = false,
    /** Portrait floating tool row offset in dp (-1 = default at bottom left). */
    val toolsRowX: Float = -1f,
    val toolsRowY: Float = -1f,
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
    /** Hide the status and navigation bars in portrait too (landscape always hides them). */
    val fullscreenPortrait: Boolean = false,
    /** Portrait: the plate switcher sits on the grip above the settings panel instead of over the 3D view. */
    val platesOnHandle: Boolean = false,
    /** Size of the plate switcher alone (tabs, filmstrip tiles or chips). */
    val plateScale: Float = 1f,
    /** Real shadow (Prepare): how dark (0.1-1), how soft in mm of penumbra, and the depth bias in mm (acne vs. light under parts). */
    val shadowStrength: Float = 0.5f,
    val shadowSoft: Float = 6f,
    val shadowBias: Float = 2f,
    /** Key light of the 3D models: direction (azimuth about the plate from +X, elevation above the bed, degrees) and strength (1 = as designed). */
    val lightAzimuth: Float = -57.5f,
    val lightElevation: Float = 49f,
    val lightStrength: Float = 1f,
    /** Pinch zoom speed: exponent on the fingers' gap ratio (1 = the old 1:1 pinch, user 2026-10-17: too sensitive). */
    val pinchSpeed: Float = 0.6f,
    /** Mouse wheel / touchpad / Quest thumbstick zoom speed: exponent on the 15 % per wheel notch (user 2026-10-19: stick too fast). */
    val wheelSpeed: Float = 1f,
    /** Size of the Slice button on the portrait panel's grip; only the main UI size scales it too (user, 2026-10-17). */
    val sliceScale: Float = 1f,
) {
    fun toJson(): String = JSONObject()
        .put("plates", plates.name).put("plateLabel", plateLabel).put("overlayAlpha", overlayAlpha.toDouble())
        .put("toolsLeft", toolsLeft).put("toolsCollapsed", toolsCollapsed)
        .put("toolsRowX", toolsRowX.toDouble()).put("toolsRowY", toolsRowY.toDouble())
        .put("panelTucked", panelTucked).put("panelWidth", panelWidth.toDouble())
        .put("textScale", textScale.toDouble()).put("uiScale", uiScale.toDouble()).put("overlayScale", overlayScale.toDouble())
        .put("fullscreenPortrait", fullscreenPortrait).put("platesOnHandle", platesOnHandle).put("plateScale", plateScale.toDouble())
        .put("shadowStrength", shadowStrength.toDouble()).put("shadowSoft", shadowSoft.toDouble()).put("shadowBias", shadowBias.toDouble())
        .put("lightAzimuth", lightAzimuth.toDouble()).put("lightElevation", lightElevation.toDouble()).put("lightStrength", lightStrength.toDouble())
        .put("pinchSpeed", pinchSpeed.toDouble()).put("wheelSpeed", wheelSpeed.toDouble()).put("sliceScale", sliceScale.toDouble()).toString()

    companion object {
        val TEXT_SCALES = 0.7f..1.3f
        val UI_SCALES = 0.7f..1.3f
        val OVERLAY_SCALES = 0.6f..1.2f
        val PLATE_SCALES = 0.6f..1.6f
        val SLICE_SCALES = 0.5f..1.5f
        val SHADOW_STRENGTHS = 0.1f..1f
        val SHADOW_SOFTS = 0.5f..12f
        val SHADOW_BIASES = 0.2f..8f
        val LIGHT_AZIMUTHS = -180f..180f
        val LIGHT_ELEVATIONS = 20f..85f
        val LIGHT_STRENGTHS = 0.2f..2f
        val PINCH_SPEEDS = 0.2f..1f
        val WHEEL_SPEEDS = 0.1f..2f

        fun fromJson(json: String?): LayoutLab {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return LayoutLab()
            val d = LayoutLab()
            return LayoutLab(
                plates = runCatching { PlateSwitcher.valueOf(o.getString("plates")) }.getOrDefault(d.plates),
                plateLabel = o.optBoolean("plateLabel", d.plateLabel),
                overlayAlpha = o.optDouble("overlayAlpha", d.overlayAlpha.toDouble()).toFloat().coerceIn(0.2f, 1f),
                toolsLeft = o.optBoolean("toolsLeft", d.toolsLeft),
                toolsCollapsed = o.optBoolean("toolsCollapsed", d.toolsCollapsed),
                toolsRowX = o.optDouble("toolsRowX", d.toolsRowX.toDouble()).toFloat(),
                toolsRowY = o.optDouble("toolsRowY", d.toolsRowY.toDouble()).toFloat(),
                panelTucked = o.optBoolean("panelTucked", d.panelTucked),
                panelWidth = o.optDouble("panelWidth", 0.0).toFloat().coerceAtLeast(0f),
                textScale = o.optDouble("textScale", 1.0).toFloat().coerceIn(TEXT_SCALES.start, TEXT_SCALES.endInclusive),
                uiScale = o.optDouble("uiScale", 1.0).toFloat().coerceIn(UI_SCALES.start, UI_SCALES.endInclusive),
                overlayScale = o.optDouble("overlayScale", 1.0).toFloat().coerceIn(OVERLAY_SCALES.start, OVERLAY_SCALES.endInclusive),
                fullscreenPortrait = o.optBoolean("fullscreenPortrait", d.fullscreenPortrait),
                platesOnHandle = o.optBoolean("platesOnHandle", d.platesOnHandle),
                plateScale = o.optDouble("plateScale", 1.0).toFloat().coerceIn(PLATE_SCALES.start, PLATE_SCALES.endInclusive),
                shadowStrength = o.optDouble("shadowStrength", d.shadowStrength.toDouble()).toFloat().coerceIn(SHADOW_STRENGTHS.start, SHADOW_STRENGTHS.endInclusive),
                shadowSoft = o.optDouble("shadowSoft", d.shadowSoft.toDouble()).toFloat().coerceIn(SHADOW_SOFTS.start, SHADOW_SOFTS.endInclusive),
                shadowBias = o.optDouble("shadowBias", d.shadowBias.toDouble()).toFloat().coerceIn(SHADOW_BIASES.start, SHADOW_BIASES.endInclusive),
                lightAzimuth = o.optDouble("lightAzimuth", d.lightAzimuth.toDouble()).toFloat().coerceIn(LIGHT_AZIMUTHS.start, LIGHT_AZIMUTHS.endInclusive),
                lightElevation = o.optDouble("lightElevation", d.lightElevation.toDouble()).toFloat().coerceIn(LIGHT_ELEVATIONS.start, LIGHT_ELEVATIONS.endInclusive),
                lightStrength = o.optDouble("lightStrength", d.lightStrength.toDouble()).toFloat().coerceIn(LIGHT_STRENGTHS.start, LIGHT_STRENGTHS.endInclusive),
                sliceScale = o.optDouble("sliceScale", 1.0).toFloat().coerceIn(SLICE_SCALES.start, SLICE_SCALES.endInclusive),
                pinchSpeed = o.optDouble("pinchSpeed", d.pinchSpeed.toDouble()).toFloat().coerceIn(PINCH_SPEEDS.start, PINCH_SPEEDS.endInclusive),
                wheelSpeed = o.optDouble("wheelSpeed", d.wheelSpeed.toDouble()).toFloat().coerceIn(WHEEL_SPEEDS.start, WHEEL_SPEEDS.endInclusive),
            )
        }
    }
}
