package app.orcaandroid.ui

import app.orcaandroid.ui.components.liveLabelNumber
import org.junit.Test
import org.junit.Assert.assertEquals

class LiveLabelTest {
    @Test fun oneDecimalWithDotAndOptionalSign() {
        assertEquals("3.0", liveLabelNumber(3f))
        assertEquals("12.3", liveLabelNumber(12.34f))
        assertEquals("+5.0", liveLabelNumber(5f, signed = true))
        assertEquals("-0.5", liveLabelNumber(-0.5f, signed = true))
    }
}
