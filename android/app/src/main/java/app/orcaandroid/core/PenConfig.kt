package app.orcaandroid.core

import org.json.JSONObject

/** What a pen button or the eraser end does. TIP = nothing special: it acts like the tip (one finger). */
enum class PenAction {
    TIP, PAN, ORBIT, SELECT_ADD, SELECT_REMOVE, MENU, UNDO, REDO, FRAME;

    /** Fires once (a tap or a button press) instead of changing what a drag does. */
    val instant get() = this == UNDO || this == REDO || this == FRAME
}

/** How pen pressure (0..1) maps to the brush: SOFT reaches full effect with a light touch, FIRM needs a hard press. */
enum class PressureCurve(val gamma: Float) { SOFT(0.6f), LINEAR(1f), FIRM(1.6f) }

/** Stylus settings (More > Pen): palm rejection, pressure and what the two barrel buttons and the eraser end do. */
data class PenConfig(
    /** Finger touches are ignored while the pen touches or hovers near the screen. */
    val ignoreFingers: Boolean = true,
    val button1: PenAction = PenAction.PAN,
    val button2: PenAction = PenAction.MENU,
    val eraser: PenAction = PenAction.UNDO,
    /** Pen pressure scales Sculpt strength, the Paint brush radius and the layer-height tap step (stylus only). */
    val pressure: Boolean = true,
    val curve: PressureCurve = PressureCurve.LINEAR,
) {
    /** Brush scale 0..1 for a raw [raw] pressure from a [stylus] pointer; 1 for fingers and mice or when pressure is off. */
    fun scale(raw: Float, stylus: Boolean): Float =
        if (!pressure || !stylus) 1f else Math.pow(raw.coerceIn(0f, 1f).toDouble(), curve.gamma.toDouble()).toFloat()

    fun toJson(): String = JSONObject().put("ignoreFingers", ignoreFingers)
        .put("button1", button1.name).put("button2", button2.name).put("eraser", eraser.name)
        .put("pressure", pressure).put("curve", curve.name).toString()

    companion object {
        fun fromJson(json: String?): PenConfig {
            val d = PenConfig()
            val o = runCatching { JSONObject(json!!) }.getOrNull() ?: return d
            fun action(key: String, default: PenAction) = runCatching { PenAction.valueOf(o.getString(key)) }.getOrDefault(default)
            val curve = runCatching { PressureCurve.valueOf(o.getString("curve")) }.getOrDefault(d.curve)
            return PenConfig(o.optBoolean("ignoreFingers", d.ignoreFingers), action("button1", d.button1), action("button2", d.button2), action("eraser", d.eraser),
                o.optBoolean("pressure", d.pressure), curve)
        }
    }
}
