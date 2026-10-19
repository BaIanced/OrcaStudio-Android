package app.orcaandroid.core

import org.json.JSONObject

/** What a pen button or the eraser end does. TIP = nothing special: it acts like the tip (one finger). */
enum class PenAction {
    TIP, PAN, ORBIT, SELECT_ADD, SELECT_REMOVE, MENU, UNDO, REDO, FRAME;

    /** Fires once (a tap or a button press) instead of changing what a drag does. */
    val instant get() = this == UNDO || this == REDO || this == FRAME
}

/** Stylus settings (More > Pen): palm rejection and what the two barrel buttons and the eraser end do. */
data class PenConfig(
    /** Finger touches are ignored while the pen touches or hovers near the screen. */
    val ignoreFingers: Boolean = true,
    val button1: PenAction = PenAction.PAN,
    val button2: PenAction = PenAction.MENU,
    val eraser: PenAction = PenAction.UNDO,
) {
    fun toJson(): String = JSONObject().put("ignoreFingers", ignoreFingers)
        .put("button1", button1.name).put("button2", button2.name).put("eraser", eraser.name).toString()

    companion object {
        fun fromJson(json: String?): PenConfig {
            val d = PenConfig()
            val o = runCatching { JSONObject(json!!) }.getOrNull() ?: return d
            fun action(key: String, default: PenAction) = runCatching { PenAction.valueOf(o.getString(key)) }.getOrDefault(default)
            return PenConfig(o.optBoolean("ignoreFingers", d.ignoreFingers), action("button1", d.button1), action("button2", d.button2), action("eraser", d.eraser))
        }
    }
}
