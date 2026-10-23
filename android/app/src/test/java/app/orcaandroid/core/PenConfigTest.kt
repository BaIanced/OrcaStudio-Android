package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PenConfig]'s JSON round trip: one bad stored action must fall back for that
 * key only, never reset the whole pen setup. Defaults: ignoreFingers on, button1 PAN,
 * button2 MENU, eraser UNDO.
 */
class PenConfigTest {

    @Test fun defaultsFromNullMalformedAndEmpty() {
        val d = PenConfig()
        assertEquals(d, PenConfig.fromJson(null))
        assertEquals(d, PenConfig.fromJson(""))
        assertEquals(d, PenConfig.fromJson("not json"))
        assertEquals(d, PenConfig.fromJson("[]"))
        assertEquals(d, PenConfig.fromJson("{}"))
        assertTrue(d.ignoreFingers)
        assertEquals(PenAction.PAN, d.button1)
        assertEquals(PenAction.MENU, d.button2)
        assertEquals(PenAction.UNDO, d.eraser)
    }

    @Test fun roundTripEveryActionInEverySlot() {
        for (action in PenAction.entries) {
            val config = PenConfig(ignoreFingers = false, button1 = action, button2 = action, eraser = action)
            assertEquals(config, PenConfig.fromJson(config.toJson()))
        }
        val mixed = PenConfig(ignoreFingers = true, button1 = PenAction.ORBIT, button2 = PenAction.SELECT_ADD, eraser = PenAction.FRAME)
        assertEquals(mixed, PenConfig.fromJson(mixed.toJson()))
    }

    @Test fun unknownActionFallsBackPerKey() {
        val config = PenConfig.fromJson("""{"button1":"FLY","button2":"ORBIT","eraser":"PAN"}""")
        assertEquals(PenAction.PAN, config.button1) // the default, not a global reset
        assertEquals(PenAction.ORBIT, config.button2)
        assertEquals(PenAction.PAN, config.eraser)
    }

    @Test fun lowercaseActionFallsBackToday() {
        // Enum names are case-sensitive, so a hand-edited lowercase value is lost.
        // Stored files always carry the exact name; pinned, not fixed.
        assertEquals(PenAction.MENU, PenConfig.fromJson("""{"button2":"menu"}""").button2)
    }

    @Test fun wrongTypeAndNullActionsFallBack() {
        val config = PenConfig.fromJson("""{"button1":5,"button2":null,"eraser":true}""")
        assertEquals(PenConfig(), config)
    }

    @Test fun ignoreFingersEdgeValues() {
        assertFalse(PenConfig.fromJson("""{"ignoreFingers":false}""").ignoreFingers)
        // org.json's optBoolean also parses the strings "true"/"false".
        assertFalse(PenConfig.fromJson("""{"ignoreFingers":"false"}""").ignoreFingers)
        // A number is neither, so the default (on) survives.
        assertTrue(PenConfig.fromJson("""{"ignoreFingers":0}""").ignoreFingers)
    }

    @Test fun instantActionsAreUndoRedoFrame() {
        assertTrue(PenAction.UNDO.instant)
        assertTrue(PenAction.REDO.instant)
        assertTrue(PenAction.FRAME.instant)
        for (action in listOf(PenAction.TIP, PenAction.PAN, PenAction.ORBIT, PenAction.SELECT_ADD, PenAction.SELECT_REMOVE, PenAction.MENU)) {
            assertFalse(action.instant)
        }
    }
}
