package app.orcaandroid.ui.prepare

import app.orcaandroid.ui.Screen
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.toolAfterScreenChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The paint mode is derived from the tool (see [PaintMode]) and the tool is kept
 * across Prepare <-> Preview switches (see `toolAfterScreenChange`), so the mode
 * survives a Preview round-trip. These are the pure halves of that fix; the device
 * behaviour itself is UNVERIFIED (no toolchain / device in Muse's VM).
 */
class PaintModeTest {

    @Test fun modeOfShape() {
        // Brush shapes, fill shapes, the height band, and anything unknown (a brush).
        assertEquals(PaintMode.BRUSH, PaintMode.ofShape("sphere"))
        assertEquals(PaintMode.BRUSH, PaintMode.ofShape("circle"))
        assertEquals(PaintMode.BRUSH, PaintMode.ofShape("triangle"))
        assertEquals(PaintMode.FILL, PaintMode.ofShape("fill"))
        assertEquals(PaintMode.FILL, PaintMode.ofShape("regions"))
        assertEquals(PaintMode.BANDS, PaintMode.ofShape("height"))
        assertEquals(PaintMode.BRUSH, PaintMode.ofShape("pick"))
    }

    @Test fun objectModeNeedsTheFillShape() {
        // Object is the fill shape plus the tool's objectMode flag; the flag alone
        // (stale, after the shape moved on) does not resurrect Object.
        assertEquals(PaintMode.OBJECT, PaintMode.of("fill", objectMode = true))
        assertEquals(PaintMode.FILL, PaintMode.of("fill", objectMode = false))
        assertEquals(PaintMode.BRUSH, PaintMode.of("sphere", objectMode = true))
        assertEquals(PaintMode.FILL, PaintMode.of("regions", objectMode = true))
        assertEquals(PaintMode.BANDS, PaintMode.of("height", objectMode = true))
    }

    @Test fun toolSurvivesPreparePreviewRoundTrip() {
        val paint = Tool.Paint("color", 2, 3f, shape = "fill", objectMode = true)
        val inPreview = toolAfterScreenChange(Screen.PREPARE, Screen.PREVIEW, paint)
        assertSame(paint, inPreview)
        assertSame(paint, toolAfterScreenChange(Screen.PREVIEW, Screen.PREPARE, inPreview))
        // Other tools ride along too: the workspace shares one 3D view.
        assertSame(Tool.Measure, toolAfterScreenChange(Screen.PREPARE, Screen.PREVIEW, Tool.Measure))
    }

    @Test fun toolClearsWhenLeavingTheWorkspace() {
        val paint = Tool.Paint("color", 2, 3f, shape = "height")
        assertEquals(Tool.None, toolAfterScreenChange(Screen.PREPARE, Screen.DEVICE, paint))
        assertEquals(Tool.None, toolAfterScreenChange(Screen.PREPARE, Screen.MODELS, paint))
        // Also from Preview, where the tool now survives: Device is still a clear.
        assertEquals(Tool.None, toolAfterScreenChange(Screen.PREVIEW, Screen.DEVICE, paint))
        assertEquals(Tool.None, toolAfterScreenChange(Screen.DEVICE, Screen.MODELS, paint))
    }

    @Test fun toolKeptWhenReturningToPrepare() {
        // The pre-fix rule for arriving at Prepare (from Back on another tab) is unchanged.
        val paint = Tool.Paint("color", 1, 3f)
        assertSame(paint, toolAfterScreenChange(Screen.DEVICE, Screen.PREPARE, paint))
        assertEquals(Tool.None, toolAfterScreenChange(Screen.PREPARE, Screen.PREPARE, Tool.None))
    }
}
