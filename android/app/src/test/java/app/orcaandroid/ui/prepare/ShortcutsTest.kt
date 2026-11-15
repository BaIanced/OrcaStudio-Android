package app.orcaandroid.ui.prepare

import app.orcaandroid.R
import app.orcaandroid.core.Scene
import app.orcaandroid.core.SceneObject
import app.orcaandroid.ui.ChromeState
import app.orcaandroid.ui.SceneState
import app.orcaandroid.ui.Selection
import app.orcaandroid.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shortcut list (docs/design/EDGE-MENU.md section 7): add, Undo, the cap of 6, scopes, unknown ids, order. */
class ShortcutsTest {
    private val plate = listOf("plate.arrange.arrange", "plate.arrange.auto_orient", "object.cut", "object.paint", "object.resize", "object.rotate")
    private val reshape = listOf("reshape.brush.draw", "reshape.brush.inflate", "reshape.brush.smooth", "reshape.brush.flatten", "reshape.brush.pinch",
        "reshape.brush.crease")

    @Test fun everyIdUsedHereIsALeaf() {
        (plate + reshape + "view.iso" + "object.center" + "reshape.invert").forEach { assertTrue(it, Shortcuts.known(it)) }
    }

    @Test fun parseAndJoin() {
        assertEquals(listOf("a", "b"), Shortcuts.parse(" a, b,,a "))
        assertEquals(emptyList<String>(), Shortcuts.parse(null))
        assertEquals(emptyList<String>(), Shortcuts.parse(""))
        assertEquals("object.cut,view.iso", Shortcuts.join(listOf("object.cut", "view.iso")))
    }

    @Test fun addAppendsAndUndoRemoves() {
        val one = Shortcuts.add(emptyList(), "object.cut")!!
        val two = Shortcuts.add(one, "view.iso")!!
        assertEquals(listOf("object.cut", "view.iso"), two)
        // Undo of an add is a remove.
        assertEquals(one, Shortcuts.remove(two, "view.iso"))
        // Undo of a remove puts it back at its place.
        assertEquals(two, Shortcuts.insert(Shortcuts.remove(two, "object.cut"), "object.cut", 0))
        assertEquals(listOf("object.cut", "view.iso"), Shortcuts.insert(listOf("view.iso"), "object.cut", 0))
        assertEquals(listOf("view.iso", "object.cut"), Shortcuts.insert(listOf("view.iso"), "object.cut", 9))
    }

    @Test fun addingTwiceIsRefused() {
        assertNull(Shortcuts.add(listOf("object.cut"), "object.cut"))
    }

    @Test fun theSeventhInAContextIsRefused() {
        assertEquals(MAX_SHORTCUTS, plate.size)
        assertFalse(Shortcuts.canAdd(plate, "object.center"))
        assertNull(Shortcuts.add(plate, "object.center"))
        // The 6th still fits.
        assertEquals(plate, Shortcuts.add(plate.dropLast(1), plate.last()))
    }

    @Test fun theCapIsPerContext() {
        // A full plate leaves Reshape free, and the other way round.
        assertTrue(Shortcuts.canAdd(plate, "reshape.invert"))
        assertTrue(Shortcuts.canAdd(reshape, "object.center"))
        // A view shortcut shows in both, so either full context refuses it.
        assertFalse(Shortcuts.canAdd(plate, "view.iso"))
        assertFalse(Shortcuts.canAdd(reshape, "view.iso"))
        assertTrue(Shortcuts.canAdd(plate.dropLast(1) + "view.iso", "reshape.invert"))
        // Removing frees the place.
        assertTrue(Shortcuts.canAdd(Shortcuts.remove(plate, "object.cut"), "object.center"))
    }

    @Test fun scopeFilter() {
        val all = listOf("object.cut", "reshape.brush.draw", "view.iso", "plate.arrange.arrange", "reshape.invert")
        assertEquals(listOf("object.cut", "view.iso", "plate.arrange.arrange"), Shortcuts.forContext(all, reshaping = false))
        assertEquals(listOf("reshape.brush.draw", "view.iso", "reshape.invert"), Shortcuts.forContext(all, reshaping = true))
    }

    @Test fun unknownIdsAreSkippedAndDropped() {
        val stored = listOf("object.cut", "object.gone", "reshape.brush.nope", "object", "view.iso", "")
        // A branch is not a shortcut either.
        assertEquals(listOf("object.cut", "view.iso"), Shortcuts.clean(stored))
        assertEquals(listOf("object.cut", "view.iso"), Shortcuts.forContext(stored, reshaping = false))
        assertEquals(listOf("view.iso"), Shortcuts.forContext(stored, reshaping = true))
        // The next change drops them.
        assertEquals(listOf("object.cut", "view.iso", "object.paint"), Shortcuts.add(stored, "object.paint"))
        assertNull(Shortcuts.add(emptyList(), "object.gone"))
        // They do not count towards the cap.
        assertTrue(Shortcuts.canAdd(plate.dropLast(1) + "object.gone" + "object.nothing", plate.last()))
    }

    @Test fun reorderKeepsOtherContexts() {
        val all = listOf("object.cut", "reshape.brush.draw", "view.iso", "object.paint", "reshape.invert")
        // The plate dock shows cut, iso, paint; the user drags paint to the front.
        assertEquals(listOf("object.paint", "reshape.brush.draw", "object.cut", "view.iso", "reshape.invert"),
            Shortcuts.reorder(all, listOf("object.paint", "object.cut", "view.iso")))
        // Not a reordering of what is stored: nothing changes.
        assertEquals(all, Shortcuts.reorder(all, listOf("object.paint", "object.gone")))
        assertEquals(all, Shortcuts.reorder(all, listOf("object.cut", "object.cut")))
    }

    private fun obj(i: Int) = SceneObject(i, "part$i", 12, emptyMap(), emptyList(), emptyList(), emptyList(), false, "")
    private fun state(objects: List<SceneObject>, sel: Selection? = null) =
        UiState(workspace = SceneState(scene = Scene(objects = objects), selection = sel), chrome = ChromeState(focusRoom = true))

    @Test fun blockedShortcutsSayWhy() {
        assertEquals(R.string.shortcut_need_object, Shortcuts.blockedReason(state(listOf(obj(0))), "object.cut"))
        assertEquals(R.string.shortcut_need_scene, Shortcuts.blockedReason(state(emptyList()), "plate.arrange.arrange"))
        assertNull(Shortcuts.blockedReason(state(listOf(obj(0)), Selection(0)), "object.cut"))
        assertNull(Shortcuts.blockedReason(state(emptyList()), "view.iso"))
        assertEquals(R.string.shortcut_need_many, Shortcuts.blockedReason(state(listOf(obj(0)), Selection(0)), "selection.assemble"))
        assertNull(Shortcuts.blockedReason(state(emptyList()), "object.gone"))
    }
}
