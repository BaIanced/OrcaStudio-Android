package app.orcaandroid.net

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Minimal MQTT 3.1.1 client over TLS: CONNECT, SUBSCRIBE and PUBLISH at QoS 0, which is all the
 * Bambu LAN protocol needs. Incoming PUBLISH payloads go to [onMessage] on a reader thread.
 */
internal class MqttClient(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val username: String,
    private val password: String,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
) {
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    @Volatile private var running = false

    val isConnected get() = running

    fun connect(timeoutMs: Int = 8_000) {
        val s = LanTls.socketFactory().createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.startHandshake()
        val o = s.getOutputStream()
        // CONNECT: protocol "MQTT" level 4, clean session + username + password, keep-alive 60 s.
        val payload = utf8(clientId) + utf8(username) + utf8(password)
        val variable = utf8("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 60)
        writePacket(o, 0x10, variable + payload)
        val input = DataInputStream(s.getInputStream())
        val (type, body) = readPacket(input)
        if (type != 0x20 || body.size < 2 || body[1].toInt() != 0)
            throw IOException("MQTT connection refused (access code?)")
        s.soTimeout = 0
        socket = s
        out = o
        running = true
        thread(name = "mqtt-reader", isDaemon = true) { readLoop(input) }
        thread(name = "mqtt-ping", isDaemon = true) {
            while (running) {
                Thread.sleep(30_000)
                runCatching { synchronized(this) { out?.let { writePacket(it, 0xC0, ByteArray(0)) } } }
            }
        }
    }

    fun subscribe(topic: String) = synchronized(this) {
        writePacket(out ?: throw IOException("Not connected"), 0x82, byteArrayOf(0, 1) + utf8(topic) + byteArrayOf(0))
    }

    fun publish(topic: String, payload: String) = synchronized(this) {
        writePacket(out ?: throw IOException("Not connected"), 0x30, utf8(topic) + payload.toByteArray())
    }

    fun close() {
        running = false
        runCatching { synchronized(this) { out?.let { writePacket(it, 0xE0, ByteArray(0)) } } }
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (running) {
                val (type, body) = readPacket(input)
                if (type and 0xF0 == 0x30) {
                    val len = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    val topic = String(body, 2, len)
                    // QoS 0: no packet identifier after the topic.
                    onMessage(topic, body.copyOfRange(2 + len, body.size))
                }
            }
        } catch (_: Exception) {
        } finally {
            running = false
        }
    }

    private fun utf8(s: String): ByteArray {
        val b = s.toByteArray()
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    private fun writePacket(o: OutputStream, header: Int, body: ByteArray) {
        val len = ArrayList<Byte>()
        var x = body.size
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            len.add(digit.toByte())
        } while (x > 0)
        o.write(byteArrayOf(header.toByte()) + len.toByteArray() + body)
        o.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = input.readUnsignedByte()
        var multiplier = 1
        var length = 0
        while (true) {
            val digit = input.readUnsignedByte()
            length += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
        }
        val body = ByteArray(length)
        input.readFully(body)
        return header to body
    }
}
