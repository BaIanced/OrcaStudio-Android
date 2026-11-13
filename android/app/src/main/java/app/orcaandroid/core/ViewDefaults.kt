package app.orcaandroid.core

/** How the Prepare screen switches plates. */
enum class PlateSwitcher { TABS, FILMSTRIP, CHIPS }

/**
 * The layout and 3D-view values the retired Layout lab used to offer as sliders, fixed at their old defaults
 * (T-027). The code behind a constant stays even where the constant makes a branch unreachable.
 */
object ViewDefaults {
    // --- Plain constants -----------------------------------------------------------------------------
    const val PLATE_LABEL = false
    const val SLICE_FLOATING = true
    const val ORBIT_INERTIA = 0.5f
    const val PLATE_HD_ALPHA = 1f

    // --- Lighting leftovers: removable after lighting review (UA-7) ------------
    // removable after lighting review (UA-7)
    const val SHADOW_STRENGTH = 0.5f
    // removable after lighting review (UA-7)
    const val SHADOW_SOFT_MM = 6f
    // removable after lighting review (UA-7)
    const val SHADOW_BIAS_MM = 2f
    // removable after lighting review (UA-7)
    const val LIGHT_STRENGTH = 1f
    // removable after lighting review (UA-7)
    const val CAMERA_LIGHT = 0f

    // --- Defaults and ranges of the values that are now real settings or state ---------------------------
    /** How the plates are switched (offered: tabs and filmstrip). */
    val PLATES = PlateSwitcher.TABS
    /** Opacity of the control backgrounds over the 3D view. */
    const val OVERLAY_ALPHA = 0.85f
    val OVERLAY_ALPHAS = 0.2f..1f
    /** HD plate opacity seen from below (0 = the plate is invisible from underneath). */
    const val PLATE_UNDER_ALPHA = 0f
    val PLATE_UNDER_ALPHAS = 0f..1f
    /** Size of the plate switcher alone. */
    const val PLATE_SCALE = 1f
    val PLATE_SCALES = 0.6f..1.6f
    /** Size of the controls floating over the 3D view, on top of the interface size. */
    const val OVERLAY_SCALE = 1f
    val OVERLAY_SCALES = 0.6f..1.2f
    /** Size of the Slice button, on top of the interface size. */
    const val SLICE_SCALE = 1f
    val SLICE_SCALES = 0.5f..1.5f
    /** Text size on top of the system font size. */
    const val TEXT_SCALE = 1f
    val TEXT_SCALES = 0.7f..1.3f
    const val FULLSCREEN_PORTRAIT = false
    const val PLATES_ON_HANDLE = false
    const val TOOLS_LEFT = true
    const val TOOLS_COLLAPSED = false
    /** Portrait floating tool row offset in dp (-1 = default at bottom left). */
    const val TOOLS_ROW_X = -1f
    const val TOOLS_ROW_Y = -1f

    /** Interface size steps (scales the whole interface, buttons, icons, spacing and text). */
    val INTERFACE_SIZES = listOf(0.85f, 0.9f, 1f, 1.1f, 1.2f)
    const val INTERFACE_SIZE = 1f
    /** The tool button's size before scaling; its effective size must stay at or above [TOOL_BUTTON_MIN_DP]. */
    const val TOOL_BUTTON_DP = 48f
    const val TOOL_BUTTON_MIN_DP = 40f

    const val PINCH_SPEED = 0.6f
    val PINCH_SPEEDS = 0.2f..1f
    const val WHEEL_SPEED = 1f
    val WHEEL_SPEEDS = 0.1f..2f

    /** Key light direction (removable after lighting review, UA-7): azimuth about the plate from +X, elevation above the bed, degrees. */
    const val LIGHT_AZIMUTH = -57.5f
    const val LIGHT_ELEVATION = 49f
    val LIGHT_AZIMUTHS = -180f..180f
    val LIGHT_ELEVATIONS = 20f..85f

    /** The step of [INTERFACE_SIZES] nearest to [v]. */
    fun snapInterfaceSize(v: Float): Float = INTERFACE_SIZES.minByOrNull { kotlin.math.abs(it - v) } ?: INTERFACE_SIZE
}
