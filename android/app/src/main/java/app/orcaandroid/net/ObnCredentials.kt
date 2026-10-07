package app.orcaandroid.net

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.KeyStore

/**
 * The user's own Bambu slicer credentials (slicer_cert.pem, slicer_key.pem, slicer_crl.pem) used
 * by open-bamboo-networking to sign print commands. They are imported from files the user picks
 * and kept only in the app's private storage (not backed up, never part of the APK). This app
 * does not ship them and gives no instructions for obtaining them.
 */
object ObnCredentials {
    const val CERT = "slicer_cert.pem"
    const val KEY = "slicer_key.pem"
    const val CRL = "slicer_crl.pem"
    private val ALL = listOf(CERT, KEY, CRL)

    @Volatile private var app: Context? = null

    /** Called from OrcaApp.onCreate; print hosts are created without a Context. */
    fun attach(context: Context) {
        app = context.applicationContext
    }

    internal fun appContext(): Context = app ?: throw IllegalStateException("ObnCredentials.attach() not called")

    /** obn's config/log directory: obn.conf, the credentials, cached printer certificates, obn.log. */
    fun dir(context: Context): File = File(context.noBackupFilesDir, "obn").apply { mkdirs() }

    fun missing(context: Context): List<String> = ALL.filter { !File(dir(context), it).isFile }

    fun isComplete(context: Context) = missing(context).isEmpty()

    /**
     * Copies the picked PEM files into [dir]. Each file is recognised by its PEM block type, so the
     * original file names do not matter. Returns the names that were stored.
     */
    fun import(context: Context, uris: List<Uri>): List<String> {
        val stored = mutableListOf<String>()
        for (uri in uris) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Cannot read $uri")
            if (bytes.size > 64 * 1024) throw IOException("$uri is too large for a PEM file")
            val text = String(bytes, Charsets.US_ASCII)
            val name = when {
                text.contains("-----BEGIN X509 CRL-----") -> CRL
                text.contains("PRIVATE KEY-----") -> KEY
                text.contains("-----BEGIN CERTIFICATE-----") -> CERT
                else -> throw IOException("Not a PEM certificate, key or CRL: $uri")
            }
            val target = File(dir(context), name)
            val tmp = File(target.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) throw IOException("Cannot store $name")
            stored += name
        }
        writeDefaultConf(context)
        return stored
    }

    fun clear(context: Context) = ALL.forEach { File(dir(context), it).delete() }

    /**
     * obn.conf for LAN printing with verification left on (open-bamboo-networking "Option B"):
     * prints go straight to the printer over the LAN and no cloud record is written. Never
     * overwrites a file the user edited.
     */
    fun writeDefaultConf(context: Context) {
        val conf = File(dir(context), "obn.conf")
        if (conf.exists()) return
        conf.writeText(
            """
            # Written by Orca-Android. See https://github.com/ClusterM/open-bamboo-networking#configuration-file
            cloud_print = lan_only
            log_to_file = 1
            """.trimIndent() + "\n"
        )
    }

    @Volatile private var tlsReady = false

    /** Certificates written to cacert.pem by [prepareTls] (shown with cloud errors). */
    @Volatile var caCount = 0
        private set

    /**
     * CA certificates for obn's cloud HTTPS (Bambu sign-in, the account's printer list): Android's
     * CA store exported as one PEM file, which OpenSSL reads through SSL_CERT_FILE (the Android
     * obn build enables curl's CA fallback, see android/obn/obn.cmake). Done once per process,
     * before obn's first cloud request.
     */
    fun prepareTls(context: Context) {
        if (tlsReady) return
        synchronized(this) {
            if (tlsReady) return
            val pem = StringBuilder()
            var count = 0
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            for (alias in store.aliases()) {
                val cert = store.getCertificate(alias) ?: continue
                count++
                pem.append("-----BEGIN CERTIFICATE-----\n")
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).forEach { pem.append(it).append('\n') }
                pem.append("-----END CERTIFICATE-----\n")
            }
            val file = File(dir(context), "cacert.pem")
            file.writeText(pem.toString())
            Os.setenv("SSL_CERT_FILE", file.path, true)
            caCount = count
            tlsReady = true
        }
    }

    /** The last obn.log line containing [marker], to show obn's own reason for a failure. */
    fun lastLogLine(context: Context, marker: String): String? {
        val log = File(dir(context), "obn.log")
        if (!log.isFile) return null
        val tail = log.length().let { len ->
            java.io.RandomAccessFile(log, "r").use { f ->
                val from = maxOf(0L, len - 64 * 1024)
                f.seek(from)
                ByteArray((len - from).toInt()).also { f.readFully(it) }
            }
        }
        return String(tail, Charsets.UTF_8).lineSequence().lastOrNull { it.contains(marker) }?.trim()
    }
}
