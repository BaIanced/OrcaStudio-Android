package app.orcaandroid.render

import org.junit.Assert.assertEquals
import org.junit.Test

class FilamentMaterialTest {
    @Test
    fun presetNames() {
        assertEquals(FilamentMaterial.BASIC, FilamentMaterial.of("Bambu PLA Basic @BBL A1"))
        assertEquals(FilamentMaterial.MATTE, FilamentMaterial.of("Bambu PLA Matte @BBL A1"))
        assertEquals(FilamentMaterial.SILK, FilamentMaterial.of("Bambu PLA Silk+ @BBL A1"))
        assertEquals(FilamentMaterial.GLOSSY, FilamentMaterial.of("Generic PETG"))
        assertEquals(FilamentMaterial.MATTE, FilamentMaterial.of("Bambu PETG-CF @BBL A1"))
        assertEquals(FilamentMaterial.TRANSLUCENT, FilamentMaterial.of("Bambu PETG Translucent @BBL A1"))
        assertEquals(FilamentMaterial.MATTE, FilamentMaterial.of("Generic TPU"))
        assertEquals(FilamentMaterial.SATIN, FilamentMaterial.of("Generic ASA"))
        assertEquals(FilamentMaterial.BASIC, FilamentMaterial.of("Generic PLA"))
    }

    @Test
    fun typeOverride() {
        assertEquals(FilamentMaterial.GLOSSY, FilamentMaterial.of("My filament", "PETG"))
        assertEquals(FilamentMaterial.BASIC, FilamentMaterial.of("My filament", "PLA"))
    }

    @Test
    fun realisticParamsInRange() {
        // uMatP: roughness, metallic, clear coat, coat roughness, all 0..1 (the shader clamps roughness to 0.045).
        for (m in FilamentMaterial.entries) {
            assertEquals(4, m.p.size)
            for (v in m.p) org.junit.Assert.assertTrue("$m $v", v in 0f..1f)
        }
    }
}
