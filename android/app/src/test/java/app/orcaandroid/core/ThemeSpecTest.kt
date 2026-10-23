package app.orcaandroid.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ThemeSpec]'s JSON round trip and its edge cases: stored theme settings are
 * user data, so a parse surprise silently resets the user's colours. ARGB values are
 * plain [Int]s, so opaque colours carry the sign bit (e.g. 0xFFFF0000 is negative) and
 * must survive the "#%08X" text form exactly.
 */
class ThemeSpecTest {

    @Test fun fromJsonNullAndMalformedAreDefault() {
        assertEquals(ThemeSpec(), ThemeSpec.fromJson(null))
        assertEquals(ThemeSpec(), ThemeSpec.fromJson(""))
        assertEquals(ThemeSpec(), ThemeSpec.fromJson("not json"))
        assertEquals(ThemeSpec(), ThemeSpec.fromJson("[]"))
        assertEquals(ThemeSpec(), ThemeSpec.fromJson("{}"))
    }

    @Test fun presetKeptAndEmptyPresetFallsBack() {
        assertEquals("orca", ThemeSpec.fromJson("""{"preset":"orca"}""").preset)
        assertEquals(ThemeSpec.DEFAULT_PRESET, ThemeSpec.fromJson("""{"preset":""}""").preset)
        assertEquals(ThemeSpec.DEFAULT_PRESET, ThemeSpec.fromJson("""{"dark":{}}""").preset)
    }

    @Test fun roundTripPreservesEverything() {
        val spec = ThemeSpec(
            preset = "orca",
            dark = mapOf("chrome" to mapOf("accent" to 0xFFFF0000.toInt(), "bg" to 0xFF102030.toInt())),
            light = mapOf("canvas" to mapOf("grid" to 0x11000000)),
        )
        assertEquals(spec, ThemeSpec.fromJson(spec.toJson()))
    }

    @Test fun argbSignBitFormatsAsEightHexDigits() {
        val spec = ThemeSpec(dark = mapOf("r" to mapOf("a" to -1, "b" to Int.MIN_VALUE, "c" to 0)))
        val slots = JSONObject(spec.toJson()).getJSONObject("dark").getJSONObject("r")
        assertEquals("#FFFFFFFF", slots.getString("a"))
        assertEquals("#80000000", slots.getString("b"))
        assertEquals("#00000000", slots.getString("c"))
        // And each parses back to the same Int.
        assertEquals(spec, ThemeSpec.fromJson(spec.toJson()))
    }

    @Test fun withSetsOverwritesAndCounts() {
        val spec = ThemeSpec()
            .with(dark = true, region = "chrome", slot = "accent", argb = 0xFF0000FF.toInt())
            .with(dark = true, region = "chrome", slot = "bg", argb = 0xFF000000.toInt())
            .with(dark = true, region = "chrome", slot = "accent", argb = 0xFF00FF00.toInt())
        assertEquals(0xFF00FF00.toInt(), spec.dark.getValue("chrome").getValue("accent"))
        assertEquals(2, spec.customCount(dark = true))
        assertEquals(0, spec.customCount(dark = false))
    }

    @Test fun withNullRemovesSlotAndEmptyRegionDisappears() {
        val spec = ThemeSpec()
            .with(dark = false, region = "chrome", slot = "accent", argb = 5)
            .with(dark = false, region = "chrome", slot = "accent", argb = null)
        assertEquals(emptyMap<String, Map<String, Int>>(), spec.light)
        // Removing a slot that was never set is a no-op.
        assertEquals(ThemeSpec(), ThemeSpec().with(dark = true, region = "x", slot = "y", argb = null))
    }

    @Test fun withAndResetTouchOnlyTheChosenMode() {
        val spec = ThemeSpec()
            .with(dark = true, region = "chrome", slot = "accent", argb = 1)
            .with(dark = false, region = "chrome", slot = "accent", argb = 2)
        val reset = spec.reset(dark = true)
        assertEquals(emptyMap<String, Map<String, Int>>(), reset.dark)
        assertEquals(mapOf("chrome" to mapOf("accent" to 2)), reset.light)
    }

    @Test fun resetOneRegionKeepsTheOthers() {
        val spec = ThemeSpec()
            .with(dark = true, region = "a", slot = "s", argb = 1)
            .with(dark = true, region = "b", slot = "s", argb = 2)
            .reset(dark = true, region = "a")
        assertEquals(mapOf("b" to mapOf("s" to 2)), spec.dark)
    }

    @Test fun customCountSumsAcrossRegions() {
        val spec = ThemeSpec(light = mapOf("a" to mapOf("x" to 1, "y" to 2), "b" to mapOf("z" to 3)))
        assertEquals(3, spec.customCount(dark = false))
    }

    @Test fun malformedSlotsDroppedOthersKept() {
        val spec = ThemeSpec.fromJson(
            """{"dark":{"chrome":{"good":"#FF112233","named":"red","bad":"#GGGGGG","nested":{"a":1}}}}"""
        )
        assertEquals(mapOf("chrome" to mapOf("good" to 0xFF112233.toInt())), spec.dark)
        // A region whose slots are all malformed disappears entirely.
        assertEquals(ThemeSpec(), ThemeSpec.fromJson("""{"dark":{"chrome":{"bad":"red"}}}"""))
    }

    @Test fun hexWithoutHashAndLowercaseParse() {
        val spec = ThemeSpec.fromJson("""{"light":{"r":{"a":"ff112233","b":"#aabbccdd"}}}""")
        assertEquals(0xFF112233.toInt(), spec.light.getValue("r").getValue("a"))
        assertEquals(0xAABBCCDD.toInt(), spec.light.getValue("r").getValue("b"))
    }

    @Test fun nonObjectRegionSkippedOthersKept() {
        // A region stored as a scalar or an array must not take the whole spec down:
        // the preset and the other mode's valid overrides are user data too.
        val spec = ThemeSpec.fromJson(
            """{"preset":"orca","dark":{"chrome":5,"canvas":[1,2]},"light":{"chrome":{"accent":"#FF000001"}}}"""
        )
        assertEquals("orca", spec.preset)
        assertEquals(emptyMap<String, Map<String, Int>>(), spec.dark)
        assertEquals(mapOf("chrome" to mapOf("accent" to 0xFF000001.toInt())), spec.light)
    }

    @Test fun numericSlotValueCoercedToday() {
        // org.json's optString stringifies a numeric slot, and the digits then parse
        // as hex: 255 (decimal in the file) comes back as 0x255. Harmless for real
        // files (toJson always writes "#%08X" strings); pinned, not fixed.
        val spec = ThemeSpec.fromJson("""{"dark":{"r":{"a":255}}}""")
        assertEquals(0x255, spec.dark.getValue("r").getValue("a"))
    }

    @Test fun overlongHexTruncatesToday() {
        // More than 8 hex digits still fit a Long, so toInt() silently truncates
        // instead of dropping the slot. Real files never hold these; pinned.
        val spec = ThemeSpec.fromJson("""{"dark":{"r":{"a":"#1FFFFFFFF"}}}""")
        assertEquals(-1, spec.dark.getValue("r").getValue("a"))
        assertTrue(spec.toJson().contains("#FFFFFFFF"))
    }
}
