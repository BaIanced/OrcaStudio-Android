package app.orcaandroid.core

/** How the Prepare screen switches plates. */
enum class PlateSwitcher { TABS, FILMSTRIP, CHIPS }

/**
 * The layout and 3D-view values the retired Layout lab used to offer as sliders, fixed at their old defaults
 * (T-027). The code behind a constant stays even where the constant makes a branch unreachable.
 */
object ViewDefaults {
    // --- Plain constants -----------------------------------------------------------------------------
    val PLATES = PlateSwitcher.TABS
    const val PLATE_LABEL = false
    /** Opacity of the control backgrounds over the 3D view. */
    const val OVERLAY_ALPHA = 0.85f
    const val TOOLS_LEFT = true
    const val TOOLS_COLLAPSED = false
    /** Portrait floating tool row offset in dp (-1 = default at bottom left). */
    const val TOOLS_ROW_X = -1f
    const val TOOLS_ROW_Y = -1f
    /** Text size on top of the system font size (the system font size covers it). */
    const val TEXT_SCALE = 1f
    /** Size of the controls floating over the 3D view, on top of the interface size. */
    const val OVERLAY_SCALE = 1f
    const val FULLSCREEN_PORTRAIT = false
    const val PLATES_ON_HANDLE = false
    const val PLATE_SCALE = 1f
    const val SLICE_SCALE = 1f
    const val SLICE_FLOATING = true
    const val ORBIT_INERTIA = 0.5f
    const val PLATE_HD_ALPHA = 1f
    const val PLATE_UNDER_ALPHA = 0f

    // --- Lighting and view-cube / pie-menu leftovers: removable after lighting review (UA-7) ------------
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
    // removable after lighting review (UA-7)
    const val VIEW_CUBE = false
    // removable after lighting review (UA-7)
    const val VIEW_CUBE_X = -1f
    // removable after lighting review (UA-7)
    const val VIEW_CUBE_Y = -1f
    // removable after lighting review (UA-7)
    const val PIE_MENU = false

    // --- Defaults and ranges of the values that are now real settings or state ---------------------------
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
