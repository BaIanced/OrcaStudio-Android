package app.orcaandroid.ui.settings

import app.orcaandroid.core.PresetType
import app.orcaandroid.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingGuardsTest {
    @Test fun fixesForKnownMessages() {
        assertEquals(NoticeFix.Option(PresetType.PRINT, "prime_tower_width"),
            noticeFix("The prime tower (about 35 x 35 mm) has no free spot on plate 1: every position would overlap an object."))
        assertEquals(NoticeFix.Option(PresetType.PRINT, "spiral_mode"),
            noticeFix("Spiral (vase) mode does not work when an object contains more than one material."))
        assertEquals(NoticeFix.Option(PresetType.PRINT, "support_type"),
            noticeFix("Variable layer height is not supported with Organic supports."))
        assertEquals(NoticeFix.Go(Screen.MORE), noticeFix("Selected nozzle temperatures are incompatible: PLA, PETG"))
        assertNull(noticeFix("Something unrelated happened"))
    }

    @Test fun dangerRules() {
        assertEquals(DangerRule.NOZZLE_TEMP, dangerRule(PresetType.FILAMENT, "nozzle_temperature", "220,320"))
        assertNull(dangerRule(PresetType.FILAMENT, "nozzle_temperature", "300"))
        assertEquals(DangerRule.BED_TEMP, dangerRule(PresetType.FILAMENT, "hot_plate_temp_initial_layer", "120"))
        assertNull(dangerRule(PresetType.FILAMENT, "hot_plate_temp", "110"))
        assertEquals(DangerRule.PRINTER_GEOMETRY, dangerRule(PresetType.PRINTER, "z_offset", "0.1"))
        assertNull(dangerRule(PresetType.PRINT, "z_offset", "0.1"))
        assertEquals(DangerRule.GCODE, dangerRule(PresetType.PRINTER, "machine_start_gcode", "G28"))
        assertNull(dangerRule(PresetType.PRINT, "layer_height", "0.2"))
    }
}
