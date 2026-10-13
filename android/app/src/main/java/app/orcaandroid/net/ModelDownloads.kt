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
    val MODEL_EXTENSIONS = setOf("3mf", "stl", "obj", "step", "stp", "amf", "svg", "scad")
    /** Slicer preset files (filament profiles from 3D Filament Profiles and the like), imported as presets. */
    private val PRESET_EXTENSIONS = setOf("json", "orca_filament", "orca_bundle", "orca_printer")

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
            // The first name with a model or zip extension: the page's, the server's, the URL's.
            // A guessed name (WebView makes "x.bin" of application/octet-stream) gets one from the content.
            val candidates = listOfNotNull(name.ifBlank { null }, fileNameOf(c), Uri.parse(url).lastPathSegment)
            val known = candidates.firstOrNull { hasKnownExtension(it) }
            val file = target(context, known ?: candidates.firstOrNull().orEmpty())
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            if (known != null) return file
            val sniffed = File(file.parentFile, file.nameWithoutExtension + "." + sniffExtension(file))
            return if (!sniffed.exists() && file.renameTo(sniffed)) sniffed else file
        } finally {
            c.disconnect()
        }
    }

    /** Saves a base64 file the page hands over (MakerWorld's generator tools). */
    fun saveBase64(context: Context, data: String, name: String): File {
        val file = target(context, name)
        file.writeBytes(Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT))
        if (hasKnownExtension(name)) return file
        val sniffed = File(file.parentFile, file.nameWithoutExtension + "." + sniffExtension(file))
        return if (!sniffed.exists() && file.renameTo(sniffed)) sniffed else file
    }

    /** A preset file or a zip of presets (JSON files, no models), for the preset import. */
    fun isPresetFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in PRESET_EXTENSIONS) return true
        if (ext != "zip") return false
        return runCatching {
            java.util.zip.ZipFile(file).use { z ->
                val names = z.entries().asSequence().filter { !it.isDirectory }.map { it.name.substringAfterLast('.', "").lowercase() }.toList()
                names.any { it == "json" } && names.none { it in MODEL_EXTENSIONS }
            }
        }.getOrDefault(false)
    }

    /**
     * Model files to open: the file itself, or the model files inside a zip. A zip with OpenSCAD
     * sources is unpacked whole with its folders, so include <lib/x.scad> and import("part.stl")
     * still resolve; model files a source names stay out of the list (they are the design's data).
     */
    fun modelFiles(file: File): List<File> {
        if (!file.name.endsWith(".zip", true)) return listOf(file)
        // Not the plain name: the import folder also holds folders of its own ("openscad", "presets").
        val dir = File(file.parentFile, file.nameWithoutExtension + "_files").apply { deleteRecursively(); mkdirs() }
        val root = dir.canonicalPath + File.separator
        val hasScad = java.util.zip.ZipFile(file).use { z -> z.entries().asSequence().any { it.name.endsWith(".scad", true) } }
        val out = mutableListOf<File>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val base = e.name.substringAfterLast('/')
                if (e.isDirectory || base.startsWith("._") || e.name.startsWith("__MACOSX/")) continue
                val model = base.substringAfterLast('.', "").lowercase() in MODEL_EXTENSIONS
                if (!model && !hasScad) continue
                val f = if (hasScad) File(dir, e.name).takeIf { it.canonicalPath.startsWith(root) } ?: continue
                    else File(dir, safeName(base))
                f.parentFile?.mkdirs()
                f.outputStream().use { zip.copyTo(it) }
                if (model) out += f
            }
        }
        if (!hasScad) return out
        // Sources nearest the top first: the design is usually there, its libraries in folders.
        val sources = out.filter { it.extension.equals("scad", true) }.sortedBy { it.relativeTo(dir).path.count { c -> c == '/' } }
        val texts = sources.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        return sources + out.filter { !it.extension.equals("scad", true) && !texts.contains(it.name) }
    }

    private fun hasKnownExtension(name: String) = name.substringAfterLast('.', "").lowercase().let { it in MODEL_EXTENSIONS || it in PRESET_EXTENSIONS || it == "zip" }

    private val SCAD_TEXT = Regex("""\b(cube|cylinder|sphere|polygon|polyhedron|difference|union|intersection|translate|linear_extrude|rotate_extrude)\s*\(|\b(module|function)\s+\w+\s*\(|\b(include|use)\s*<|\${'$'}fn\s*=""")

    /** The model type of a downloaded file from its content: 3MF / zip, ASCII or binary STL, OBJ, OpenSCAD. */
    private fun sniffExtension(file: File): String {
        val head = file.inputStream().use { input -> ByteArray(512).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        val text = String(head, Charsets.ISO_8859_1)
        return when {
            text.startsWith("PK") -> if (runCatching { java.util.zip.ZipFile(file).use { it.getEntry("3D/3dmodel.model") != null } }.getOrDefault(false)) "3mf" else "zip"
            text.trimStart().startsWith("{") -> "json"
            text.trimStart().startsWith("solid") && text.contains("facet") -> "stl"
            Regex("(?m)^\\s*(v|vn|vt|f|o|g|mtllib) ").containsMatchIn(text) -> "obj"
            // Text with OpenSCAD statements (a binary STL has zero bytes in its first 512).
            '\u0000' !in text && SCAD_TEXT.containsMatchIn(text) -> "scad"
            else -> "stl" // binary STL: 80-byte header, triangle count, 50 bytes per triangle
        }
    }

    private fun fileNameOf(c: HttpURLConnection): String? =
        c.getHeaderField("Content-Disposition")?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }?.let(Uri::decode)

    /** A new file in the download folder; the name is reduced to a plain, unused file name. */
    private fun target(context: Context, name: String): File {
        // Not "models": the import copies files there by name, and a copy onto itself truncates the file.
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val clean = safeName(name).ifEmpty { "model.3mf" }
        var file = File(dir, clean)
        var i = 1
        while (file.exists()) file = File(dir, "${clean.substringBeforeLast('.')}-${i++}.${clean.substringAfterLast('.', "3mf")}")
        return file
    }

    private fun safeName(name: String) =
        name.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").trim('.', ' ').take(120)
}
