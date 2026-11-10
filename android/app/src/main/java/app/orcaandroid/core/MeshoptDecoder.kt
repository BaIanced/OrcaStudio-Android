package app.orcaandroid.core

import kotlin.math.sqrt

/**
 * Decoder-only Kotlin port of the meshoptimizer compression codecs, as used
 * by the glTF extension EXT_meshopt_compression. Pure Kotlin/JVM, no JNI,
 * no dependencies; nothing here encodes.
 *
 * Ported from meshoptimizer (MIT License, Copyright (c) 2016-2026 Arseny
 * Kapoulkine, https://github.com/zeux/meshoptimizer), master sources read
 * 2026-11-09:
 * - vertexcodec.cpp: meshopt_decodeVertexBuffer, decodeVertexBlock,
 *   decodeBytes, decodeBytesGroup (scalar path), decodeDeltas4, unzigzag,
 *   rotate, getVertexBlockSize; stream versions 0 and 1.
 * - indexcodec.cpp: meshopt_decodeIndexBuffer, meshopt_decodeIndexSequence,
 *   decodeVByte, decodeIndex, pushVertexFifo/pushEdgeFifo; versions 0 and 1.
 * - vertexfilter.cpp: meshopt_decodeFilterOct (scalar decodeFilterOct),
 *   meshopt_decodeFilterQuat (decodeFilterQuat), meshopt_decodeFilterExp
 *   (decodeFilterExp).
 *
 * Where the C decoder returns an error code, these functions throw
 * IllegalArgumentException. Argument validation mirrors the task contract
 * (and the C asserts): bad sizes/counts throw before any decoding.
 */
object MeshoptDecoder {

    private fun fail(msg: String): Nothing = throw IllegalArgumentException("meshopt: $msg")

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    // ================= vertex buffer =================

    private const val VERTEX_HEADER = 0xA0
    private const val BLOCK_BYTES = 8192
    private const val BLOCK_MAX = 256
    private const val GROUP = 16
    private const val GROUP_LIMIT = 24 // kByteGroupDecodeLimit
    private val BITS_V0 = intArrayOf(0, 2, 4, 8)
    private val BITS_V1 = intArrayOf(0, 1, 2, 4, 8)
    private const val TAIL_MIN_V0 = 32
    private const val TAIL_MIN_V1 = 24

    fun decodeVertexBuffer(data: ByteArray, vertexCount: Int, vertexSize: Int): ByteArray {
        if (vertexSize <= 0 || vertexSize > 256 || vertexSize % 4 != 0) {
            fail("vertexSize $vertexSize (must be a multiple of 4, 4..256)")
        }
        if (vertexCount < 0) fail("vertexCount $vertexCount")
        if (vertexCount.toLong() * vertexSize > 256L * 1024 * 1024) fail("vertex buffer too large")
        if (data.isEmpty()) fail("empty stream")

        var pos = 0
        val header = data.u8(pos++)
        if (header and 0xF0 != VERTEX_HEADER) fail("bad vertex stream header 0x${header.toString(16)}")
        val version = header and 0x0F
        if (version > 1) fail("unsupported vertex stream version $version")

        val tailSize = vertexSize + if (version == 0) 0 else vertexSize / 4
        val tailMin = if (version == 0) TAIL_MIN_V0 else TAIL_MIN_V1
        val tailPad = maxOf(tailSize, tailMin)
        if (data.size - pos < tailPad) fail("stream too small for its tail")

        // The tail holds the FIRST vertex (the delta seed) and, for v1, the
        // per-group channel descriptors.
        val tailStart = data.size - tailSize
        val lastVertex = ByteArray(256)
        data.copyInto(lastVertex, 0, tailStart, tailStart + vertexSize)
        val channels: ByteArray? = if (version == 0) null else
            data.copyOfRange(tailStart + vertexSize, tailStart + vertexSize + vertexSize / 4)

        val blockSize = getVertexBlockSize(vertexSize)
        val out = ByteArray(vertexCount * vertexSize)
        var offset = 0
        while (offset < vertexCount) {
            val n = if (offset + blockSize < vertexCount) blockSize else vertexCount - offset
            pos = decodeVertexBlock(data, pos, out, offset * vertexSize, n, vertexSize,
                lastVertex, channels, version)
            offset += n
        }
        if (data.size - pos != tailPad) fail("stream has trailing garbage")
        return out
    }

    private fun getVertexBlockSize(vertexSize: Int): Int {
        val result = (BLOCK_BYTES / vertexSize) and (GROUP - 1).inv()
        return if (result < BLOCK_MAX) result else BLOCK_MAX
    }

    private fun decodeVertexBlock(
        data: ByteArray, startPos: Int, out: ByteArray, outOffset: Int,
        vertexCount: Int, vertexSize: Int, lastVertex: ByteArray,
        channels: ByteArray?, version: Int,
    ): Int {
        var pos = startPos
        val aligned = (vertexCount + GROUP - 1) and (GROUP - 1).inv()
        val transposed = ByteArray(BLOCK_BYTES)
        val controlSize = if (version == 0) 0 else vertexSize / 4
        if (data.size - pos < controlSize) fail("vertex block truncated (control)")
        val controlPos = pos
        pos += controlSize

        for (k in 0 until vertexSize step 4) {
            val ctrlByte = if (version == 0) 0 else data.u8(controlPos + k / 4)
            // Byte planes for this 4-byte group: plane j covers byte j of
            // every vertex, vertexCount bytes each. The scratch is sized
            // like the C buffer (kVertexBlockMaxSize * 4): decodeBytes
            // writes vertexCountAligned bytes per plane, which can spill
            // past 4 * vertexCount for a partial block; the next plane's
            // decode overwrites the spill, exactly as in C.
            val planes = ByteArray(BLOCK_MAX * 4)
            for (j in 0 until 4) {
                val ctrl = (ctrlByte ushr (j * 2)) and 3
                val planeOff = j * vertexCount
                when {
                    ctrl == 3 -> { // literal
                        if (data.size - pos < vertexCount) fail("vertex block truncated (literal)")
                        data.copyInto(planes, planeOff, pos, pos + vertexCount)
                        pos += vertexCount
                    }
                    ctrl == 2 -> Unit // zero encoding: planes are already 0
                    else -> {
                        val table = if (version == 0) BITS_V0
                            else IntArray(4) { BITS_V1[it + ctrl] }
                        pos = decodeBytes(data, pos, planes, planeOff, aligned, table)
                    }
                }
            }
            val channel = if (version == 0) 0 else channels!!.u8(k / 4)
            when (channel and 3) {
                0 -> decodeDeltas(planes, 1, false, 0, transposed, k, vertexCount, vertexSize, lastVertex, k)
                1 -> decodeDeltas(planes, 2, false, 0, transposed, k, vertexCount, vertexSize, lastVertex, k)
                2 -> decodeDeltas(planes, 4, true, (32 - (channel ushr 4)) and 31,
                    transposed, k, vertexCount, vertexSize, lastVertex, k)
                else -> fail("invalid channel type ${channel and 3}")
            }
        }
        transposed.copyInto(out, outOffset, 0, vertexCount * vertexSize)
        // The next block's delta seed is this block's last vertex.
        val lastOff = (vertexCount - 1) * vertexSize
        transposed.copyInto(lastVertex, 0, lastOff, lastOff + vertexSize)
        return pos
    }

    /** Decodes one byte-group stream (meshopt decodeBytes). Returns new pos. */
    private fun decodeBytes(
        data: ByteArray, startPos: Int, out: ByteArray, outOff: Int,
        size: Int, bitsTable: IntArray,
    ): Int {
        var pos = startPos
        val headerSize = (size / GROUP + 3) / 4
        if (data.size - pos < headerSize) fail("byte stream truncated (header)")
        val headerPos = pos
        pos += headerSize
        var i = 0
        while (i < size) {
            if (data.size - pos < GROUP_LIMIT) fail("byte stream truncated (group)")
            val g = i / GROUP
            val bitsk = (data.u8(headerPos + g / 4) ushr ((g % 4) * 2)) and 3
            pos = decodeBytesGroup(data, pos, out, outOff + i, bitsTable[bitsk])
            i += GROUP
        }
        return pos
    }

    /** Decodes 16 bytes (meshopt decodeBytesGroup, scalar path). Returns new pos. */
    private fun decodeBytesGroup(data: ByteArray, startPos: Int, out: ByteArray, outOff: Int, bits: Int): Int {
        var pos = startPos
        when (bits) {
            0 -> {
                out.fill(0, outOff, outOff + GROUP)
                return pos
            }
            8 -> {
                data.copyInto(out, outOff, pos, pos + GROUP)
                return pos + GROUP
            }
            1, 2, 4 -> {
                val groupBytes = when (bits) { 1 -> 2; 2 -> 4; else -> 8 }
                var varPos = pos + groupBytes
                val sentinel = (1 shl bits) - 1
                var outIdx = outOff
                for (g in 0 until groupBytes) {
                    var byte = data.u8(pos++)
                    if (bits == 1) byte = reverseBits8(byte)
                    val perByte = 8 / bits
                    for (s in 0 until perByte) {
                        val enc = byte ushr (8 - bits)
                        byte = (byte shl bits) and 0xFF
                        if (enc == sentinel) {
                            if (varPos >= data.size) fail("byte stream overrun (sentinel data)")
                            out[outIdx++] = data[varPos++]
                        } else {
                            out[outIdx++] = enc.toByte()
                        }
                    }
                }
                return varPos
            }
            else -> fail("unexpected bit width $bits")
        }
    }

    private fun reverseBits8(b: Int): Int {
        var v = b
        var r = 0
        for (i in 0 until 8) {
            r = (r shl 1) or (v and 1)
            v = v ushr 1
        }
        return r
    }

    /**
     * meshopt decodeDeltas4: recombines the byte planes of one 4-byte group
     * into [sizeT]-byte values, un-deltas them against the running previous
     * value (seeded from the last vertex), and writes them interleaved into
     * the transposed block output.
     */
    private fun decodeDeltas(
        planes: ByteArray, sizeT: Int, xor: Boolean, rot: Int,
        transposed: ByteArray, groupOff: Int,
        vertexCount: Int, vertexSize: Int, lastVertex: ByteArray, lastOff: Int,
    ) {
        val mask = if (sizeT == 1) 0xFF else if (sizeT == 2) 0xFFFF else -1
        var kk = 0
        while (kk < 4) {
            var p = 0
            for (j in 0 until sizeT) p = p or (lastVertex.u8(lastOff + kk + j) shl (8 * j))
            var vertexOffset = kk
            for (i in 0 until vertexCount) {
                var v = 0
                for (j in 0 until sizeT) {
                    v = v or (planes.u8((kk + j) * vertexCount + i) shl (8 * j))
                }
                v = if (xor) {
                    v.rotateLeft(rot) xor p
                } else {
                    val un = ((if (v and 1 != 0) mask else 0) xor (v ushr 1)) and mask
                    (un + p) and mask
                }
                for (j in 0 until sizeT) {
                    transposed[groupOff + vertexOffset + j] = (v ushr (8 * j)).toByte()
                }
                p = v
                vertexOffset += vertexSize
            }
            kk += sizeT
        }
    }

    // ================= index buffer / sequence =================

    private const val INDEX_HEADER = 0xE0
    private const val SEQUENCE_HEADER = 0xD0

    /** Bounds-checked VByte cursor (meshopt decodeVByte / decodeIndex). */
    private class VByteCursor(val data: ByteArray, var pos: Int) {
        fun decodeVByte(): Int {
            if (pos >= data.size) fail("vbyte overrun")
            val lead = data.u8(pos++)
            if (lead < 128) return lead
            var result = lead and 127
            var shift = 7
            for (i in 0 until 4) {
                if (pos >= data.size) fail("vbyte overrun")
                val group = data.u8(pos++)
                result = result or ((group and 127) shl shift)
                shift += 7
                if (group < 128) break
            }
            return result
        }

        fun decodeIndex(last: Int): Int {
            val v = decodeVByte()
            val d = (v ushr 1) xor -(v and 1)
            return last + d
        }
    }

    fun decodeIndexBuffer(data: ByteArray, indexCount: Int, indexSize: Int): IntArray {
        if (indexSize != 2 && indexSize != 4) fail("indexSize $indexSize (must be 2 or 4)")
        if (indexCount < 0 || indexCount % 3 != 0) fail("indexCount $indexCount (must be a multiple of 3)")
        if (data.size < 1 + indexCount / 3 + 16) fail("index stream too small")
        if (data.u8(0) and 0xF0 != INDEX_HEADER) fail("bad index stream header")
        val version = data.u8(0) and 0x0F
        if (version > 1) fail("unsupported index stream version $version")

        val edgeFifo = IntArray(32) { -1 }
        val vertexFifo = IntArray(16) { -1 }
        var edgeOff = 0
        var vertexOff = 0
        var next = 0
        var last = 0
        val fecmax = if (version >= 1) 13 else 15

        val codeEnd = 1 + indexCount / 3
        val dataSafeEnd = data.size - 16
        val tablePos = dataSafeEnd
        val cur = VByteCursor(data, codeEnd)
        val out = IntArray(indexCount)
        var outPos = 0

        fun pushVertex(v: Int, cond: Int = 1) {
            vertexFifo[vertexOff] = v
            vertexOff = (vertexOff + cond) and 15
        }
        fun pushEdge(a: Int, b: Int) {
            edgeFifo[edgeOff * 2] = a
            edgeFifo[edgeOff * 2 + 1] = b
            edgeOff = (edgeOff + 1) and 15
        }
        fun writeTri(a: Int, b: Int, c: Int) {
            if (indexSize == 2) {
                out[outPos++] = a and 0xFFFF
                out[outPos++] = b and 0xFFFF
                out[outPos++] = c and 0xFFFF
            } else {
                out[outPos++] = a
                out[outPos++] = b
                out[outPos++] = c
            }
        }

        var codePos = 1
        while (codePos < codeEnd) {
            val codetri = data.u8(codePos++)
            if (codetri < 0xF0) {
                val fe = codetri ushr 4
                val a = edgeFifo[((edgeOff - 1 - fe) and 15) * 2]
                val b = edgeFifo[((edgeOff - 1 - fe) and 15) * 2 + 1]
                var c: Int
                val fec = codetri and 15
                if (fec < fecmax) {
                    val cf = vertexFifo[(vertexOff - 1 - fec) and 15]
                    c = if (fec == 0) next else cf
                    val fec0 = if (fec == 0) 1 else 0
                    next += fec0
                    pushVertex(c, fec0)
                } else {
                    if (cur.pos > dataSafeEnd) fail("index stream data overrun")
                    // fec 13/14 encode last-1 / last+1; fec 15 is a free index.
                    // Either way `last` advances to the new index (C: last = c = ...).
                    c = if (fec != 15) {
                        last += fec * 2 - 27
                        last
                    } else {
                        last = cur.decodeIndex(last)
                        last
                    }
                    pushVertex(c)
                }
                pushEdge(c, b)
                pushEdge(a, c)
                writeTri(a, b, c)
            } else if (codetri < 0xFE) {
                val codeaux = data.u8(tablePos + (codetri and 15))
                val feb = codeaux ushr 4
                val fec = codeaux and 15
                val a = next++
                val bf = vertexFifo[(vertexOff - feb) and 15]
                val b = if (feb == 0) next else bf
                val feb0 = if (feb == 0) 1 else 0
                next += feb0
                val cf = vertexFifo[(vertexOff - fec) and 15]
                val c = if (fec == 0) next else cf
                val fec0 = if (fec == 0) 1 else 0
                next += fec0
                writeTri(a, b, c)
                pushVertex(a)
                pushVertex(b, feb0)
                pushVertex(c, fec0)
                pushEdge(b, a)
                pushEdge(c, b)
                pushEdge(a, c)
            } else {
                if (cur.pos > dataSafeEnd) fail("index stream data overrun")
                val codeaux = curByte(cur)
                val fea = if (codetri == 0xFE) 0 else 15
                val feb = codeaux ushr 4
                val fec = codeaux and 15
                if (codeaux == 0) next = 0
                var a = if (fea == 0) next++ else 0
                var b = if (feb == 0) next++ else vertexFifo[(vertexOff - feb) and 15]
                var c = if (fec == 0) next++ else vertexFifo[(vertexOff - fec) and 15]
                if (fea == 15) {
                    last = cur.decodeIndex(last); a = last
                }
                if (feb == 15) {
                    last = cur.decodeIndex(last); b = last
                }
                if (fec == 15) {
                    last = cur.decodeIndex(last); c = last
                }
                writeTri(a, b, c)
                pushVertex(a)
                pushVertex(b, if (feb == 0 || feb == 15) 1 else 0)
                pushVertex(c, if (fec == 0 || fec == 15) 1 else 0)
                pushEdge(b, a)
                pushEdge(c, b)
                pushEdge(a, c)
            }
        }
        if (cur.pos != dataSafeEnd) fail("index stream not consumed exactly")
        return out
    }

    private fun curByte(cur: VByteCursor): Int {
        if (cur.pos >= cur.data.size) fail("index stream data overrun")
        return cur.data.u8(cur.pos++)
    }

    fun decodeIndexSequence(data: ByteArray, indexCount: Int, indexSize: Int): IntArray {
        if (indexSize != 2 && indexSize != 4) fail("indexSize $indexSize (must be 2 or 4)")
        if (indexCount < 0) fail("indexCount $indexCount")
        if (data.size < 1 + indexCount + 4) fail("sequence stream too small")
        if (data.u8(0) and 0xF0 != SEQUENCE_HEADER) fail("bad sequence stream header")
        val version = data.u8(0) and 0x0F
        if (version > 1) fail("unsupported sequence stream version $version")

        val dataSafeEnd = data.size - 4
        val cur = VByteCursor(data, 1)
        val last = IntArray(2)
        val out = IntArray(indexCount)
        for (i in 0 until indexCount) {
            if (cur.pos >= dataSafeEnd) fail("sequence stream data overrun")
            var v = cur.decodeVByte()
            val current = v and 1
            v = v ushr 1
            val d = (v ushr 1) xor -(v and 1)
            val index = last[current] + d
            last[current] = index
            out[i] = if (indexSize == 2) index and 0xFFFF else index
        }
        if (cur.pos != dataSafeEnd) fail("sequence stream not consumed exactly")
        return out
    }

    // ================= vertex filters =================

    private fun getShort(data: ByteArray, byteIndex: Int): Int {
        val v = (data[byteIndex].toInt() and 0xFF) or (data[byteIndex + 1].toInt() shl 8)
        return v.toShort().toInt()
    }

    private fun putShort(data: ByteArray, byteIndex: Int, v: Int) {
        data[byteIndex] = (v and 0xFF).toByte()
        data[byteIndex + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun getInt(data: ByteArray, byteIndex: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((data[byteIndex + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun putInt(data: ByteArray, byteIndex: Int, v: Int) {
        for (i in 0 until 4) data[byteIndex + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun sqrtf(x: Float): Float = sqrt(x.toDouble()).toFloat()

    /** meshopt_decodeFilterOct: in place; stride 4 = snorm8, stride 8 = snorm16. */
    fun filterOctahedral(data: ByteArray, count: Int, stride: Int) {
        if (stride != 4 && stride != 8) fail("octahedral stride $stride (must be 4 or 8)")
        if (count < 0 || count.toLong() * stride > data.size) fail("octahedral count $count exceeds buffer")
        val max = if (stride == 4) 127f else 32767f
        fun comp(i: Int, j: Int): Float = if (stride == 4) {
            data[i * 4 + j].toFloat() // signed byte, as in C (signed char)
        } else {
            getShort(data, i * 8 + j * 2).toFloat()
        }
        fun store(i: Int, j: Int, v: Int) {
            if (stride == 4) data[i * 4 + j] = v.toByte() else putShort(data, i * 8 + j * 2, v)
        }
        for (i in 0 until count) {
            var x = comp(i, 0)
            var y = comp(i, 1)
            var z = comp(i, 2) - kotlin.math.abs(x) - kotlin.math.abs(y)
            val t = if (z >= 0f) 0f else z
            x += if (x >= 0f) t else -t
            y += if (y >= 0f) t else -t
            val l = sqrtf(x * x + y * y + z * z)
            val s = max / l
            store(i, 0, (x * s + if (x >= 0f) 0.5f else -0.5f).toInt())
            store(i, 1, (y * s + if (y >= 0f) 0.5f else -0.5f).toInt())
            store(i, 2, (z * s + if (z >= 0f) 0.5f else -0.5f).toInt())
            // component 3 (w) is preserved
        }
    }

    /** meshopt_decodeFilterQuat: in place; stride must be 8 (snorm16 x4). */
    fun filterQuaternion(data: ByteArray, count: Int, stride: Int) {
        if (stride != 8) fail("quaternion stride $stride (must be 8)")
        if (count < 0 || count.toLong() * stride > data.size) fail("quaternion count $count exceeds buffer")
        val scale = 32767f / sqrtf(2f)
        for (i in 0 until count) {
            val base = i * 8
            val d0 = getShort(data, base)
            val d1 = getShort(data, base + 2)
            val d2 = getShort(data, base + 4)
            val d3 = getShort(data, base + 6)
            val sf = d3 or 3
            val s = sf.toFloat()
            val x = d0.toFloat()
            val y = d1.toFloat()
            val z = d2.toFloat()
            val ws = s * s
            val ww = ws * 2f - x * x - y * y - z * z
            val w = sqrtf(if (ww >= 0f) ww else 0f)
            val ss = scale / s
            val xf = (x * ss + if (x >= 0f) 0.5f else -0.5f).toInt()
            val yf = (y * ss + if (y >= 0f) 0.5f else -0.5f).toInt()
            val zf = (z * ss + if (z >= 0f) 0.5f else -0.5f).toInt()
            val wf = (w * ss + 0.5f).toInt()
            val qc = d3 and 3
            putShort(data, base + ((qc + 1) and 3) * 2, xf)
            putShort(data, base + ((qc + 2) and 3) * 2, yf)
            putShort(data, base + ((qc + 3) and 3) * 2, zf)
            putShort(data, base + qc * 2, wf)
        }
    }

    /** meshopt_decodeFilterExp: in place; one 32-bit word per value. */
    fun filterExponential(data: ByteArray) {
        if (data.size % 4 != 0) fail("exponential data size ${data.size} not a multiple of 4")
        var i = 0
        while (i < data.size) {
            val v = getInt(data, i)
            val m = (v shl 8) shr 8 // sign-extended 24-bit mantissa
            val e = v shr 24 // sign-extended exponent byte
            val bits = (e + 127) shl 23
            val f = Float.fromBits(bits) * m.toFloat()
            putInt(data, i, f.toBits())
            i += 4
        }
    }
}
