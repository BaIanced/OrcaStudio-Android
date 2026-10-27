package app.orcaandroid.core

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Feel knobs (user 2026-10-27: "all the settings for the movement zoom speed magnification should be in settings ... tuneable
 * by me and you"): one registry read live by the code that moves things, shown as sliders in More > Tuning and read / set
 * over MCP (`tuning_get`, `tuning_set`). Only values that differ from the default are saved.
 */
object Tuning {
    class Knob(val key: String, val label: String, val default: Float, val min: Float, val max: Float, val unit: String = "", val step: Float = 0f)

    val knobs = listOf(
        Knob("loupe.zoom", "Loupe magnification", 3f, 1.5f, 8f, "x", 0.5f),
        Knob("loupe.size", "Loupe size", 128f, 80f, 240f, " dp", 8f),
        Knob("loupe.gap", "Loupe gap above the aim", 40f, 16f, 120f, " dp", 4f),
        Knob("loupe.lift", "Finger offset (dot above the finger)", 56f, 0f, 120f, " dp", 4f),
        Knob("steer.fill", "Steer: feature fills the view", 0.4f, 0.1f, 0.9f, "", 0.05f),
        Knob("steer.min_feature", "Steer: smallest feature size (zoom limit)", 2f, 0.5f, 20f, " mm", 0.5f),
        Knob("steer.max_swing", "Steer: most swing / tilt from the start view", 35f, 0f, 90f, "°", 5f),
        Knob("steer.face_square", "Steer: faces turned square-on", 0.5f, 0f, 1f, "", 0.05f),
        Knob("steer.frame_s", "Steer: framing smoothness", 0.6f, 0.1f, 3f, " s", 0.1f),
        Knob("steer.follow_s", "Steer: camera follow smoothness", 0.35f, 0.05f, 2f, " s", 0.05f),
        Knob("steer.return_s", "Steer: way back after the lift", 0.3f, 0.05f, 2f, " s", 0.05f),
        Knob("steer.slow", "Steer: finger speed that frames (below)", 60f, 10f, 300f, " dp/s", 10f),
        Knob("steer.fast", "Steer: finger speed that backs out (above)", 500f, 150f, 2000f, " dp/s", 50f),
        Knob("steer.out_rate", "Steer: back-out speed", 1.3f, 0.2f, 5f, "/s", 0.1f),
        Knob("steer.speed_s", "Steer: finger speed smoothing", 0.12f, 0.02f, 1f, " s", 0.02f),
    )
    private val byKey = knobs.associateBy { it.key }
    private val values = ConcurrentHashMap<String, Float>()
    private var prefs: android.content.SharedPreferences? = null

    /** Bumped on every change, for UI that shows the values. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    fun init(context: Context) {
        val p = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs = p
        runCatching { JSONObject(p.getString("tuning", null) ?: "{}") }.getOrNull()?.let { o ->
            for (k in o.keys()) byKey[k]?.let { values[k] = o.optDouble(k).toFloat().coerceIn(it.min, it.max) }
        }
    }

    operator fun get(key: String): Float = values[key] ?: byKey[key]?.default ?: error("no tuning knob $key")

    /** Sets [key] (clamped to its range); false for an unknown key. */
    fun set(key: String, value: Float): Boolean {
        val k = byKey[key] ?: return false
        val v = value.coerceIn(k.min, k.max)
        if (kotlin.math.abs(v - k.default) < 1e-4f) values.remove(key) else values[key] = v
        save()
        return true
    }

    fun reset() { values.clear(); save() }

    private fun save() {
        prefs?.edit()?.putString("tuning", JSONObject(values.mapValues { it.value.toDouble() }).toString())?.apply()
        version.intValue++
    }

    /** One line per knob: key = value unit [min..max] (default), for MCP. */
    fun describe() = knobs.joinToString("\n") { k ->
        "${k.key} = ${fmt(get(k.key))}${k.unit} [${fmt(k.min)}..${fmt(k.max)}]" + if (values.containsKey(k.key)) " (default ${fmt(k.default)})" else ""
    }

    fun fmt(v: Float) = if (v == kotlin.math.round(v)) v.toInt().toString() else "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0')
}
