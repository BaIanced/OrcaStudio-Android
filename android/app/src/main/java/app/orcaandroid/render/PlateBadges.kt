package app.orcaandroid.render

import android.graphics.Bitmap

/** A badge drawn on the bed beside each plate (the desktop's plate number and plate icons). */
enum class PlateBadge { DELETE, ARRANGE, MENU, NUMBER }

/**
 * Texture atlas of the plate badges, built by the host (it owns the icon art and theme colours):
 * square cells of [CELL] px, [COLUMNS] per row; cell i < 3 = the icon of [PlateBadge] ordinal i, then
 * each plate's number twice (normal, active plate). Premultiplied alpha, like every Android bitmap.
 */
class BadgeAtlas(val bitmap: Bitmap, val plates: Int) {
    /** Texture coordinates u0, v0 (top), u1, v1 (bottom) of [cell]. */
    fun uv(cell: Int): FloatArray {
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val x = (cell % COLUMNS) * CELL; val y = (cell / COLUMNS) * CELL
        return floatArrayOf(x / w, y / h, (x + CELL) / w, (y + CELL) / h)
    }

    companion object {
        const val CELL = 128
        const val COLUMNS = 8
        fun numberCell(plate: Int, active: Boolean) = 3 + 2 * plate + if (active) 1 else 0
        fun cells(plates: Int) = 3 + 2 * plates
    }
}

/** Tap target of a badge on the bed plane, world mm. */
internal class BadgeRect(val plate: Int, val badge: PlateBadge, val x0: Float, val y0: Float, val x1: Float, val y1: Float)
