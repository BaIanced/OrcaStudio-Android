package app.orcaandroid.net

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * FTP over implicit TLS (port 990) as used by Bambu printers for uploads.
 *
 * Their vsftpd requires the data channel to resume the control channel's TLS session. Android's
 * TLS stack caches sessions by host and port, so the data socket is layered with the control
 * connection's host:port, which makes it offer the cached session. Some firmware rejects
 * protected data channels anyway; [upload] then retries with a clear data channel (PROT C).
 */
internal class FtpsClient(private val host: String, private val user: String, private val password: String) {

    fun upload(file: File, remoteName: String, onProgress: (Float) -> Unit) {
        try {
            session { it.store(file, remoteName, protected = true, onProgress) }
        } catch (e: IOException) {
            session { it.store(file, remoteName, protected = false, onProgress) }
        }
    }

    private fun session(block: (Session) -> Unit) {
        val factory = LanTls.socketFactory()
        val control = factory.createSocket() as SSLSocket
        control.connect(InetSocketAddress(host, PORT), 8_000)
        control.soTimeout = 30_000
        control.startHandshake()
        Session(factory, control).use(block)
    }

    private inner class Session(private val factory: SSLSocketFactory, private val control: SSLSocket) : AutoCloseable {
        private val reader = BufferedReader(InputStreamReader(control.inputStream))
        private val writer: OutputStream = control.outputStream

        init {
            expect(220)
            command("USER $user", 331)
            command("PASS $password", 230)
            command("PBSZ 0", 200)
            command("TYPE I", 200)
        }

        fun store(file: File, remoteName: String, protected: Boolean, onProgress: (Float) -> Unit) {
            command(if (protected) "PROT P" else "PROT C", 200)
            val (dataHost, dataPort) = passive()
            val plain = Socket()
            plain.connect(InetSocketAddress(dataHost, dataPort), 8_000)
            plain.soTimeout = 60_000
            send("STOR $remoteName")
            val data: Socket = if (protected) {
                (factory.createSocket(plain, host, PORT, true) as SSLSocket).apply { useClientMode = true; startHandshake() }
            } else {
                plain
            }
            expectOneOf(125, 150)
            data.getOutputStream().use { out -> Http.copyWithProgress(file, out, onProgress) }
            data.close()
            expect(226)
        }

        /** PASV; the printer's reported address is replaced by the one we connected to. */
        private fun passive(): Pair<String, Int> {
            val reply = command("PASV", 227)
            val nums = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(reply)?.groupValues?.drop(1)?.map { it.toInt() }
                ?: throw IOException("Bad PASV reply: $reply")
            return host to (nums[4] * 256 + nums[5])
        }

        private fun send(line: String) {
            writer.write("$line\r\n".toByteArray())
            writer.flush()
        }

        private fun command(line: String, code: Int): String {
            send(line)
            return expect(code)
        }

        private fun readReply(): Pair<Int, String> {
            var line = reader.readLine() ?: throw IOException("FTP connection closed")
            val code = line.take(3).toIntOrNull() ?: throw IOException("Bad FTP reply: $line")
            // Multi-line replies end with "<code> ".
            if (line.length > 3 && line[3] == '-') {
                while (true) {
                    line = reader.readLine() ?: throw IOException("FTP connection closed")
                    if (line.startsWith("$code ")) break
                }
            }
            return code to line
        }

        private fun expect(code: Int): String = expectOneOf(code)

        private fun expectOneOf(vararg codes: Int): String {
            val (code, line) = readReply()
            if (code !in codes) throw IOException(if (code == 530) "Login refused – check the access code" else "FTP: $line")
            return line
        }

        override fun close() {
            runCatching { send("QUIT") }
            runCatching { control.close() }
        }
    }

    private companion object {
        const val PORT = 990
    }
}
