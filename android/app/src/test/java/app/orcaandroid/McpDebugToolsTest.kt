package app.orcaandroid

import app.orcaandroid.ai.LogMask
import app.orcaandroid.render.FrameStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the MCP debug tools: log_tail's masking and perf's frame statistics. */
class McpDebugToolsTest {
    @Test fun masksSecretValues() {
        assertEquals("I/Obn: pin_code=*** host ok", LogMask.mask("I/Obn: pin_code=xy host ok"))
        assertEquals("{\"token\": \"***\", \"user\": \"x\"}", LogMask.mask("{\"token\": \"abc.def\", \"user\": \"x\"}"))
        assertEquals("Authorization: Bearer ***", LogMask.mask("Authorization: Bearer xyz.1"))
        assertEquals("cookie=***; path=/", LogMask.mask("cookie=a1b2; path=/"))
    }

    @Test fun leavesPlainLinesAlone() {
        val line = "D/OrcaInput: 2f PAN shift 80 gap 12"
        assertEquals(line, LogMask.mask(line))
    }

    @Test fun frameStatsNeedTwoFrames() {
        val s = FrameStats()
        assertTrue(s.report().startsWith("No frames"))
        s.record(0, 1_000_000)
        assertTrue(s.report().startsWith("No frames"))
    }

    @Test fun frameStatsAt60Fps() {
        val s = FrameStats()
        val gap = 16_666_667L
        for (i in 0 until 300) s.record(i * gap, i * gap + 4_000_000)
        val r = s.report()
        assertTrue(r, r.startsWith("Last 240 frames"))
        assertTrue(r, r.contains("60.0 fps"))
        assertTrue(r, r.contains("median 16.7"))
        assertTrue(r, r.contains("0 gaps over 33 ms"))
        assertTrue(r, r.contains("draw ms median 4.0"))
    }

    @Test fun frameStatsCountLongGaps() {
        val s = FrameStats()
        var t = 0L
        for (i in 0 until 100) { t += if (i % 10 == 9) 50_000_000L else 16_666_667L; s.record(t, t + 1_000_000) }
        val r = s.report()
        assertTrue(r, r.contains("; 10 gaps over 33 ms"))
        assertTrue(r, r.contains("worst 50.0"))
    }
}
