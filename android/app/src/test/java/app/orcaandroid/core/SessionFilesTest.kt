package app.orcaandroid.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionFilesTest {
    private lateinit var dir: File
    private lateinit var files: SessionFiles

    @Before fun setUp() {
        dir = Files.createTempDirectory("session-files").toFile()
        files = SessionFiles(dir)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun savePair(project: String, info: String): Boolean {
        files.tmpProject.writeText(project)
        return files.commit(info)
    }

    @Test fun commitSwapsInTheNewPair() {
        assertTrue(savePair("p1", "i1"))
        assertEquals("p1", files.project.readText())
        assertEquals("i1", files.info.readText())
        assertTrue(savePair("p2", "i2"))
        assertEquals("p2", files.project.readText())
        assertEquals("i2", files.info.readText())
        assertFalse(files.tmpProject.exists())
        assertFalse(files.tmpInfo.exists())
        assertEquals(setOf("session.3mf", "session.json"), dir.list()!!.toSet())
    }

    @Test fun commitWithoutTheTemporaryProjectKeepsTheOldPair() {
        assertTrue(savePair("p1", "i1"))
        assertFalse(files.commit("i2"))
        assertEquals("p1", files.project.readText())
        assertEquals("i1", files.info.readText())
    }

    @Test fun commitThatFailsOnTheInfoStepPutsTheOldProjectBack() {
        assertTrue(savePair("p1", "i1"))
        // A directory where the info has to go makes that last rename fail.
        files.info.delete()
        files.info.mkdir()
        File(files.info, "x").writeText("x")
        assertFalse(savePair("p2", "i2"))
        assertEquals("p1", files.project.readText())
        assertFalse(files.tmpInfo.exists())
    }

    @Test fun quarantineKeepsOneGeneration() {
        assertTrue(savePair("p1", "i1"))
        files.quarantine()
        assertFalse(files.project.exists())
        assertFalse(files.info.exists())
        assertTrue(files.hasBad())
        assertTrue(savePair("p2", "i2"))
        files.quarantine()
        assertTrue(files.hasBad())
        assertEquals(setOf("session.bad.3mf", "session.bad.json"), dir.list()!!.toSet())
        assertEquals("p2", File(dir, "session.bad.3mf").readText())
        assertEquals("i2", File(dir, "session.bad.json").readText())
    }

    @Test fun quarantineWithoutASessionKeepsTheOlderBadPair() {
        assertTrue(savePair("p1", "i1"))
        files.quarantine()
        files.quarantine()
        assertEquals("p1", File(dir, "session.bad.3mf").readText())
    }

    @Test fun restoreBadRoundTrip() {
        assertTrue(savePair("p1", "i1"))
        files.quarantine()
        assertTrue(files.restoreBad())
        assertFalse(files.hasBad())
        assertEquals("p1", files.project.readText())
        assertEquals("i1", files.info.readText())
        assertFalse(files.restoreBad())
    }

    @Test fun discardBadAndClear() {
        assertTrue(savePair("p1", "i1"))
        files.quarantine()
        assertTrue(savePair("p2", "i2"))
        files.discardBad()
        assertFalse(files.hasBad())
        assertTrue(files.project.exists())
        files.clear()
        assertEquals(0, dir.list()!!.size)
    }
}
