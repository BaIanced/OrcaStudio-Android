package app.orcaandroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Heading colours by group title (upstream's untranslated optgroup names). */
class SettingFamilyTest {
    @Test fun familiesFromUpstreamTitles() {
        mapOf(
            "Layer height" to SettingFamily.QUALITY,
            "Seam" to SettingFamily.QUALITY,
            "Initial layer speed" to SettingFamily.SPEED,
            "Acceleration" to SettingFamily.SPEED,
            "Bed temperature" to SettingFamily.TEMPERATURE,
            "Cooling for specific layer" to SettingFamily.TEMPERATURE,
            "Part cooling fan" to SettingFamily.TEMPERATURE,
            "Support filament" to SettingFamily.SUPPORT,
            "Prime tower" to SettingFamily.SUPPORT,
            "Top/bottom shells" to SettingFamily.STRENGTH,
            "Infill" to SettingFamily.STRENGTH,
            "Retraction" to SettingFamily.FILAMENT,
            "Quality › Overhang speed" to SettingFamily.SPEED,
        ).forEach { (title, family) -> assertEquals(title, family, SettingFamily.of(title)) }
    }

    @Test fun unknownTitleHasNoFamily() {
        assertNull(SettingFamily.of("G-code output"))
        assertNull(SettingFamily.of("Notes"))
    }
}
