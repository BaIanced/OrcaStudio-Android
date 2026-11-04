package app.orcaandroid.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** One file sent to a printer: what it was, with what, and the sent file itself ([fileName] in the history folder) for a reprint. */
data class PrintHistoryEntry(
    val id: String,
    val time: Long,
    val name: String,
    val printer: String,
    val started: Boolean,
    val seconds: Double,
    val grams: Double,
    val cost: Double,
    val filaments: List<String>,
    val fileName: String,
    val sentName: String,
    val thumbnail: String?,
)

/** Local log of what was sent to a printer (newest first, [MAX] kept), in `files/history`. */
class PrintHistory(context: Context) {
    private val dir = File(context.filesDir, "history").apply { mkdirs() }
    private val index = File(dir, "history.json")

    fun load(): List<PrintHistoryEntry> = runCatching {
        JSONArray(index.readText()).let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { o ->
                    PrintHistoryEntry(
                        o.getString("id"), o.getLong("time"), o.getString("name"), o.optString("printer"), o.optBoolean("started"),
                        o.optDouble("seconds"), o.optDouble("grams"), o.optDouble("cost"),
                        o.optJSONArray("filaments")?.let { f -> (0 until f.length()).map(f::getString) }.orEmpty(),
                        o.getString("file"), o.optString("sent", o.getString("name")), o.optString("thumb").ifEmpty { null },
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun file(entry: PrintHistoryEntry) = File(dir, entry.fileName).takeIf { it.exists() }
    fun thumbnail(entry: PrintHistoryEntry) = entry.thumbnail?.let { File(dir, it) }?.takeIf { it.exists() }

    /** Adds the sent [file] (copied) as the newest entry and returns the new list. */
    fun add(entry: PrintHistoryEntry, file: File): List<PrintHistoryEntry> {
        val ext = file.name.substringAfter('.', "gcode")
        val stored = entry.copy(fileName = "${entry.id}.$ext", thumbnail = extractThumbnail(entry.id, file))
        file.copyTo(File(dir, stored.fileName), overwrite = true)
        val all = listOf(stored) + load()
        all.drop(MAX).forEach { old ->
            File(dir, old.fileName).delete()
            old.thumbnail?.let { File(dir, it).delete() }
        }
        return all.take(MAX).also(::save)
    }

    fun remove(entry: PrintHistoryEntry): List<PrintHistoryEntry> {
        File(dir, entry.fileName).delete()
        entry.thumbnail?.let { File(dir, it).delete() }
        return load().filter { it.id != entry.id }.also(::save)
    }

    /** The plate picture of a Bambu .gcode.3mf archive; null for plain G-code. */
    private fun extractThumbnail(id: String, file: File): String? = runCatching {
        if (!file.name.endsWith(".3mf")) return null
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("Metadata/plate_1.png") ?: return null
            File(dir, "$id.png").also { out -> zip.getInputStream(entry).use { i -> out.outputStream().use(i::copyTo) } }.name
        }
    }.getOrNull()

    private fun save(all: List<PrintHistoryEntry>) {
        index.writeText(JSONArray(all.map {
            JSONObject().put("id", it.id).put("time", it.time).put("name", it.name).put("printer", it.printer)
                .put("started", it.started).put("seconds", it.seconds).put("grams", it.grams).put("cost", it.cost)
                .put("filaments", JSONArray(it.filaments)).put("file", it.fileName).put("sent", it.sentName)
                .put("thumb", it.thumbnail.orEmpty())
        }).toString())
    }

    companion object { const val MAX = 20 }
}
