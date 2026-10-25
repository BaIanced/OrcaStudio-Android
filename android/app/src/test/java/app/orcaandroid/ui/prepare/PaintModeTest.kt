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

    // Task 11 (the Task 7 follow-up): the per-mode shapes and the fill settings Object
    // mode overrides live on the tool, and PaintMode.entered moves between modes.

    @Test fun enteredRestoresEachModesShape() {
        // The tool remembers circle for Brush and regions for Fill, on the tool itself.
        val paint = Tool.Paint("color", 1, 3f, shape = "height", brushShape = "circle", fillShape = "regions")
        val brush = PaintMode.entered(paint, PaintMode.BANDS, PaintMode.BRUSH)
        assertEquals("circle", brush.shape)
        assertEquals("circle", brush.back)
        val fill = PaintMode.entered(brush, PaintMode.BRUSH, PaintMode.FILL)
        assertEquals("regions", fill.shape)
        // Regions bumps a zero smart-fill angle to 30, as the composable used to.
        assertEquals(30f, fill.edgeAngle)
    }

    @Test fun enteredObjectSavesAndLeavingRestoresFillSettings() {
        val paint = Tool.Paint("color", 1, 3f, shape = "fill", edgeAngle = 45f, overColours = false, guardOn = true, guardZ = 7f)
        val obj = PaintMode.entered(paint, PaintMode.FILL, PaintMode.OBJECT)
        // Object applies its overrides and sets the flag.
        assertEquals("fill", obj.shape)
        assertEquals(0f, obj.edgeAngle)
        assertEquals(true, obj.overColours)
        assertEquals(false, obj.guardOn)
        assertEquals(true, obj.objectMode)
        // Leaving Object restores the saved fill settings, not the overrides.
        val back = PaintMode.entered(obj, PaintMode.OBJECT, PaintMode.FILL)
        assertEquals(45f, back.edgeAngle)
        assertEquals(false, back.overColours)
        assertEquals(true, back.guardOn)
        assertEquals(false, back.objectMode)
    }

    @Test fun paintSettingsSurvivePreviewRoundTrip() {
        // The whole point of the follow-up: the settings are fields of the tool, and the
        // tool is what toolAfterScreenChange carries across Prepare <-> Preview, so a
        // round-trip (which recreates ColorPaintOptions) cannot lose them.
        var paint = Tool.Paint("color", 1, 3f, shape = "regions", fillShape = "regions", brushShape = "triangle", edgeAngle = 45f)
        paint = PaintMode.entered(paint, PaintMode.FILL, PaintMode.OBJECT)
        val inPrepare = toolAfterScreenChange(Screen.PREVIEW, Screen.PREPARE,
            toolAfterScreenChange(Screen.PREPARE, Screen.PREVIEW, paint))
        assertSame(paint, inPrepare)
        val back = PaintMode.entered(inPrepare as Tool.Paint, PaintMode.OBJECT, PaintMode.FILL)
        assertEquals("regions", back.shape)
        assertEquals(45f, back.edgeAngle)
        assertEquals("triangle", PaintMode.entered(back, PaintMode.FILL, PaintMode.BRUSH).shape)
    }

    @Test fun reopenedKeepsModeAndShapesButNotTheVisit() {
        // Done -> Preview -> Paint > Color painting (WSA r373 reopened on Brush / Sphere).
        val left = Tool.Paint("color", 0, 5f, shape = "pick", back = "height", brushShape = "triangle", height = 0.6f,
            guardOn = true, hidden = 2, locked = 1)
        val again = PaintMode.reopened(left, "color", 2)
        assertEquals("height", again.shape)
        assertEquals(PaintMode.BANDS, PaintMode.of(again.shape, again.objectMode))
        assertEquals("triangle", PaintMode.entered(again, PaintMode.BANDS, PaintMode.BRUSH).shape)
        assertEquals(5f, again.radius)
        assertEquals(0.6f, again.height)
        assertEquals(2, again.state)
        assertEquals(false, again.guardOn)
        assertEquals(0, again.hidden)
        assertEquals(0, again.locked)
        // Another kind, or nothing remembered: a fresh tool.
        assertEquals(Tool.Paint("seam", 1, 3f), PaintMode.reopened(left, "seam", 1))
        assertEquals(Tool.Paint("color", 2, 3f), PaintMode.reopened(null, "color", 2))
    }

    @Test fun enteredObjectFromEraseSelectsAFilament() {
        val paint = Tool.Paint("color", 0, 3f, shape = "fill")
        assertEquals(1, PaintMode.entered(paint, PaintMode.FILL, PaintMode.OBJECT).state)
        // Same mode is a no-op and returns the tool untouched.
        assertSame(paint, PaintMode.entered(paint, PaintMode.FILL, PaintMode.FILL))
    }
}
