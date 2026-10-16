package app.orcaandroid.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Live view of an A1/P1-style Bambu printer on the LAN, written from observing the printer's own
 * stream: TLS on port 6000 (the printer's certificate is self-signed, so it is not verified; the access
 * code is the shared secret), one 80-byte login packet, then frames of a 16-byte header (payload size
 * as a little-endian int first) followed by a JPEG.
 */
object BambuCamera {
    private const val PORT = 6000
    private const val LOGIN_USER = "bblp"

    /** Why a stream ended, for the message under the picture. */
    class CameraException(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
        enum class Kind { UNREACHABLE, REJECTED, BROKEN }
    }

    /** Frames until the collector stops; throws [CameraException] when the connection can't be made or drops. */
    fun frames(host: String, accessCode: String): Flow<Bitmap> = flow {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val socket = try {
            val raw = java.net.Socket()
            raw.connect(InetSocketAddress(host, PORT), 6000)
            (ctx.socketFactory.createSocket(raw, host, PORT, true) as javax.net.ssl.SSLSocket).also {
                it.soTimeout = 15000
                it.startHandshake()
            }
        } catch (e: java.io.IOException) {
            throw CameraException(CameraException.Kind.UNREACHABLE, e)
        }
        var first = true
        socket.use { s ->
            try {
                s.getOutputStream().apply { write(loginPacket(accessCode)); flush() }
                val input = DataInputStream(s.getInputStream().buffered())
                while (currentCoroutineContext().isActive) {
                    val header = ByteArray(16).also { input.readFully(it) }
                    val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                    if (size <= 0 || size > 8_000_000) throw CameraException(CameraException.Kind.BROKEN)
                    val data = ByteArray(size).also { input.readFully(it) }
                    val bmp = BitmapFactory.decodeByteArray(data, 0, size) ?: continue
                    first = false
                    emit(bmp)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CameraException) {
                throw e
            } catch (e: java.io.EOFException) {
                // The printer closes the connection when the access code is wrong, before any frame.
                throw CameraException(if (first) CameraException.Kind.REJECTED else CameraException.Kind.BROKEN, e)
            } catch (e: java.io.IOException) {
                throw CameraException(CameraException.Kind.BROKEN, e)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun loginPacket(code: String): ByteArray = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x40); putInt(0x3000); putInt(0); putInt(0)
        put(LOGIN_USER.toByteArray().copyOf(32))
        put(code.toByteArray().copyOf(32))
    }.array()
}
