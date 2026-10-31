package app.orcaandroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Settings group families (user 2026-10-31: "different color headings for certain things"): each heading gets the hue of
 * what it controls, so a long page reads at a glance. Light/dark pairs keep the text readable on either theme.
 */
enum class SettingFamily(val light: Long, val dark: Long, private val words: List<String>) {
    TEMPERATURE(0xFFC62828, 0xFFFF8A80, listOf("temperature", "cooling", "fan", "heat", "chamber")),
    SPEED(0xFF2E7D32, 0xFF81C784, listOf("speed", "acceleration", "jerk", "travel", "volumetric")),
    SUPPORT(0xFF6A1B9A, 0xFFCE93D8, listOf("support", "raft", "brim", "skirt", "prime tower", "adhesion", "flush")),
    STRENGTH(0xFFB26A00, 0xFFFFCC80, listOf("wall", "shell", "infill", "top", "bottom", "strength", "bridg", "overhang")),
    QUALITY(0xFF1565C0, 0xFF90CAF9, listOf("layer", "line width", "seam", "precision", "ironing", "quality", "fuzzy")),
    FILAMENT(0xFF00796B, 0xFF80CBC4, listOf("retraction", "filament", "extru", "flow", "nozzle", "wipe", "z hop", "lift")),
    ;

    fun color(darkTheme: Boolean) = Color(if (darkTheme) dark else light)

    companion object {
        /** The family of an untranslated group title ("Print temperature", "Page › Group" when filtered); null = no family. */
        fun of(title: String): SettingFamily? {
            val t = title.substringAfterLast('›').lowercase()
            return entries.firstOrNull { f -> f.words.any { it in t } }
        }
    }
}

/** A settings group heading: tinted band with the family's colour bar, falling back to the theme's primary colour. */
@Composable
fun SettingHeading(text: String, family: SettingFamily?, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val hue = family?.color(dark) ?: MaterialTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)
            .background(hue.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(28.dp).background(hue, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)))
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.titleSmall, color = hue)
    }
}
