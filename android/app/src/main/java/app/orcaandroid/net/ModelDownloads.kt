package app.orcaandroid.net

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Model downloads from the model sites: the desktop's "open in slicer" links
 * (`bambustudio://open?file=<url>&name=<file>`, Downloader.cpp / Plater::import_model_id), plain
 * file downloads, and base64 files the MakerWorld page hands over. Files land in the cache and are
 * then opened like any imported model.
 */
object ModelDownloads {
    private val SLICER_LINK = Regex("^(orcaslicer|prusaslicer|bambustudio|cura)://open/?\\?file=", RegexOption.IGNORE_CASE)
    private val BAMBU_OPEN = Regex("^bambustudioopen://", RegexOption.IGNORE_CASE)
    val MODEL_EXTENSIONS = setOf("3mf", "stl", "obj", "step", "stp", "amf", "svg")

    fun isSlicerLink(link: String) = SLICER_LINK.containsMatchIn(link) || BAMBU_OPEN.containsMatchIn(link)

    /** The file URL and name in a slicer link, or null. Like import_model_id, "&name=" ends the URL. */
    fun parseSlicerLink(link: String): Pair<String, String>? {
        val m = SLICER_LINK.find(link) ?: BAMBU_OPEN.find(link) ?: return null
        var rest = link.substring(m.range.last + 1)
        if (rest.startsWith("http%3A", true) || rest.startsWith("https%3A", true)) rest = Uri.decode(rest)
        val namePos = rest.indexOf("&name=")
        val url = if (namePos >= 0) rest.substring(0, namePos) else rest
        val name = if (namePos >= 0) Uri.decode(rest.substring(namePos + 6)) else Uri.parse(url).lastPathSegment.orEmpty()
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return null
        return url to name
    }

    /** Downloads [url] with the WebView's cookies and user agent; returns the saved file. */
    fun download(context: Context, url: String, name: String, userAgent: String?): File {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        userAgent?.let { c.setRequestProperty("User-Agent", it) }
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val file = target(context, name.ifBlank { fileNameOf(c) ?: Uri.parse(url).lastPathSegment.orEmpty() })
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            return file
        } finally {
            c.disconnect()
        }
    }

    /** Saves a base64 file the page hands over (MakerWorld's generator tools). */
    fun saveBase64(context: Context, data: String, name: String): File {
        val file = target(context, name)
        file.writeBytes(Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT))
        return file
    }

    /** Model files to open: the file itself, or the model files inside a zip. */
    fun modelFiles(file: File): List<File> {
        if (!file.name.endsWith(".zip", true)) return listOf(file)
        val dir = File(file.parentFile, file.nameWithoutExtension).apply { deleteRecursively(); mkdirs() }
        val out = mutableListOf<File>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val base = e.name.substringAfterLast('/')
                if (e.isDirectory || base.substringAfterLast('.', "").lowercase() !in MODEL_EXTENSIONS || base.startsWith("._")) continue
                val f = File(dir, safeName(base))
                f.outputStream().use { zip.copyTo(it) }
                out += f
            }
        }
        return out
    }

    private fun fileNameOf(c: HttpURLConnection): String? =
        c.getHeaderField("Content-Disposition")?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }?.let(Uri::decode)

    /** A new file in the download folder; the name is reduced to a plain, unused file name. */
    private fun target(context: Context, name: String): File {
        val dir = File(context.cacheDir, "models").apply { mkdirs() }
        val clean = safeName(name).ifEmpty { "model.3mf" }
        var file = File(dir, clean)
        var i = 1
        while (file.exists()) file = File(dir, "${clean.substringBeforeLast('.')}-${i++}.${clean.substringAfterLast('.', "3mf")}")
        return file
    }

    private fun safeName(name: String) =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim('.', ' ').take(120)
}
