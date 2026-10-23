package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hand-written G-code fixtures for [GcodeToolChanges] (no printer output, no real user files).
 * Prime-tower volumes: filament area for 1.75 mm = pi * 0.875^2 = 2.40528 mm2, so 10 mm of
 * filament is 24.0528 mm3, 5 mm is 12.0264 mm3 and 8 mm is 19.2423 mm3.
 */
class GcodeToolChangesTest {

    private fun parse(gcode: String) = GcodeToolChanges.parse(gcode.trimIndent().lineSequence())

    @Test fun emptyInput() {
        val r = GcodeToolChanges.parse(emptySequence())
        assertEquals(0, r.changes)
        assertEquals(emptyList<List<Int>>(), r.perLayer)
        assertEquals(0.0, r.primeTowerMm3, 1e-9)
    }

    @Test fun singleFilamentNoChanges() {
        // T0 loads once (not a change); both layers are seeded with the loaded filament.
        val r = parse("""
            T0
            ; CHANGE_LAYER
            G1 X10 Y10 E5
            ; CHANGE_LAYER
            G1 X20 Y20 E9
        """)
        assertEquals(0, r.changes)
        assertEquals(listOf(listOf(0), listOf(0)), r.perLayer)
        assertEquals(0.0, r.primeTowerMm3, 1e-9)
    }

    @Test fun plainToolChangesPerLayer() {
        // 0 -> 1 on layer 0, 1 -> 0 on layer 1: 2 changes. A layer lists the filaments
        // selected while it is current, seeded with the one loaded at its marker.
        val r = parse("""
            T0
            ; CHANGE_LAYER
            T1
            ; CHANGE_LAYER
            T0
        """)
        assertEquals(2, r.changes)
        assertEquals(listOf(listOf(0, 1), listOf(1, 0)), r.perLayer)
    }

    @Test fun bambuM620CountsOncePerChange() {
        // One Bambu change to filament 1 says it three ways (M620 S1A, T1, M621 S1A):
        // only the first selection differs from the loaded filament, so 1 change.
        // M621 is not a selection command and is ignored.
        val r = parse("""
            M620 S0A
            T0
            ; CHANGE_LAYER
            M620 S1A
            T1
            M621 S1A
        """)
        assertEquals(1, r.changes)
        assertEquals(listOf(listOf(0, 1)), r.perLayer)
    }

    @Test fun bambuM1020Selection() {
        // The writer's own BBL toolchange command (GCodeWriter::toolchange).
        val r = parse("""
            M1020 S0 H-1
            ; CHANGE_LAYER
            M1020 S2 H-1
        """)
        assertEquals(1, r.changes)
        assertEquals(listOf(listOf(0, 2)), r.perLayer)
    }

    @Test fun noOpIdsIgnored() {
        // T255 / T1000 / M620 S255 are unload / no-tool sentinels, not filaments:
        // filament 0 stays loaded throughout, so no change and no extra layer entries.
        val r = parse("""
            T0
            ; CHANGE_LAYER
            T255
            M620 S255A
            T1000
            G1 X5 Y5 E2
        """)
        assertEquals(0, r.changes)
        assertEquals(listOf(listOf(0)), r.perLayer)
    }

    @Test fun commentsDoNotSelect() {
        // A T-word inside a comment (whole-line or trailing) is not a selection;
        // the manual-change comment the writer emits instead of a T command is.
        val r = parse("""
            T0 ; T5 would be a change if comments were parsed
            ; T3 on its own comment line is not either
            ; CHANGE_LAYER
            ; MANUAL_TOOL_CHANGE T2
        """)
        assertEquals(1, r.changes)
        assertEquals(listOf(listOf(0, 2)), r.perLayer)
    }

    @Test fun noLayerMarkersSingleLayer() {
        // No CHANGE_LAYER anywhere: one layer holding every selection in order.
        val r = parse("""
            T0
            T1
            T0
        """)
        assertEquals(2, r.changes)
        assertEquals(listOf(listOf(0, 1)), r.perLayer)
    }

    @Test fun primeTowerAbsoluteE() {
        // Absolute E (default): the move to E5 happens before the tower; inside it E goes
        // 5 -> 10 -> 15 = 10 mm of filament = 24.0528 mm3. The move to E20 after the
        // next feature marker does not count.
        val r = parse("""
            T0
            G92 E0
            G1 X5 Y5 E5
            ; FEATURE: Prime tower
            G1 X6 Y6 E10
            G1 X7 Y7 E15
            ; FEATURE: Inner wall
            G1 X8 Y8 E20
        """)
        assertEquals(24.0528, r.primeTowerMm3, 1e-3)
    }

    @Test fun primeTowerRelativeEAndRetraction() {
        // Relative E (M83): the X/Y moves extrude +2.5 and +2.5; the -0.8 retraction and
        // +0.8 re-prime in between are E-only moves, which the processor never counts for
        // a role (Retract / Unretract types), so the gross total is 5 mm = 12.0264 mm3.
        val r = parse("""
            M83
            ; FEATURE: Prime tower
            G1 X10 Y10 E2.5
            G1 E-0.8
            G1 E0.8
            G1 X20 Y20 E2.5
        """)
        assertEquals(12.0264, r.primeTowerMm3, 1e-3)
    }

    @Test fun primeTowerGrossXyExtrusionOnly() {
        // The processor's rules on one block (absolute E, hand-computed both ways):
        // X/Y extrusions +10 and +5 count; the -0.8 / +0.8 E-only retract pair and the
        // +2 E-only prime do not; a -0.5 X/Y move is a Travel, not an Extrude; the +1
        // X/Y move between WIPE_START / WIPE_END is typed Wipe and does not; the +2 arc
        // counts (arcs are discretised into G1 segments). Gross counted: 10 + 5 + 2 =
        // 17 mm = 40.8898 mm3. The old net-E rule gave 17.5 mm on the same input.
        val r = parse("""
            G92 E0
            ; FEATURE: Prime tower
            G1 X10 Y10 E10
            G1 E9.2
            G1 E10
            G1 X20 Y10 E15
            G1 X22 Y10 E14.5
            G1 E16.5
            ; WIPE_START
            G1 X25 Y10 E17.5
            ; WIPE_END
            G2 X30 Y15 I2 J2 E19.5
            ; FEATURE: Inner wall
            G1 X40 Y10 E29.5
        """)
        assertEquals(40.8898, r.primeTowerMm3, 1e-3)
    }

    @Test fun primeTowerWipeTowerTagsDoNotDelimit() {
        // WIPE_TOWER_START / END wrap the toolchange ram / load / wipe, but in the
        // processor they only set a flag that feeds no role statistic, and no FEATURE
        // tag inside the block sets the tower role: the +12 E-only load, the +8 X/Y
        // wipe and the -6 retract inside the block count 0 toward the tower. Only the
        // FEATURE block after it counts: E19 -> E24 = 5 mm = 12.0264 mm3. The old rule,
        // which treated the tags as delimiters and netted E, gave 19 mm here.
        val r = parse("""
            T0
            G92 E0
            ; FEATURE: Inner wall
            G1 X5 Y5 E5
            ; WIPE_TOWER_START
            G1 E17
            G1 X60 Y60 E25
            G1 E19
            ; WIPE_TOWER_END
            ; FEATURE: Prime tower
            G1 X10 Y10 E24
        """)
        assertEquals(12.0264, r.primeTowerMm3, 1e-3)
    }

    @Test fun primeTowerArcMovesAndCuraSpelling() {
        // Arc moves carry E like G1 does in the processor: G2 +5 and G3 +4 = 9 mm.
        // The Cura TYPE spelling for the tower is PRIME-TOWER: the X/Y move after it
        // adds 5 mm. Total 14 mm = 33.6739 mm3. The old parser ignored arcs and the
        // PRIME-TOWER spelling, so it counted 0 on this input.
        val r = parse("""
            G92 E0
            ; FEATURE: Prime tower
            G2 X10 Y10 I5 J0 E5
            G3 X20 Y10 I5 J0 E9
            ; TYPE: PRIME-TOWER
            G1 X25 Y10 E14
        """)
        assertEquals(33.6739, r.primeTowerMm3, 1e-3)
    }

    @Test fun primeTowerG92Reset() {
        // Absolute E with a G92 E0 reset inside the block: 0 -> 5 (5 mm), reset, 0 -> 3
        // (3 mm): 8 mm in total = 19.2423 mm3. Without the reset the second move would
        // read as a -2 mm retraction.
        val r = parse("""
            G92 E0
            ; FEATURE: Prime tower
            G1 X1 Y1 E5
            G92 E0
            G1 X2 Y2 E3
        """)
        assertEquals(19.2423, r.primeTowerMm3, 1e-3)
    }

    @Test fun primeTowerTypeSpelling() {
        // The non-BBL "; TYPE:" spelling delimits the same blocks as FEATURE:
        // 4 mm + 6 mm = 10 mm = 24.0528 mm3; the E20 move in between is outside both.
        val r = parse("""
            G92 E0
            ; TYPE: Prime tower
            G1 X1 Y1 E4
            ; TYPE: Inner wall
            G1 X2 Y2 E20
            ; TYPE: Prime tower
            G1 X3 Y3 E26
            ; TYPE: Inner wall
        """)
        assertEquals(24.0528, r.primeTowerMm3, 1e-3)
    }

    @Test fun perLayerFeedsColourChangeEstimate() {
        // The parser's perLayer is the shape ColourChangeEstimate.estimate takes, and on
        // this fixture both count the same switches. Matrix [[0, 100], [200, 0]]:
        // layer 0 prints 0 then 1, layer 1 prints 1 then 0. Estimate (startFilament null):
        // load 0 free, 0 -> 1 (100 mm3), then 1 -> 0 (200 mm3): 2 changes, 300 mm3.
        val r = parse("""
            T0
            ; CHANGE_LAYER
            T1
            ; CHANGE_LAYER
            T0
        """)
        val matrix = ColourChangeEstimate.parseFlushMatrix(listOf(0.0, 100.0, 200.0, 0.0), 2)!!
        val e = ColourChangeEstimate.estimate(r.perLayer, matrix, 1.0, listOf(1.0, 1.0), null)
        assertEquals(r.changes, e.changes)
        assertEquals(2, e.changes)
        assertEquals(300.0, e.flushMm3, 1e-9)
    }
}
