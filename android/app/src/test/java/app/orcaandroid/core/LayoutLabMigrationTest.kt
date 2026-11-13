package app.orcaandroid.core

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Layout lab's JSON moves into individual keys once ([migrateLayoutLabPrefs]), and the Interface size keeps tool buttons large enough. */
class LayoutLabMigrationTest {
    /** A map-backed SharedPreferences that counts how often it was written. */
    private class FakePrefs(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
        val data = initial.toMutableMap()
        var writes = 0
        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in data
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val puts = mutableMapOf<String, Any?>()
            var clear = false
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { puts[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                writes++
                if (clear) data.clear()
                for ((k, v) in puts) if (v == null) data.remove(k) else data[k] = v
            }
        }
    }

    private val wsaJson = """{"plates":"FILMSTRIP","plateLabel":false,"overlayAlpha":0.543,"toolsLeft":true,"toolsCollapsed":false,
        "toolsRowX":-1,"toolsRowY":-1,"panelTucked":true,"panelWidth":700.8,"textScale":1,"uiScale":0.70,"overlayScale":0.85,
        "fullscreenPortrait":false,"platesOnHandle":false,"plateScale":0.80,"shadowStrength":0.5,"shadowSoft":6,"shadowBias":2,
        "lightAzimuth":-60.75,"lightElevation":61.4,"lightStrength":1,"cameraLight":0,"orbitInertia":0.5,"pinchSpeed":1.0,
        "wheelSpeed":1,"sliceScale":0.80,"sliceFloating":true,"sliceX":0.999,"sliceY":1.0,"showPlates":false,"viewCube":false,
        "viewCubeX":0,"viewCubeY":1,"pieMenu":false,"plateHd":false,"plateHdAlpha":1,"plateUnderAlpha":0}"""

    private val newKeys = setOf("interface_size", "pinch_speed", "wheel_speed", "show_plates", "plate_hd", "panel_tucked", "panel_width",
        "slice_x", "slice_y", "light_azimuth", "light_elevation")

    @Test fun everyKeyMovesToItsNewKeyAndTheOldKeysGo() {
        val json = """{"uiScale":1.1,"pinchSpeed":0.4,"wheelSpeed":1.5,"showPlates":false,"plateHd":true,"panelTucked":true,"panelWidth":300,
            "sliceX":0.25,"sliceY":0.5,"lightAzimuth":90,"lightElevation":30,"overlayAlpha":0.3,"plates":"CHIPS"}"""
        val p = FakePrefs(mapOf("layout_lab" to json, "builder_entries_split" to true, "theme" to "DARK"))
        migrateLayoutLabPrefs(p)
        assertEquals(1.1f, p.data["interface_size"])
        assertEquals(0.4f, p.data["pinch_speed"])
        assertEquals(1.5f, p.data["wheel_speed"])
        assertEquals(false, p.data["show_plates"])
        assertEquals(true, p.data["plate_hd"])
        assertEquals(true, p.data["panel_tucked"])
        assertEquals(300f, p.data["panel_width"])
        assertEquals(0.25f, p.data["slice_x"])
        assertEquals(0.5f, p.data["slice_y"])
        assertEquals(90f, p.data["light_azimuth"])
        assertEquals(30f, p.data["light_elevation"])
        assertFalse(p.contains("layout_lab"))
        assertFalse(p.contains("builder_entries_split"))
        assertEquals("DARK", p.data["theme"])
        assertEquals(newKeys + "theme", p.data.keys)
    }

    @Test fun theWsaDeviceValuesSnapAndClamp() {
        val p = FakePrefs(mapOf("layout_lab" to wsaJson))
        migrateLayoutLabPrefs(p)
        assertEquals(0.85f, p.data["interface_size"])
        assertEquals(1.0f, p.data["pinch_speed"])
        assertEquals(1f, p.data["wheel_speed"])
        assertEquals(false, p.data["show_plates"])
        assertEquals(true, p.data["panel_tucked"])
        assertEquals(700.8f, p.data["panel_width"])
        assertEquals(0.999f, p.data["slice_x"])
        assertEquals(61.4f, p.data["light_elevation"])
        assertFalse(p.contains("layout_lab"))
        assertEquals(newKeys, p.data.keys)
    }

    @Test fun outOfRangeValuesAreClampedAsTheLabParserDid() {
        val p = FakePrefs(mapOf("layout_lab" to """{"uiScale":3,"pinchSpeed":5,"wheelSpeed":0,"panelWidth":-4,"sliceX":7,"lightAzimuth":400,"lightElevation":5}"""))
        migrateLayoutLabPrefs(p)
        assertEquals(1.2f, p.data["interface_size"])
        assertEquals(1f, p.data["pinch_speed"])
        assertEquals(0.1f, p.data["wheel_speed"])
        assertEquals(0f, p.data["panel_width"])
        assertEquals(1f, p.data["slice_x"])
        assertEquals(180f, p.data["light_azimuth"])
        assertEquals(20f, p.data["light_elevation"])
    }

    @Test fun brokenJsonRemovesTheKeyAndWritesNothingElse() {
        val p = FakePrefs(mapOf("layout_lab" to "{not json", "theme" to "DARK"))
        migrateLayoutLabPrefs(p)
        assertEquals(mapOf<String, Any>("theme" to "DARK"), p.data)
    }

    @Test fun noKeyMeansNoWrites() {
        val p = FakePrefs(mapOf("theme" to "DARK"))
        migrateLayoutLabPrefs(p)
        assertEquals(0, p.writes)
        assertEquals(mapOf<String, Any>("theme" to "DARK"), p.data)
    }

    @Test fun theRetiredSplitSwitchIsRemovedAlone() {
        val p = FakePrefs(mapOf("builder_entries_split" to true))
        migrateLayoutLabPrefs(p)
        assertTrue(p.data.isEmpty())
    }

    @Test fun runningTwiceChangesNothingTheSecondTime() {
        val p = FakePrefs(mapOf("layout_lab" to wsaJson))
        migrateLayoutLabPrefs(p)
        val after = p.data.toMap()
        val writes = p.writes
        migrateLayoutLabPrefs(p)
        assertEquals(after, p.data)
        assertEquals(writes, p.writes)
    }

    /** AppBackup.import replaces the whole prefs file (clear + puts) and restarts the app, so the next start migrates the old JSON. */
    @Test fun aRestoredOldBackupIsMigratedOnTheNextStart() {
        val p = FakePrefs(mapOf("interface_size" to 1.2f, "theme" to "LIGHT"))
        migrateLayoutLabPrefs(p)
        assertEquals(1.2f, p.data["interface_size"])
        // The restore: clear, then the backup's keys (an old backup still carries layout_lab and the split switch).
        p.edit().clear().putString("layout_lab", """{"uiScale":0.9,"showPlates":false}""").putBoolean("builder_entries_split", true).putString("theme", "DARK").commit()
        migrateLayoutLabPrefs(p)
        assertEquals(0.9f, p.data["interface_size"])
        assertEquals(false, p.data["show_plates"])
        assertFalse(p.contains("layout_lab"))
        assertFalse(p.contains("builder_entries_split"))
        assertEquals("DARK", p.data["theme"])
    }

    @Test fun interfaceSizeSnapsToTheNearestStep() {
        assertEquals(0.85f, ViewDefaults.snapInterfaceSize(0.7f))
        assertEquals(0.9f, ViewDefaults.snapInterfaceSize(0.92f))
        assertEquals(1f, ViewDefaults.snapInterfaceSize(1.04f))
        assertEquals(1.2f, ViewDefaults.snapInterfaceSize(1.3f))
        assertNull(ViewDefaults.INTERFACE_SIZES.firstOrNull { ViewDefaults.snapInterfaceSize(it) != it })
    }

    @Test fun theSmallestInterfaceSizeKeepsToolButtonsAtLeast40Dp() {
        assertTrue(ViewDefaults.TOOL_BUTTON_DP * ViewDefaults.INTERFACE_SIZES.min() >= ViewDefaults.TOOL_BUTTON_MIN_DP)
        assertTrue(48f * ViewDefaults.INTERFACE_SIZES.min() >= 40f)
    }
}
