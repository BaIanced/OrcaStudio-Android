package app.orcaandroid.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the small `Json.kt` helpers the engine bridge is built on. These run against
 * the real org.json (added as a test dependency for this task); the Android runtime's
 * org.json coerces the same way for every case pinned here.
 */
class JsonHelpersTest {

    @Test fun throwIfErrorPassesCleanObjectThrough() {
        val o = JSONObject("""{"ok":1}""")
        assertSame(o, o.throwIfError())
    }

    @Test fun throwIfErrorThrowsOrcaExceptionWithMessage() {
        val e = assertThrows(OrcaException::class.java) {
            JSONObject("""{"error":"engine exploded"}""").throwIfError()
        }
        assertEquals("engine exploded", e.message)
    }

    @Test fun arrayMapTransformsEveryElement() {
        val doubled = JSONArray("[1,2,3]").map { (it as Number).toInt() * 2 }
        assertEquals(listOf(2, 4, 6), doubled)
        assertEquals(emptyList<Any>(), JSONArray("[]").map { it })
    }

    @Test fun floatsNullAndValuesAndNumericStrings() {
        val none: JSONArray? = null
        assertEquals(0, none.floats().size)
        val a = JSONArray("[1.5, -2.25, 3]").floats()
        assertEquals(listOf(1.5f, -2.25f, 3f), a.toList())
        // getDouble parses numeric strings too.
        assertEquals(listOf(0.5f), JSONArray("""["0.5"]""").floats().toList())
    }

    @Test fun vec3NeedsThreeNumbers() {
        val o = JSONObject("""{"p":[1.0,2.0,3.0,4.0],"q":[1.0,2.0]}""")
        assertEquals(Vec3(1f, 2f, 3f), o.vec3("p")) // the 4th number is ignored
        assertEquals(Vec3.ZERO, o.vec3("q")) // too few numbers
        assertEquals(Vec3.ZERO, o.vec3("missing"))
    }

    @Test fun stringMapCoercesValuesAndSkipsMissing() {
        val o = JSONObject("""{"m":{"n":5,"b":true,"x":null,"o":{"a":1}}}""")
        val map = o.stringMap("m")
        assertEquals("5", map["n"])
        assertEquals("true", map["b"])
        // JSONObject.NULL stringifies to "null" instead of skipping the key; the
        // engine's maps are settings, where the literal is visible. Pinned.
        assertEquals("null", map["x"])
        assertEquals("""{"a":1}""", map["o"])
        assertEquals(emptyMap<String, String>(), o.stringMap("missing"))
    }

    @Test fun stringsOnNullAndObject() {
        val none: JSONObject? = null
        assertEquals(emptyMap<String, String>(), none.strings())
        assertEquals(mapOf("a" to "1"), JSONObject("""{"a":1}""").strings())
    }

    @Test fun toJsonNullBecomesJsonNullSentinel() {
        val o = mapOf<String, Any?>("k" to null).toJson()
        assertSame(JSONObject.NULL, o.get("k"))
        assertEquals("""{"k":null}""", o.toString())
    }

    @Test fun toJsonNestedMapsCollectionsAndVec3() {
        val o = mapOf<String, Any?>(
            "nested" to mapOf("a" to 1),
            "listOfMaps" to listOf(mapOf("b" to 2)),
            "listOfScalars" to listOf(1, "x", true),
            "vec" to Vec3(1.5f, -2.5f, 3f),
            "floats" to floatArrayOf(0.5f, 2f),
        ).toJson()
        assertEquals(1, o.getJSONObject("nested").getInt("a"))
        assertEquals(2, o.getJSONArray("listOfMaps").getJSONObject(0).getInt("b"))
        assertEquals("x", o.getJSONArray("listOfScalars").getString(1))
        val vec = o.getJSONArray("vec")
        assertEquals(1.5, vec.getDouble(0), 0.0)
        assertEquals(-2.5, vec.getDouble(1), 0.0)
        assertEquals(3.0, vec.getDouble(2), 0.0)
        assertEquals(0.5, o.getJSONArray("floats").getDouble(0), 0.0)
    }

    @Test fun toJsonFloatWidensToDoubleToday() {
        // A stored Float keeps its Float -> Double widening: 0.1f reads back as
        // 0.10000000149011612, not 0.1. Callers compare with tolerances; pinned.
        val o = mapOf<String, Any?>("f" to 0.1f).toJson()
        assertEquals(0.1f.toDouble(), o.getDouble("f"), 0.0)
        assertTrue(o.getDouble("f") != 0.1)
    }

    @Test fun argsBuildsObjectAndLastDuplicateWins() {
        val o = args("a" to 1, "b" to "x", "n" to null)
        assertEquals(1, o.getInt("a"))
        assertEquals("x", o.getString("b"))
        assertSame(JSONObject.NULL, o.get("n"))
        assertEquals(2, args("a" to 1, "a" to 2).getInt("a"))
        assertEquals(0, args().length())
    }

    @Test fun errorKeyWithNonStringValueIsNotOrcaExceptionToday() {
        // getString is strict in org.json: a numeric "error" surfaces as a
        // JSONException, not an OrcaException. The engine always sends a string
        // message; pinned, not fixed.
        assertThrows(JSONException::class.java) {
            JSONObject("""{"error":5}""").throwIfError()
        }
    }
}
