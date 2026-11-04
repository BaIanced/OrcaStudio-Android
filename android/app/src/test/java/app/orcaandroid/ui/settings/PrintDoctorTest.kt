package app.orcaandroid.ui.settings

import app.orcaandroid.core.PresetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintDoctorTest {
    private fun fix(op: DoctorOp) = DoctorFix(PresetType.PRINT, "k", op)

    @Test fun addKeepsTheRange() {
        assertEquals("1.2", fix(DoctorOp.Add(0.4f, 0f, 3f)).newValue("0.8"))
        assertEquals("3", fix(DoctorOp.Add(0.4f, 0f, 3f)).newValue("2.9"))
        assertEquals("150", fix(DoctorOp.Add(-5f, 150f)).newValue("152"))
    }

    @Test fun scaleKeepsPercentAndRange() {
        assertEquals("3000", fix(DoctorOp.Scale(0.6f, 500f, 20000f)).newValue("5000"))
        assertEquals("30%", fix(DoctorOp.Scale(0.5f)).newValue("60%"))
    }

    @Test fun nothingToChange() {
        assertNull(fix(DoctorOp.Set("1")).newValue("1"))
        assertNull(fix(DoctorOp.Add(1f)).newValue(null))
        assertNull(fix(DoctorOp.Scale(2f)).newValue("200,200"))
        assertNull(fix(DoctorOp.Add(5f, max = 10f)).newValue("10"))
    }

    @Test fun everySymptomHasFixes() {
        DOCTOR_SYMPTOMS.forEach { assert(it.fixes.isNotEmpty()) }
        assertEquals(DOCTOR_SYMPTOMS.size, DOCTOR_SYMPTOMS.map { it.id }.toSet().size)
    }
}
