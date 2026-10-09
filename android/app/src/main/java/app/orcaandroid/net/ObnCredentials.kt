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
     * prints go straight to the printer over the LAN and no cloud record is written.
     *
     * mqtt_keep_connection = 0: every printer action here connects and disconnects (PrintHost per
     * action). With obn's default (1) the disconnect is deferred and later drops the session
     * without clearing obn's once-per-session "app certificate installed" latch (agent.cpp
     * schedule_deferred_disconnect), so the next connection skips the certificate exchange and
     * [ObnNative.installCert] never sees "device_cert_installed". An immediate disconnect clears
     * the latch (agent.cpp disconnect_printer).
     *
     * Never overwrites a setting the user edited: an existing file only gets the keys it lacks.
     */
    fun writeDefaultConf(context: Context) {
        val conf = File(dir(context), "obn.conf")
        if (!conf.exists()) {
            conf.writeText(
                """
                # Written by Orca-Android. See https://github.com/ClusterM/open-bamboo-networking#configuration-file
                cloud_print = lan_only
                log_to_file = 1
                mqtt_keep_connection = 0
                """.trimIndent() + "\n"
            )
            return
        }
        val text = conf.readText()
        if (Regex("""(?m)^\s*mqtt_keep_connection\s*=""").containsMatchIn(text)) return
        conf.appendText((if (text.isEmpty() || text.endsWith("\n")) "" else "\n") + "mqtt_keep_connection = 0\n")
    }

    /**
     * The user's opt-in for Bambu's cloud, kept in obn.conf: [cloud] turns block_cloud off (printer
     * reports, prompts and commands through the account when the LAN does not reach the printer),
     * [cloudPrint] sets cloud_print = try_lan_first (print over the LAN when possible, else through
     * Bambu's cloud; obn patch 0002). Both are off unless the user turns them on.
     */
    data class CloudSettings(val cloud: Boolean, val cloudPrint: Boolean)

    fun cloudSettings(context: Context): CloudSettings {
        writeDefaultConf(context)
        val text = File(dir(context), "obn.conf").readText()
        fun value(key: String) = Regex("""(?m)^\s*$key\s*=\s*(\S+)""").find(text)?.groupValues?.get(1)?.lowercase()
        // obn's defaults: block_cloud on; the app writes cloud_print = lan_only.
        val cloud = value("block_cloud") in setOf("0", "false", "no")
        return CloudSettings(cloud, cloud && value("cloud_print") in setOf("try_lan_first", "cloud_only"))
    }

    /** Writes the cloud settings to obn.conf and has a running obn re-read it. */
    fun setCloudSettings(context: Context, settings: CloudSettings) {
        writeDefaultConf(context)
        val conf = File(dir(context), "obn.conf")
        var text = conf.readText()
        text = setKey(text, "block_cloud", if (settings.cloud) "0" else "1")
        text = setKey(text, "cloud_print", if (settings.cloud && settings.cloudPrint) "try_lan_first" else "lan_only")
        conf.writeText(text)
        ObnNative.reloadConfig()
    }

    private fun setKey(text: String, key: String, value: String): String {
        val line = Regex("""(?m)^\s*$key\s*=.*$""")
        return if (line.containsMatchIn(text)) line.replace(text) { "$key = $value" }
        else text + (if (text.isEmpty() || text.endsWith("\n")) "" else "\n") + "$key = $value\n"
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

    /**
     * Starts obn (once per process) with its config, the CA bundle for cloud HTTPS and Bambu's
     * printer CA. Returns obn's version.
     */
    fun startAgent(context: Context): String {
        writeDefaultConf(context)
        prepareTls(context)
        installPrinterCa(context)
        return ObnNative.init(dir(context).path).ifEmpty { throw IOException("open-bamboo-networking failed to start") }
    }

    @Volatile private var printerCaReady = false

    /**
     * Bambu's printer CA (OrcaSlicer resources/cert/printer.cer, packed as assets/obn/printer.cer)
     * in [dir], which the JNI bridge hands to obn as its cert folder (bambu_network_set_cert_file,
     * as the desktop slicers pass resources/cert). Without it obn refuses LAN MQTT while TLS
     * verification is on: "LanSession: TLS verify enabled but printer.cer missing" (obn -2).
     */
    private fun installPrinterCa(context: Context) {
        if (printerCaReady) return
        synchronized(this) {
            if (printerCaReady) return
            val bytes = context.assets.open("obn/printer.cer").use { it.readBytes() }
            val target = File(dir(context), "printer.cer")
            if (!target.isFile || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
            printerCaReady = true
        }
    }

    /** The last obn.log line containing one of [markers], to show obn's own reason for a failure. */
    fun lastLogLine(context: Context, vararg markers: String): String? {
        val log = File(dir(context), "obn.log")
        if (!log.isFile) return null
        val tail = log.length().let { len ->
            java.io.RandomAccessFile(log, "r").use { f ->
                val from = maxOf(0L, len - 64 * 1024)
                f.seek(from)
                ByteArray((len - from).toInt()).also { f.readFully(it) }
            }
        }
        return String(tail, Charsets.UTF_8).lineSequence().lastOrNull { line -> markers.any { line.contains(it) } }?.trim()
    }
}
