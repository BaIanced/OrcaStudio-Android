package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeshConvertTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(): java.io.File {
        val dir = tmp.newFolder("model")
        java.io.File(dir, "m.gltf").writeText("""{"asset":{"version":"2.0"},"buffers":[{"uri":"../secret.bin","byteLength":1}]}""")
        java.io.File(dir, "m.bin").writeBytes(byteArrayOf(1))
        java.io.File(dir.parentFile, "secret.bin").writeBytes(byteArrayOf(2))
        return java.io.File(dir, "m.gltf")
    }

    @Test fun parentTraversalIsRefused() {
        val f = fixture()
        assertThrows(IllegalArgumentException::class.java) { MeshConvert.resolveSibling(f, "../secret.bin") }
        assertThrows(IllegalArgumentException::class.java) { MeshConvert.resolveSibling(f, "..%2Fsecret.bin") }
    }

    @Test fun absoluteAndSchemeUrisAreRefused() {
        val f = fixture()
        for (u in listOf("/etc/passwd", "file:///etc/passwd", "http://x/y.bin", "C:/x.bin")) {
            assertThrows(u, IllegalArgumentException::class.java) { MeshConvert.resolveSibling(f, u) }
        }
    }

    @Test fun siblingBinIsAccepted() {
        val f = fixture()
        val r = MeshConvert.resolveSibling(f, "m.bin")
        assertEquals(f.parentFile.canonicalFile, r.parentFile)
        assertEquals(1, r.readBytes().size)
    }
}
