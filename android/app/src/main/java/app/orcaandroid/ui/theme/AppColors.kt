package app.orcaandroid.ui.theme

import android.app.Activity
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import app.orcaandroid.R
import app.orcaandroid.core.ThemeMode
import app.orcaandroid.core.ThemeSpec

// Prusa-style orange on neutral greys (user, 2026-10-14; values from prusa3d.com's CSS: brand #FD5000,
// buttons #FF7300, text #1E1F24). Greys are set explicitly: Material's defaults are purple-tinted.
// Tertiary (modified settings, warnings) is blue so it stays apart from the orange. Elevated surfaces are
// tinted with the text colour, not the accent, so panels stay neutral grey instead of turning peach.
private val LightColors = lightColorScheme(
    primary = Color(0xFFD84000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF3A1100),
    secondary = Color(0xFF60646C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E5EA),
    onSecondaryContainer = Color(0xFF1E1F24),
    tertiary = Color(0xFF2F6FD0),
    tertiaryContainer = Color(0xFFD6E4FF),
    onTertiaryContainer = Color(0xFF0B2A55),
    background = Color(0xFFFCFCFD),
    onBackground = Color(0xFF1E1F24),
    surface = Color(0xFFFCFCFD),
    onSurface = Color(0xFF1E1F24),
    surfaceTint = Color(0xFF1E1F24),
    surfaceVariant = Color(0xFFE4E5EA),
    onSurfaceVariant = Color(0xFF60646C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F6F8),
    surfaceContainer = Color(0xFFF0F0F3),
    surfaceContainerHigh = Color(0xFFEDEEF0),
    surfaceContainerHighest = Color(0xFFE4E5EA),
    outline = Color(0xFF8B8D98),
    outlineVariant = Color(0xFFD9DAE0),
    error = Color(0xFFCE2C31),
    errorContainer = Color(0xFFFFDBDC),
    onErrorContainer = Color(0xFF64181B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7300),
    onPrimary = Color(0xFF1E1F24),
    primaryContainer = Color(0xFF5C2400),
    onPrimaryContainer = Color(0xFFFFDBCA),
    secondary = Color(0xFFB9BBC6),
    onSecondary = Color(0xFF1E1F24),
    secondaryContainer = Color(0xFF3A3B42),
    onSecondaryContainer = Color(0xFFF0F0F3),
    tertiary = Color(0xFF7FB3FF),
    onTertiary = Color(0xFF0B2A55),
    tertiaryContainer = Color(0xFF1F3A5F),
    onTertiaryContainer = Color(0xFFD6E4FF),
    background = Color(0xFF1A1B1F),
    onBackground = Color(0xFFEDEEF0),
    surface = Color(0xFF1A1B1F),
    onSurface = Color(0xFFEDEEF0),
    surfaceTint = Color(0xFFEDEEF0),
    surfaceVariant = Color(0xFF3A3B42),
    onSurfaceVariant = Color(0xFFB9BBC6),
    surfaceContainerLowest = Color(0xFF141518),
    surfaceContainerLow = Color(0xFF1E1F24),
    surfaceContainer = Color(0xFF222328),
    surfaceContainerHigh = Color(0xFF2B2C32),
    surfaceContainerHighest = Color(0xFF36373D),
    outline = Color(0xFF7F818C),
    outlineVariant = Color(0xFF45464E),
    error = Color(0xFFFF5A5F),
    onError = Color(0xFF3B0A0C),
    errorContainer = Color(0xFF64181B),
    onErrorContainer = Color(0xFFFFD9D9),
)

/** The default Prusa scheme without presets or custom colours: the design panel uses it so it stays readable. */
fun plainScheme(dark: Boolean) = if (dark) DarkColors else LightColors

/**
 * The colour roles a user picks (as in the colour mock-up, user 2026-10-14). Setting one fills the related
 * Material slots too ([with]); they are applied in this order, so a later role sees the earlier ones.
 */
enum class Role(val key: String, @StringRes val label: Int, @StringRes val hint: Int, val read: (ColorScheme) -> Color) {
    BACKGROUND("background", R.string.role_background, R.string.role_background_hint, { it.background }),
    SURFACE("surface", R.string.role_surface, R.string.role_surface_hint, { it.surfaceContainerHigh }),
    MUTED("muted", R.string.role_muted, R.string.role_muted_hint, { it.onSurfaceVariant }),
    BORDER("border", R.string.role_border, R.string.role_border_hint, { it.outlineVariant }),
    TEXT("text", R.string.role_text, R.string.role_text_hint, { it.onSurface }),
    ACCENT("accent", R.string.role_accent, R.string.role_accent_hint, { it.primary }),
    ACCENT_SOFT("accent_soft", R.string.role_accent_soft, R.string.role_accent_soft_hint, { it.primaryContainer }),
    STATUS("status", R.string.role_status, R.string.role_status_hint, { it.tertiary }),
}

/**
 * Colour presets: the Prusa default and the three dark palettes of the user's mock-up (2026-10-14). The light
 * versions of those are derived ([lightFrom]): same hues, light neutrals, accents darkened to stay readable.
 */
enum class ThemePreset(val key: String, @StringRes val label: Int, private val palette: Map<Role, Long>?) {
    PRUSA(ThemeSpec.DEFAULT_PRESET, R.string.preset_prusa, null),
    PRUSA_INDUSTRIAL("prusa_industrial", R.string.preset_prusa_industrial, mapOf(
        Role.BACKGROUND to 0xFF18181B, Role.SURFACE to 0xFF27272A, Role.BORDER to 0xFF3F3F46, Role.MUTED to 0xFFA1A1AA,
        Role.TEXT to 0xFFF4F4F5, Role.ACCENT to 0xFFFA6B05, Role.STATUS to 0xFFEAB308)),
    PRINTABLES_NIGHT("printables_night", R.string.preset_printables_night, mapOf(
        Role.BACKGROUND to 0xFF0F172A, Role.SURFACE to 0xFF1E293B, Role.BORDER to 0xFF334155, Role.MUTED to 0xFF94A3B8,
        Role.TEXT to 0xFFF8FAFC, Role.ACCENT to 0xFFFF7F00, Role.STATUS to 0xFF0091FF)),
    PRUSA_MINIMAL("prusa_minimal", R.string.preset_prusa_minimal, mapOf(
        Role.BACKGROUND to 0xFF141210, Role.SURFACE to 0xFF1C1917, Role.BORDER to 0xFF292524, Role.MUTED to 0xFF78716C,
        Role.TEXT to 0xFFFAFAF9, Role.ACCENT to 0xFFEB5403, Role.STATUS to 0xFFD97706));

    /** The preset's role colours in the given mode, or null for the hand-made Prusa schemes. */
    fun roles(dark: Boolean): Map<Role, Color>? = palette?.mapValues { Color(it.value) }?.let { if (dark) it else lightFrom(it) }

    /** The full scheme of this preset (without custom colours). */
    fun scheme(dark: Boolean): ColorScheme = plainScheme(dark).let { base -> roles(dark)?.let { base.withRoles(it, dark) } ?: base }

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: PRUSA
    }
}

/**
 * One colour the user can change; [read] gives its value when not changed. A text or icon slot names the slot it
 * sits [on]: when only that one is changed, the default follows it if it would no longer be readable.
 */
class Slot(val key: String, @StringRes val label: Int, @StringRes val hint: Int = 0, val on: Slot? = null, val read: (ColorScheme) -> Color)

private val ROLE_SLOTS = Role.entries.map { r -> Slot(r.key, r.label, r.hint, read = r.read) }

val NAV_BAR = Slot("bar", R.string.slot_nav_bar) { it.surfaceContainer }
val NAV_PILL = Slot("pill", R.string.slot_nav_pill) { it.secondaryContainer }
val NAV_SELECTED = Slot("selected", R.string.slot_nav_selected, on = NAV_PILL) { it.onSecondaryContainer }
val NAV_ICONS = Slot("icons", R.string.slot_nav_icons, on = NAV_BAR) { it.onSurfaceVariant }

val VIEW_BACKGROUND = Slot("background", R.string.slot_view_background) { it.surfaceContainerHighest }
val VIEW_GRID = Slot("grid", R.string.slot_view_grid) { it.outline }
val VIEW_ACTIVE_PLATE = Slot("active_plate", R.string.slot_view_active_plate) { it.primary }
// Not primary: an orange theme would hide the selection on orange parts.
val VIEW_SELECTION = Slot("selection", R.string.slot_view_selection) { it.tertiary }
val VIEW_PART = Slot("part", R.string.slot_view_part) { Color(0xFFF59433) }

val BADGE = Slot("badge", R.string.slot_badge) { it.secondaryContainer }
val BADGE_TEXT = Slot("badge_text", R.string.slot_badge_text, on = BADGE) { it.onSecondaryContainer }
val BADGE_ACTIVE = Slot("active", R.string.slot_badge_active) { it.primary }
val BADGE_ACTIVE_TEXT = Slot("active_text", R.string.slot_badge_active_text, on = BADGE_ACTIVE) { it.onPrimary }

/**
 * Parts of the app with colours of their own. Regions with the role slots get their own Material scheme
 * ([Themed]); the others (tab bar, 3D view, plate badges) read their slots directly with [AppColors.color].
 */
enum class Region(val key: String, @StringRes val label: Int, val slots: List<Slot>) {
    GLOBAL("global", R.string.region_global, ROLE_SLOTS),
    NAV("nav", R.string.region_nav, listOf(NAV_BAR, NAV_PILL, NAV_SELECTED, NAV_ICONS)),
    PREPARE_PANEL("prepare_panel", R.string.region_prepare_panel, ROLE_SLOTS),
    PREPARE_TOOLS("prepare_tools", R.string.region_prepare_tools, ROLE_SLOTS),
    PREVIEW_PANEL("preview_panel", R.string.region_preview_panel, ROLE_SLOTS),
    PREVIEW_TOOLS("preview_tools", R.string.region_preview_tools, ROLE_SLOTS),
    VIEW_3D("view3d", R.string.region_view3d, listOf(VIEW_BACKGROUND, VIEW_GRID, VIEW_ACTIVE_PLATE, VIEW_SELECTION, VIEW_PART)),
    BADGES("badges", R.string.region_badges, listOf(BADGE, BADGE_TEXT, BADGE_ACTIVE, BADGE_ACTIVE_TEXT)),
    DEVICE("device", R.string.region_device, ROLE_SLOTS),
    MODELS("models", R.string.region_models, ROLE_SLOTS),
    MORE("more", R.string.region_more, ROLE_SLOTS),
    EDITOR("editor", R.string.region_editor, ROLE_SLOTS),
    OPENSCAD("openscad", R.string.region_openscad, ROLE_SLOTS),
    DIALOGS("dialogs", R.string.region_dialogs, ROLE_SLOTS);

    val usesRoles get() = slots === ROLE_SLOTS
}

/** The resolved colours: the global scheme (preset + the user's global colours) and per-region schemes and slots. */
@Stable
class AppColors(val dark: Boolean, base: ColorScheme, val overrides: Map<String, Map<String, Int>>) {
    private fun roles(r: Region): Map<Role, Color> = if (!r.usesRoles) emptyMap() else
        overrides[r.key].orEmpty().mapNotNull { (k, v) -> Role.entries.firstOrNull { it.key == k }?.let { it to Color(v) } }.toMap()

    val global: ColorScheme = base.withRoles(roles(Region.GLOBAL), dark)
    private val schemes = HashMap<Region, ColorScheme>()

    fun scheme(r: Region): ColorScheme = if (r == Region.GLOBAL) global else schemes.getOrPut(r) { global.withRoles(roles(r), dark) }

    fun color(r: Region, slot: Slot): Color {
        overrides[r.key]?.get(slot.key)?.let { return Color(it) }
        val default = slot.read(scheme(r))
        val back = slot.on?.takeIf { isCustom(r, it) }?.let { color(r, it) } ?: return default
        // E.g. a dark tab bar in light mode: grey labels would vanish, so they turn light (a bit muted, like before).
        return if (contrast(default, back) >= 3f) default else onColor(back).let { if (slot == NAV_ICONS) lerp(it, back, 0.2f) else it }
    }

    fun isCustom(r: Region, slot: Slot) = overrides[r.key]?.containsKey(slot.key) == true

    fun customCount(r: Region) = overrides[r.key]?.size ?: 0
}

val LocalAppColors = staticCompositionLocalOf { AppColors(false, LightColors, emptyMap()) }

/** [content] in the colours of [region]; [fill] paints the region's background behind it (whole screens). */
@Composable
fun Themed(region: Region, modifier: Modifier = Modifier, fill: Boolean = false, content: @Composable () -> Unit) {
    val scheme = LocalAppColors.current.scheme(region)
    MaterialTheme(colorScheme = scheme) {
        if (fill) Surface(modifier, color = scheme.background, contentColor = scheme.onSurface, content = content)
        else CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

@Composable
fun OrcaTheme(mode: ThemeMode, dynamicColor: Boolean, spec: ThemeSpec, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val base = remember(dark, dynamicColor, spec.preset) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else ThemePreset.of(spec.preset).scheme(dark)
    }
    val colors = remember(base, dark, spec) { AppColors(dark, base, spec.overrides(dark)) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        // From the colours, not the mode: a custom background may be light in dark mode.
        val lightBars = colors.global.background.luminance() > 0.5f
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = colors.global, content = content)
    }
}

// --- Deriving Material slots from the roles --------------------------------------------------------

fun ColorScheme.withRoles(roles: Map<Role, Color>, dark: Boolean): ColorScheme =
    Role.entries.fold(this) { s, r -> roles[r]?.let { s.with(r, it, dark) } ?: s }

private fun ColorScheme.with(role: Role, c: Color, dark: Boolean): ColorScheme = when (role) {
    Role.BACKGROUND -> readableOn(c).copy(
        background = c, surface = c, inverseOnSurface = c,
        surfaceContainerLowest = if (onColor(c) == Color.White) lerp(c, Color.Black, 0.3f) else lerp(c, Color.White, 0.7f),
        surfaceContainerLow = lerp(c, surfaceContainerHigh, 0.3f),
        surfaceContainer = lerp(c, surfaceContainerHigh, 0.55f),
        surfaceDim = lerp(c, Color.Black, 0.1f), surfaceBright = lerp(c, surfaceContainerHigh, 0.8f),
    )
    Role.SURFACE -> copy(
        surfaceContainerHigh = c,
        surfaceContainerLow = lerp(background, c, 0.3f),
        surfaceContainer = lerp(background, c, 0.55f),
        surfaceContainerHighest = lerp(c, outlineVariant, 0.45f),
        secondaryContainer = lerp(c, outlineVariant, 0.6f),
        surfaceBright = lerp(background, c, 0.8f),
    )
    Role.MUTED -> copy(onSurfaceVariant = c, secondary = c, onSecondary = onColor(c), outline = lerp(outlineVariant, c, 0.45f))
    Role.BORDER -> copy(
        outlineVariant = c, surfaceVariant = c, outline = lerp(c, onSurfaceVariant, 0.45f),
        surfaceContainerHighest = lerp(surfaceContainerHigh, c, 0.45f),
        secondaryContainer = lerp(surfaceContainerHigh, c, 0.6f),
    )
    Role.TEXT -> copy(onBackground = c, onSurface = c, onSecondaryContainer = c, inverseSurface = c, surfaceTint = c)
    Role.ACCENT -> copy(
        primary = c, onPrimary = onColor(c), inversePrimary = c,
        primaryContainer = lerp(c, background, if (dark) 0.65f else 0.78f),
        onPrimaryContainer = lerp(c, if (dark) Color.White else Color.Black, 0.7f),
    )
    Role.ACCENT_SOFT -> copy(primaryContainer = c, onPrimaryContainer = if (contrast(c, onSurface) >= 4.5f) onSurface else onColor(c))
    Role.STATUS -> copy(
        tertiary = c, onTertiary = onColor(c),
        tertiaryContainer = lerp(c, background, if (dark) 0.65f else 0.78f),
        onTertiaryContainer = lerp(c, if (dark) Color.White else Color.Black, 0.7f),
    )
}

/**
 * A background the text no longer reads on (e.g. a dark Device tab in light mode): text, cards, lines and the
 * soft accents turn to the other side, so only Background has to be set. Text or Muted set after it still win.
 */
private fun ColorScheme.readableOn(c: Color): ColorScheme {
    if (contrast(onSurface, c) >= 4.5f) return this
    val t = onColor(c)
    val darkBg = t == Color.White
    return copy(
        onBackground = t, onSurface = t, onSecondaryContainer = t, inverseSurface = t, surfaceTint = t,
        onSurfaceVariant = lerp(t, c, 0.35f), secondary = lerp(t, c, 0.35f), onSecondary = c,
        outline = lerp(c, t, 0.45f), outlineVariant = lerp(c, t, 0.2f), surfaceVariant = lerp(c, t, 0.2f),
        surfaceContainerHigh = lerp(c, t, 0.1f), surfaceContainerHighest = lerp(c, t, 0.16f), secondaryContainer = lerp(c, t, 0.2f),
        primaryContainer = lerp(primary, c, if (darkBg) 0.65f else 0.78f), onPrimaryContainer = lerp(primary, t, 0.7f),
        tertiaryContainer = lerp(tertiary, c, if (darkBg) 0.65f else 0.78f), onTertiaryContainer = lerp(tertiary, t, 0.7f),
    )
}

private val INK = Color(0xFF1E1F24)

/** Dark ink or white, whichever reads better on [c]. */
fun onColor(c: Color): Color = if (contrast(c, INK) >= contrast(c, Color.White)) INK else Color.White

/** WCAG contrast ratio of two opaque colours. */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun hsl(c: Color) = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }

/** A dark palette's light counterpart: neutrals in the background's hue, accents darkened to 3:1 on the background. */
private fun lightFrom(dark: Map<Role, Color>): Map<Role, Color> {
    val (h, s) = hsl(dark.getValue(Role.BACKGROUND))
    fun neutral(l: Float, sat: Float = s) = Color.hsl(h, sat.coerceIn(0f, 1f), l)
    val bg = neutral(0.985f)
    fun readable(c: Color): Color {
        val (ch, cs, cl) = hsl(c)
        var l = cl
        var out = c
        while (contrast(out, bg) < 3f && l > 0.1f) { l -= 0.02f; out = Color.hsl(ch, cs, l) }
        return out
    }
    return mapOf(
        Role.BACKGROUND to bg, Role.SURFACE to neutral(0.93f), Role.BORDER to neutral(0.85f),
        Role.MUTED to neutral(0.40f, s * 0.6f), Role.TEXT to neutral(0.12f),
        Role.ACCENT to readable(dark.getValue(Role.ACCENT)), Role.STATUS to readable(dark.getValue(Role.STATUS)),
    )
}
