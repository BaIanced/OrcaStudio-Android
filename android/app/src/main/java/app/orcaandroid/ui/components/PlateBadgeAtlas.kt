package app.orcaandroid.ui.components

import app.orcaandroid.ui.theme.BADGE
import app.orcaandroid.ui.theme.BADGE_ACTIVE
import app.orcaandroid.ui.theme.BADGE_ACTIVE_TEXT
import app.orcaandroid.ui.theme.BADGE_TEXT
import app.orcaandroid.ui.theme.LocalAppColors
import app.orcaandroid.ui.theme.Region
import app.orcaandroid.ui.theme.Slot
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import app.orcaandroid.render.BadgeAtlas
import app.orcaandroid.render.PlateBadge

/** The plate badge art for [plates] plates in the theme's colours (icons in [PlateBadge] order, then the numbers). */
@Composable
fun rememberBadgeAtlas(plates: Int): BadgeAtlas {
    val appColors = LocalAppColors.current
    val density = LocalDensity.current
    // Same order as PlateBadge: DELETE, ARRANGE, MENU.
    val icons = listOf(Icons.Default.Delete, Icons.Default.AutoAwesomeMosaic, Icons.Default.MoreVert).map { rememberVectorPainter(it) }
    fun slot(s: Slot) = appColors.color(Region.BADGES, s)
    val back = slot(BADGE)
    val fore = slot(BADGE_TEXT)
    val activeBack = slot(BADGE_ACTIVE)
    val activeFore = slot(BADGE_ACTIVE_TEXT)
    return remember(plates, back, fore, activeBack, activeFore) {
        val cell = BadgeAtlas.CELL.toFloat()
        val rows = (BadgeAtlas.cells(plates) + BadgeAtlas.COLUMNS - 1) / BadgeAtlas.COLUMNS
        val bitmap = ImageBitmap(BadgeAtlas.COLUMNS * BadgeAtlas.CELL, rows * BadgeAtlas.CELL)
        val margin = cell * 0.04f
        val pad = cell * 0.2f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            fun badge(index: Int, color: Color, content: DrawScope.() -> Unit) =
                translate((index % BadgeAtlas.COLUMNS) * cell, (index / BadgeAtlas.COLUMNS) * cell) {
                    drawRoundRect(color, Offset(margin, margin), Size(cell - 2 * margin, cell - 2 * margin), CornerRadius(cell * 0.22f))
                    content()
                }
            icons.forEachIndexed { i, icon ->
                badge(i, back) { translate(pad, pad) { with(icon) { draw(Size(cell - 2 * pad, cell - 2 * pad), colorFilter = ColorFilter.tint(fore)) } } }
            }
            for (plate in 0 until plates) for (active in listOf(false, true)) {
                badge(BadgeAtlas.numberCell(plate, active), if (active) activeBack else back) {
                    val label = "${plate + 1}"
                    text.color = (if (active) activeFore else fore).toArgb()
                    text.textSize = cell * if (label.length > 1) 0.5f else 0.62f
                    drawContext.canvas.nativeCanvas.drawText(label, cell / 2, cell / 2 - (text.ascent() + text.descent()) / 2, text)
                }
            }
        }
        BadgeAtlas(bitmap.asAndroidBitmap(), plates)
    }
}
