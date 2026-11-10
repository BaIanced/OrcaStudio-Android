package app.orcaandroid.vr

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerRouterTest {
    private val log = ArrayList<String>()
    private val router = PointerRouter(object : PointerRouter.Sink {
        override fun panel(action: Int, x: Float, y: Float) {
            log += "${listOf("down", "move", "up", "cancel")[action]} ${x.toInt()} ${y.toInt()}"
        }

        override fun plateTap(index: Int, x: Float, y: Float) {
            log += "tap $index ${x.toInt()} ${y.toInt()}"
        }
    })

    private fun frame(
        pressed: Boolean, panel: Boolean = false, px: Float = 0f, py: Float = 0f, plate: Int = -1,
        tracking: Boolean = true, tool: Boolean = false, build: Boolean = false,
    ) = router.update(tracking, pressed, panel, px, py, plate, 5f, 6f, tool, build)

    @Test fun panelPressGivesDownMoveUp() {
        frame(false, panel = true, px = 100f, py = 100f)
        frame(true, panel = true, px = 100f, py = 100f)
        frame(true, panel = true, px = 160f, py = 100f)
        frame(false, panel = true, px = 160f, py = 100f)
        assertEquals(listOf("down 100 100", "move 160 100", "up 160 100"), log)
    }

    @Test fun holdingDoesNotRepeatThePress() {
        frame(true, plate = 0)
        frame(true, plate = 0)
        frame(true, plate = 0)
        assertEquals(listOf("tap 0 5 6"), log)
    }

    @Test fun releaseThenPressTapsAgain() {
        frame(true, plate = 0)
        frame(false, plate = 0)
        frame(true, plate = 1)
        assertEquals(listOf("tap 0 5 6", "tap 1 5 6"), log)
    }

    @Test fun dragLeavingThePanelKeepsCapture() {
        frame(true, panel = true, px = 1270f, py = 400f)
        frame(true, panel = false, px = 1400f, py = 400f, plate = 0)
        frame(false, panel = false, px = 1400f, py = 400f, plate = 0)
        assertEquals(listOf("down 1270 400", "move 1400 400", "up 1400 400"), log)
    }

    @Test fun wanderingInsideTheDeadZoneIsStillAClick() {
        frame(true, panel = true, px = 100f, py = 100f)
        frame(true, panel = true, px = 105f, py = 104f)
        frame(false, panel = true, px = 108f, py = 100f)
        assertEquals(listOf("down 100 100", "up 100 100"), log)
    }

    @Test fun passingTheDeadZoneKeepsMovingEvenWhenBackNearTheStart() {
        frame(true, panel = true, px = 100f, py = 100f)
        frame(true, panel = true, px = 140f, py = 100f)
        frame(true, panel = true, px = 101f, py = 100f)
        assertEquals(listOf("down 100 100", "move 140 100", "move 101 100"), log)
    }

    @Test fun focusLossCancelsAPanelPress() {
        frame(true, panel = true, px = 50f, py = 60f)
        frame(true, tracking = false)
        frame(false, panel = true, px = 50f, py = 60f)
        assertEquals(listOf("down 50 60", "cancel 50 60"), log)
    }

    @Test fun triggerHeldAcrossFocusReturnIsNotANewPress() {
        frame(true, tracking = false)
        frame(true, plate = 0)
        assertEquals(emptyList<String>(), log)
        frame(false, plate = 0)
        frame(true, plate = 0)
        assertEquals(listOf("tap 0 5 6"), log)
    }

    @Test fun panelBeatsPlate() {
        frame(true, panel = true, px = 10f, py = 10f, plate = 0)
        assertEquals(listOf("down 10 10"), log)
    }

    @Test fun noPlateTapWhenAToolIsSetOrBuildIsOpen() {
        frame(true, plate = 0, tool = true)
        frame(false)
        frame(true, plate = 0, build = true)
        assertEquals(emptyList<String>(), log)
    }

    @Test fun pressOnNothingDoesNothingEvenWhenSlidingOntoAPlate() {
        frame(true)
        frame(true, plate = 0)
        frame(false, plate = 0)
        assertEquals(emptyList<String>(), log)
    }

    @Test fun panelPressWhileToolSetStillWorks() {
        frame(true, panel = true, px = 1f, py = 2f, tool = true)
        assertEquals(listOf("down 1 2"), log)
    }
}
