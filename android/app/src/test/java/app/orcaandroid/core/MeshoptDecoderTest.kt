package app.orcaandroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM tests for [MeshoptDecoder]. The encoded streams below were produced
 * by the REAL meshoptimizer encoder, compiled in the build VM from the
 * master sources (vertexcodec.cpp / indexcodec.cpp / vertexfilter.cpp,
 * read 2026-11-09) and run over known inputs; the expected outputs are the
 * real decoder's outputs on those streams. Total committed vector bytes
 * are ~0.7 KB, under the task's 4 KB cap.
 */
class MeshoptDecoderTest {

    private fun bytes(hex: String): ByteArray {
        val parts = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
    }

    private fun floatsToBytes(values: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buf.putFloat(v)
        return buf.array()
    }

    private fun bytesToFloats(data: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(data.size / 4) { buf.getFloat(it * 4) }
    }

    private fun bytesToShorts(data: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(data.size / 2) { buf.getShort(it * 2).toInt() }
    }

    private fun expectThrow(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // 20 vertices x 12 bytes: vertex i = (i, i % 3, 20 - i) as floats.
    private val vertexStream = bytes(
        """
        a1 3a da ba 00 00 00 80 80 40 40 40 40 20 20 20 20 20 20 20 20 10 10 10
        01 06 00 7f 01 00 48 92 ff ff ff ff ff 04 00 ff 00 7f 01 7f 7f 01 7f 7f
        01 7f 7f 01 7f 7f 01 7f 7f 01 7f 7f 00 0f 0f 0f 0f 1f 1f 1f 1f 1f 1f 1f
        1f 3f 3f 3f 3f 7f 7f ff 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 a0 41 01 01 01
        """)

    // 300 vertices x 4 bytes: vertex i = (i % 7) as float; crosses the
    // 256-vertex block boundary.
    private val vertex300Stream = bytes(
        """
        a1 5a 00 00 00 00 f8 7c 80 80 40 40 7f 80 80 40 40 7f 3e 9f 80 80 40 40
        7f 80 80 40 40 7f 80 cf e7 80 40 40 7f 80 80 40 40 7f 80 80 40 f3 f9 40
        7f 80 80 40 40 7f 80 80 40 40 7f 7c 3e 80 80 40 40 7f 80 80 40 40 7f 9f
        cf 80 80 40 40 7f 80 80 40 40 7f 80 80 e7 f3 40 40 7f 80 80 40 40 7f 80
        80 40 40 f9 7c 7f 80 80 40 40 7f 80 80 40 40 7f 3e 9f 80 80 40 40 7f 80
        80 40 40 7f 80 cf e7 80 40 40 7f 80 80 40 40 7f 80 80 40 f3 f9 40 7f 80
        80 40 40 7f 80 80 40 40 7f 7c 3e 80 80 40 40 7f 80 80 40 40 7f 9f cf 80
        80 40 40 7f 80 80 40 40 7f 80 80 e7 f3 40 40 7f 80 80 40 40 7f 80 80 40
        40 f9 7c 7f 80 80 40 40 7f 80 80 40 40 7f 3e 9f 80 80 40 40 7f 80 80 40
        40 7f 80 54 55 55 55 86 c3 7f 01 81 7f 01 81 7f 40 3d 00 f4 81 7f 81 7f
        03 d0 0f 40 81 7f 81 7f 3d 00 f4 03 81 7f 81 7f 81 d0 0f 40 3d 7f 81 7f
        81 7f 00 f4 03 d0 81 7f 81 7f 0f 40 3d 00 81 7f 81 7f f4 03 d0 0f 81 7f
        81 7f 81 7f 40 3d 00 f4 81 7f 81 7f 03 d0 0f 40 81 7f 81 7f 3d 00 f4 03
        81 7f 81 7f 81 d0 0f 40 3d 7f 81 7f 81 7f 00 f4 03 d0 81 7f 81 7f 0f 40
        3d 00 81 7f 81 7f f4 03 d0 0f 81 7f 81 7f 81 7f 40 3d 00 f4 81 7f 81 7f
        5a 00 cf e7 80 40 40 7f 80 80 40 40 7f 80 80 40 f3 f9 40 7f 80 80 40 40
        7f 80 80 40 40 7f 7c 0e 80 80 40 40 7f 80 80 40 00 38 1c 81 7f 01 81 7f
        01 0e 87 81 7f 01 81 7f 01 81 c3 01 7f 01 81 7f 01 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01
        """)

    // 12 indices: (0,1,2) (2,1,3) (3,1,4) (4,5,6) as fed to the encoder.
    private val indexStream = bytes("e1 f0 10 10 fc 00 76 87 56 67 78 a9 86 65 89 68 98 01 69 00 00")

    // 6 sequence indices: 5 3 9 9 2 14.
    private val sequenceStream = bytes("d1 14 06 18 00 1a 30 00 00 00 00")

    @Test
    fun vertexBufferMatchesReferenceDecoder() {
        val decoded = MeshoptDecoder.decodeVertexBuffer(vertexStream, 20, 12)
        val expected = FloatArray(60) { i ->
            val v = i / 3
            when (i % 3) {
                0 -> v.toFloat()
                1 -> (v % 3).toFloat()
                else -> (20 - v).toFloat()
            }
        }
        assertArrayEquals(floatsToBytes(expected), decoded)
    }

    @Test
    fun vertexBufferCrossesBlockBoundary() {
        val decoded = MeshoptDecoder.decodeVertexBuffer(vertex300Stream, 300, 4)
        val values = bytesToFloats(decoded)
        assertEquals(300, values.size)
        for (i in 0 until 300) assertEquals("vertex $i", (i % 7).toFloat(), values[i], 0f)
    }

    @Test
    fun indexBufferMatchesReferenceDecoder() {
        val decoded = MeshoptDecoder.decodeIndexBuffer(indexStream, 12, 4)
        // The reference encoder rotated the last triangle (4,5,6) -> (5,6,4);
        // the reference decoder reproduces the rotated order, and so do we.
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 1, 3, 3, 1, 4, 5, 6, 4), decoded)
    }

    @Test
    fun indexBufferDecodesAs16BitToo() {
        val decoded = MeshoptDecoder.decodeIndexBuffer(indexStream, 12, 2)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 1, 3, 3, 1, 4, 5, 6, 4), decoded)
    }

    @Test
    fun indexSequenceMatchesReferenceDecoder() {
        assertArrayEquals(intArrayOf(5, 3, 9, 9, 2, 14),
            MeshoptDecoder.decodeIndexSequence(sequenceStream, 6, 4))
        assertArrayEquals(intArrayOf(5, 3, 9, 9, 2, 14),
            MeshoptDecoder.decodeIndexSequence(sequenceStream, 6, 2))
    }

    @Test
    fun filterOctahedral8Bit() {
        val data = bytes("7f 00 7f 7f 00 7f 7f 7f 00 00 7f 7f db 25 7f 7f")
        MeshoptDecoder.filterOctahedral(data, 4, 4)
        assertArrayEquals(
            byteArrayOf(127, 0, 0, 127, 0, 127, 0, 127, 0, 0, 127, 127, -63, 63, 90, 127),
            data)
    }

    @Test
    fun filterOctahedral16Bit() {
        val data = bytes(
            """
            ff 1f 00 00 ff 1f ff 7f 00 00 ff 1f ff 1f ff 7f 00 00 00 00 ff 1f ff 7f
            a1 f6 5f 09 ff 1f ff 7f
            """)
        MeshoptDecoder.filterOctahedral(data, 4, 8)
        assertArrayEquals(
            intArrayOf(32767, 0, 0, 32767, 0, 32767, 0, 32767,
                0, 0, 32767, 32767, -16383, 16383, 23171, 32767),
            bytesToShorts(data))
    }

    @Test
    fun filterQuaternionReconstructsDroppedComponent() {
        val data = bytes("00 00 00 00 00 00 ff 1f a0 16 a0 16 a0 16 fc 1f")
        MeshoptDecoder.filterQuaternion(data, 2, 8)
        assertArrayEquals(
            intArrayOf(0, 0, 0, 32767, 16383, 16384, 16384, 16384),
            bytesToShorts(data))
    }

    @Test
    fun filterExponentialDecodes() {
        val data = bytes("00 00 60 ea 00 00 c0 e8 00 00 64 f0 38 89 41 e0")
        MeshoptDecoder.filterExponential(data)
        val values = bytesToFloats(data)
        assertEquals(1.5f, values[0], 0f)
        assertEquals(-0.25f, values[1], 0f)
        assertEquals(100f, values[2], 0f)
        assertEquals(0.001f, values[3], 0.000001f)
    }

    @Test
    fun badVertexArgumentsThrow() {
        expectThrow { MeshoptDecoder.decodeVertexBuffer(vertexStream, 20, 10) } // not /4
        expectThrow { MeshoptDecoder.decodeVertexBuffer(vertexStream, 20, 0) }
        expectThrow { MeshoptDecoder.decodeVertexBuffer(ByteArray(0), 0, 12) }
    }

    @Test
    fun badVertexHeaderAndVersionThrow() {
        val badMagic = vertexStream.copyOf()
        badMagic[0] = 0x55
        expectThrow { MeshoptDecoder.decodeVertexBuffer(badMagic, 20, 12) }
        val badVersion = vertexStream.copyOf()
        badVersion[0] = 0xA2.toByte() // version 2 unsupported
        expectThrow { MeshoptDecoder.decodeVertexBuffer(badVersion, 20, 12) }
    }

    @Test
    fun truncatedAndTrailingVertexStreamsThrow() {
        expectThrow {
            MeshoptDecoder.decodeVertexBuffer(vertexStream.copyOf(vertexStream.size / 2), 20, 12)
        }
        expectThrow {
            MeshoptDecoder.decodeVertexBuffer(vertexStream + byteArrayOf(0), 20, 12)
        }
        // Wrong vertex count: stream was encoded for 20 vertices.
        expectThrow { MeshoptDecoder.decodeVertexBuffer(vertexStream, 19, 12) }
    }

    @Test
    fun badIndexArgumentsThrow() {
        expectThrow { MeshoptDecoder.decodeIndexBuffer(indexStream, 10, 4) } // not /3
        expectThrow { MeshoptDecoder.decodeIndexBuffer(indexStream, 12, 3) } // bad size
        expectThrow { MeshoptDecoder.decodeIndexBuffer(indexStream.copyOf(10), 12, 4) } // truncated
        val badMagic = indexStream.copyOf()
        badMagic[0] = 0xD1.toByte() // sequence header, not index header
        expectThrow { MeshoptDecoder.decodeIndexBuffer(badMagic, 12, 4) }
        expectThrow { MeshoptDecoder.decodeIndexSequence(sequenceStream, 6, 1) }
        expectThrow { MeshoptDecoder.decodeIndexSequence(sequenceStream.copyOf(6), 6, 4) }
    }

    @Test
    fun badFilterArgumentsThrow() {
        expectThrow { MeshoptDecoder.filterOctahedral(ByteArray(16), 4, 5) }
        expectThrow { MeshoptDecoder.filterOctahedral(ByteArray(8), 4, 4) } // count*stride > size
        expectThrow { MeshoptDecoder.filterQuaternion(ByteArray(16), 2, 4) }
        expectThrow { MeshoptDecoder.filterExponential(ByteArray(6)) }
    }
}
