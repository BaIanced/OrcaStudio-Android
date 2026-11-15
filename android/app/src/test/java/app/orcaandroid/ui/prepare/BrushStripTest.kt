package app.orcaandroid.ui.prepare

import app.orcaandroid.ui.SCULPT_MAX_MM
import app.orcaandroid.ui.SCULPT_MIN_MM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The brush strip's math (docs/design/EDGE-MENU.md section 8): axis lock, brush steps, log size, tap hit, TalkBack steps. */
class BrushStripTest {
    private val m = BrushStripMath
    private val step = 28f
    private val span = 240f
    private val n = SCULPT_BRUSH_IDS.size

    @Test fun sevenBrushesInTheCardsOrder() {
        assertEquals(listOf("draw", "inflate", "smooth", "flatten", "pinch", "crease", "grab"), SCULPT_BRUSH_IDS)
        // The Reshape tree offers the same brushes in the same order.
        assertEquals(SCULPT_BRUSH_IDS.map { "reshape.brush.$it" }, findMenuNode("reshape.brush")!!.children.map { it.id })
    }

    @Test fun axisLocksOnlyPastTheSlopOnTheDominantAxis() {
        assertNull(m.lockAxis(5f, 5f, 8f))
        assertNull(m.lockAxis(8f, 0f, 8f))
        assertEquals(StripAxis.BRUSH, m.lockAxis(9f, 0f, 8f))
        assertEquals(StripAxis.BRUSH, m.lockAxis(-9f, 4f, 8f))
        assertEquals(StripAxis.SIZE, m.lockAxis(3f, -9f, 8f))
        assertEquals(StripAxis.SIZE, m.lockAxis(-6f, 7f, 8f))
        // A tie goes along the strip.
        assertEquals(StripAxis.BRUSH, m.lockAxis(7f, 7f, 8f))
    }

    @Test fun oneBrushPerStepTheRowFollowsTheFinger() {
        assertEquals(3, m.brushIndex(3, 0f, step, n))
        assertEquals(3, m.brushIndex(3, -27.9f, step, n))
        assertEquals(4, m.brushIndex(3, -28f, step, n))
        assertEquals(5, m.brushIndex(3, -56.5f, step, n))
        assertEquals(2, m.brushIndex(3, 28f, step, n))
        assertEquals(3, m.brushIndex(3, 27f, step, n))
        // Held at the ends.
        assertEquals(6, m.brushIndex(3, -1000f, step, n))
        assertEquals(0, m.brushIndex(3, 1000f, step, n))
    }

    @Test fun nextAndPreviousStopAtTheEnds() {
        assertEquals("inflate", m.stepBrush("draw", 1))
        assertEquals("draw", m.stepBrush("draw", -1))
        assertEquals("grab", m.stepBrush("grab", 1))
        assertEquals("crease", m.stepBrush("grab", -1))
        // An unknown brush counts as the first.
        assertEquals("inflate", m.stepBrush("nope", 1))
    }

    @Test fun logScaleRoundTripsAndEnds() {
        assertEquals(0f, m.sizeFraction(SCULPT_MIN_MM), 1e-6f)
        assertEquals(1f, m.sizeFraction(SCULPT_MAX_MM), 1e-6f)
        assertEquals(0f, m.sizeFraction(0.01f), 1e-6f)
        assertEquals(1f, m.sizeFraction(1000f), 1e-6f)
        for (mm in listOf(0.5f, 1f, 3f, 12f, 30f, 100f, 150f)) assertEquals(mm, m.sizeAt(m.sizeFraction(mm)), mm * 1e-4f)
        // Geometric middle at half way.
        assertEquals(kotlin.math.sqrt(SCULPT_MIN_MM * SCULPT_MAX_MM), m.sizeAt(0.5f), 0.01f)
    }

    @Test fun dragUpIsBiggerAndTheSpanCoversTheRange() {
        assertEquals(12f, m.sizeForDrag(12f, 0f, span), 1e-3f)
        assertTrue(m.sizeForDrag(12f, -10f, span) > 12f)
        assertTrue(m.sizeForDrag(12f, 10f, span) < 12f)
        assertEquals(SCULPT_MAX_MM, m.sizeForDrag(SCULPT_MIN_MM, -span, span), 1e-3f)
        assertEquals(SCULPT_MIN_MM, m.sizeForDrag(SCULPT_MAX_MM, span, span), 1e-4f)
        assertEquals(SCULPT_MAX_MM, m.sizeForDrag(30f, -10 * span, span), 1e-3f)
        // Equal drags multiply the size by the same factor (log scale): 24 px = a tenth of the range.
        val ratio = Math.pow((SCULPT_MAX_MM / SCULPT_MIN_MM).toDouble(), 0.1).toFloat()
        assertEquals(10f * ratio, m.sizeForDrag(10f, -24f, span), 0.01f)
        assertEquals(1f * ratio, m.sizeForDrag(1f, -24f, span), 0.001f)
    }

    @Test fun talkBackStepsAreATwentiethOfTheRange() {
        val ratio = Math.pow((SCULPT_MAX_MM / SCULPT_MIN_MM).toDouble(), 0.05).toFloat()
        assertEquals(10f * ratio, m.stepSize(10f, 1), 0.01f)
        assertEquals(10f / ratio, m.stepSize(10f, -1), 0.01f)
        assertEquals(SCULPT_MAX_MM, m.stepSize(SCULPT_MAX_MM, 1), 1e-3f)
        assertEquals(SCULPT_MIN_MM, m.stepSize(SCULPT_MIN_MM, -1), 1e-4f)
    }

    @Test fun chosenBrushIsCentredAndTapsHitItsItem() {
        val w = 400f
        val item = 64f
        for (i in 0 until n) {
            val off = m.centredOffset(i, w, item)
            assertEquals(i, m.itemAt(w / 2f, off, item, n))
            assertEquals(i, m.itemAt(off + i * item + 1f, off, item, n))
        }
        val off = m.centredOffset(0, w, item)
        assertNull(m.itemAt(off - 1f, off, item, n))
        assertEquals(1, m.itemAt(w / 2f + item, off, item, n))
        assertNull(m.itemAt(off + n * item + 1f, m.centredOffset(0, w, item), item, n))
    }
}
