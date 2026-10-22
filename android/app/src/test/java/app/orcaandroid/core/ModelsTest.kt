package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expectations for the small pure helpers in `core/Models.kt`: [printerKey]'s nozzle
 * normalisation and [OptionDef]'s vector/base-type derivation from the native type
 * string. Values are what the code produces today, worked out by reading it.
 */
class ModelsTest {

    @Test fun printerKeyNormalisesNumericNozzle() {
        // "0.40" parses as a Double and is written back in canonical form.
        assertEquals("Bambu Lab A1|0.4", printerKey("Bambu Lab A1", "0.4"))
        assertEquals("Bambu Lab A1|0.4", printerKey("Bambu Lab A1", "0.40"))
        assertEquals("Qidi Q1 Pro|0.6", printerKey("Qidi Q1 Pro", "0.60"))
    }

    @Test fun printerKeyKeepsNonNumericNozzleVerbatim() {
        // Not a number: the string is used as-is, so the key still distinguishes it.
        assertEquals("Model|0.4mm", printerKey("Model", "0.4mm"))
        assertEquals("Model|hardened", printerKey("Model", "hardened"))
    }

    @Test fun printerKeyDoubleOverload() {
        // The Double overload (PrinterInfo.key) formats with Double.toString.
        assertEquals("Model|0.4", printerKey("Model", 0.4))
        assertEquals("Model|1.0", printerKey("Model", 1.0))
        // Both overloads agree for the same numeric nozzle.
        assertEquals(printerKey("Model", "0.4"), printerKey("Model", 0.4))
    }

    private fun def(type: String) = OptionDef(
        key = "k", label = "K", fullLabel = "K", category = "", tooltip = "", type = type,
        sidetext = "", mode = 0, min = null, max = null, enumValues = emptyList(),
        enumLabels = emptyList(), readonly = false, multiline = false, isCode = false,
    )

    @Test fun optionDefIsVector() {
        // Plural native types are vectors; the singular ones are not, and
        // "float_or_percent" is explicitly excluded despite not being plural either.
        assertTrue(def("floats").isVector)
        assertTrue(def("ints").isVector)
        assertTrue(def("strings").isVector)
        assertTrue(def("bools").isVector)
        assertTrue(def("percents").isVector)
        assertFalse(def("float").isVector)
        assertFalse(def("string").isVector)
        assertFalse(def("float_or_percent").isVector)
        assertFalse(def("enum").isVector)
    }

    @Test fun optionDefBaseType() {
        // Vector types unwrap to their element type; "floats_or_percents" unwraps to
        // the singular compound; anything else passes through unchanged.
        assertEquals("float", def("floats").baseType)
        assertEquals("int", def("ints").baseType)
        assertEquals("percent", def("percents").baseType)
        assertEquals("bool", def("bools").baseType)
        assertEquals("string", def("strings").baseType)
        assertEquals("float_or_percent", def("floats_or_percents").baseType)
        assertEquals("enum", def("enums").baseType)
        assertEquals("float", def("float").baseType)
        assertEquals("percent", def("percent").baseType)
    }
}
