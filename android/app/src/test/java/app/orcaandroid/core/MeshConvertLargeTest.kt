package app.orcaandroid.core

import app.orcaandroid.net.ArchiveTooLargeException
import app.orcaandroid.net.ModelDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MeshConvertLargeTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A binary little-endian PLY with [triangles] triangles on [triangles] + 2 shared vertices (a strip). */
    private fun plyBytes(triangles: Int): ByteArray {
        val verts = triangles + 2
        val header = "ply\nformat binary_little_endian 1.0\nelement vertex $verts\nproperty float x\nproperty float y\nproperty float z\n" +
            "element face $triangles\nproperty list uchar int vertex_indices\nend_header\n"
        val buf = ByteBuffer.allocate(header.length + verts * 12 + triangles * 13).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(header.toByteArray(Charsets.ISO_8859_1))
        for (i in 0 until verts) { buf.putFloat((i / 2).toFloat()); buf.putFloat((i % 2).toFloat()); buf.putFloat((i % 7).toFloat()) }
        for (t in 0 until triangles) { buf.put(3); buf.putInt(t); buf.putInt(t + 1); buf.putInt(t + 2) }
        return buf.array()
    }

    @Test fun twoHundredThousandTrianglesConvertAtDefaultHeap() {
        val ply = tmp.newFile("big.ply").apply { writeBytes(plyBytes(200_000)) }
        val out = MeshConvert.convert(ply, listOf(0xFFFFFF))
        assertTrue(out != null && out.exists() && out.length() > 0)
        val xml = java.util.zip.ZipFile(out!!).use { z -> z.getInputStream(z.getEntry("3D/3dmodel.model")).readBytes().toString(Charsets.UTF_8) }
        // Degenerate-free strip: every triangle is kept.
        assertEquals(200_000, Regex("<triangle ").findAll(xml).count())
    }

    @Test fun smallAsciiPlyConvertsToExpectedMesh() {
        val ply = tmp.newFile("tri.ply").apply {
            writeText("ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n" +
                "element face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n1 0 0\n0 1 0\n3 0 1 2\n")
        }
        val out = MeshConvert.convert(ply, listOf(0xFFFFFF))!!
        val xml = java.util.zip.ZipFile(out).use { z -> z.getInputStream(z.getEntry("3D/3dmodel.model")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(xml.contains("<vertex x=\"1.0\" y=\"0.0\" z=\"0.0\"/>"))
        assertTrue(xml.contains("<triangle v1=\"0\" v2=\"1\" v3=\"2\"/>"))
    }

    @Test fun trianglesOverTheCapAreRefusedWithTheRealCount() {
        // A 5 million triangle file is too big for a unit test; the cap is checked through the builder the readers use.
        val b = MeshConvert.TriBuilder(limit = 1000)
        repeat(1000) { b.add(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, -1) }
        assertTrue(b.over)
        repeat(1_500_000) { b.skip() }
        val e = assertThrows(MeshConvert.TooManyTrianglesException::class.java) { b.finish() }
        assertEquals(1_501_000L, e.triangles)
    }

    @Test fun archiveLimits() {
        ModelDownloads.checkArchiveLimits(10_000, 2L * 1024 * 1024 * 1024)
        assertThrows(ArchiveTooLargeException::class.java) { ModelDownloads.checkArchiveLimits(10_001, 0) }
        assertThrows(ArchiveTooLargeException::class.java) { ModelDownloads.checkArchiveLimits(1, 2L * 1024 * 1024 * 1024 + 1) }
    }

    @Test fun zipWithTooManyEntriesIsRefused() {
        val zip = tmp.newFile("many.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (i in 0..10_000) { z.putNextEntry(ZipEntry("f$i.txt")); z.closeEntry() }
        }
        assertThrows(ArchiveTooLargeException::class.java) { ModelDownloads.modelFiles(zip) }
    }
}
