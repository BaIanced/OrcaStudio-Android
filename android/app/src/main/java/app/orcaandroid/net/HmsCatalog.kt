package app.orcaandroid.net

import android.content.Context
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Texts and prompt buttons of Bambu printer messages, per printer model (the first three characters
 * of the serial), loaded as the desktop's HMSQuery does (HMS.cpp download_hms_related): the files
 * hms_<lang>_<model>.json from https://e.bambulab.com/query.php and hms_action_<model>.json from
 * /hms/GetActionImage.php. They are cached in the app's files and refreshed weekly. The requests
 * carry only the model and the language.
 */
internal object HmsCatalog {
    private const val HOST = "https://e.bambulab.com"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val RETRY_MS = 10L * 60 * 1000
    private val loaded = HashMap<String, JSONObject>()
    private val failedAt = HashMap<String, Long>()

    /** [status] with the texts (and, for a print error, the prompt buttons) of its alerts filled in. */
    fun describe(context: Context, serial: String, status: PrinterStatus): PrinterStatus =
        if (status.alerts.isEmpty()) status
        else status.copy(alerts = status.alerts.map { a ->
            a.copy(text = text(context, serial, a), buttons = if (a.isPrintError) buttons(context, serial, a) else emptyList())
        })

    /** What the printer shows for [alert], or null when the catalog has no text for it. */
    fun text(context: Context, serial: String, alert: PrinterAlert): String? {
        val model = serial.take(3).uppercase(Locale.ROOT)
        if (model.length < 3) return null
        val lang = language()
        val section = if (alert.isPrintError) "device_error" else "device_hms"
        for (l in listOf(lang, "en").distinct()) {
            val data = file(context, "hms_${l}_$model.json", "$HOST/query.php?lang=$l&d=$model")?.optJSONObject("data") ?: continue
            val byLang = data.optJSONObject(section) ?: continue
            val entries = byLang.optJSONArray(l) ?: byLang.optJSONArray("en") ?: continue
            find(entries, alert.code)?.optString("intro")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** The desktop's prompt buttons for [alert] (DeviceErrorDialog::ActionButton ids), in its order. */
    fun buttons(context: Context, serial: String, alert: PrinterAlert): List<Int> {
        val model = serial.take(3).uppercase(Locale.ROOT)
        if (model.length < 3) return emptyList()
        val data = file(context, "hms_action_$model.json", "$HOST/hms/GetActionImage.php?d=$model")?.optJSONArray("data") ?: return emptyList()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (!item.optString("ecode").equals(alert.code, ignoreCase = true)) continue
            val device = item.optString("device")
            if (!device.equals(model, ignoreCase = true) && device != "default") continue
            val actions = item.optJSONArray("actions") ?: return emptyList()
            return List(actions.length()) { actions.optInt(it) }
        }
        return emptyList()
    }

    private fun find(entries: JSONArray, code: String): JSONObject? {
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            if (e.optString("ecode").equals(code, ignoreCase = true)) return e
        }
        return null
    }

    /** The cached file [name], downloaded from [url] when missing or older than a week. */
    @Synchronized
    private fun file(context: Context, name: String, url: String): JSONObject? {
        val f = File(File(context.filesDir, "hms").apply { mkdirs() }, name)
        val now = System.currentTimeMillis()
        val stale = !f.exists() || now - f.lastModified() > MAX_AGE_MS
        if (stale && now - (failedAt[name] ?: 0L) > RETRY_MS) {
            runCatching {
                val body = Http().get(url)
                val json = JSONObject(body)
                // result 0 with data is a catalog (as HMSQuery checks); anything else keeps the cached copy.
                if (json.optInt("result", -1) != 0 || !json.has("data")) error("no catalog")
                f.writeText(body)
                loaded[name] = json
            }.onFailure { failedAt[name] = now }
        }
        loaded[name]?.let { return it }
        return runCatching { JSONObject(f.readText()) }.getOrNull()?.also { loaded[name] = it }
    }

    /** The app is translated to German; every other language gets the English texts. */
    private fun language() = if (Locale.getDefault().language == "de") "de" else "en"
}
