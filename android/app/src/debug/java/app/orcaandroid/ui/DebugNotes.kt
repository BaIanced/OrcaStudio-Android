package app.orcaandroid.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import org.json.JSONObject

/**
 * Debug-only quick notes (Ctrl+Shift+N, or the S Pen button while hovering): freezes the current
 * window, asks for a note (type or dictate) and appends it to `notes.jsonl` with a downscaled
 * screenshot and a small state snapshot. Pull: `adb pull /sdcard/Android/data/<pkg>/files/notes`.
 * Stays on the device; never commit the files (they can show account details).
 */
object DebugNotes {
    private var showing = false

    fun onKey(activity: Activity, event: KeyEvent, snapshot: () -> String): Boolean {
        logKey(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || event.keyCode != KeyEvent.KEYCODE_N ||
            !event.isCtrlPressed || !event.isShiftPressed) return false
        capture(activity, snapshot)
        return true
    }

    private var lastButtons = 0

    /** Pen button: Samsung reports it as a changed buttonState (primary or secondary) on hover moves, not always as BUTTON_PRESS. */
    fun onMotion(activity: Activity, event: MotionEvent, snapshot: () -> String): Boolean {
        val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        if (stylus && (event.buttonState != 0 || event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS))
            Log.d("DebugNotes", "motion action=${event.actionMasked} buttons=${event.buttonState} actionButton=${event.actionButton}")
        if (!stylus) return false
        val down = event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)
        val pressed = down != 0 && lastButtons == 0
        lastButtons = down
        if (!pressed) return false
        capture(activity, snapshot)
        return true
    }

    /** Logs pen keys so an unknown button mapping can be found with logcat (tag DebugNotes). */
    fun logKey(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && !event.isCtrlPressed) Log.d("DebugNotes", "key ${event.keyCode} src=${event.source}")
    }

    private fun capture(activity: Activity, snapshot: () -> String) {
        if (showing) return
        showing = true
        val w = activity.window.decorView
        val bmp = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, bmp, { result ->
            if (result == PixelCopy.SUCCESS) overlaySurfaces(activity, w, bmp) { ask(activity, bmp, snapshot()) }
            else { showing = false; toast(activity, "Note: capture failed ($result)") }
        }, Handler(Looper.getMainLooper()))
    }

    /** A window copy leaves SurfaceViews (the 3D view) blank, so copy each one and draw it in place. */
    private fun overlaySurfaces(activity: Activity, root: View, window: Bitmap, done: () -> Unit) {
        val surfaces = ArrayList<SurfaceView>()
        fun walk(v: View) { if (v is SurfaceView && v.width > 0 && v.height > 0) surfaces.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
        val origin = IntArray(2).also { root.getLocationInWindow(it) }
        fun next(i: Int) {
            if (i >= surfaces.size) { done(); return }
            val sv = surfaces[i]
            val part = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(sv, part, { r ->
                if (r == PixelCopy.SUCCESS) {
                    val loc = IntArray(2).also { sv.getLocationInWindow(it) }
                    Canvas(window).drawBitmap(part, (loc[0] - origin[0]).toFloat(), (loc[1] - origin[1]).toFloat(), null)
                }
                next(i + 1)
            }, Handler(Looper.getMainLooper()))
        }
        next(0)
    }

    private fun ask(activity: Activity, shot: Bitmap, state: String) {
        val input = EditText(activity).apply { hint = "Note (type or dictate)"; minLines = 2; setSingleLine(false) }
        val thumb = ImageView(activity).apply { setImageBitmap(shot); adjustViewBounds = true; maxHeight = 360 }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0); addView(thumb); addView(input) }
        AlertDialog.Builder(activity).setTitle("Debug note").setView(box)
            .setPositiveButton("Save") { _, _ -> save(activity, shot, input.text.toString(), state) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { showing = false }
            .show().also {
                input.requestFocus()
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
            }
    }

    private fun save(activity: Activity, shot: Bitmap, text: String, state: String) {
        val dir = File(activity.getExternalFilesDir(null), "notes").apply { mkdirs() }
        val id = System.currentTimeMillis()
        val scale = 1280f / maxOf(shot.width, shot.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true) else shot
        File(dir, "$id.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
        val line = JSONObject().put("id", id).put("time", java.time.Instant.ofEpochMilli(id).toString())
            .put("note", text).put("state", state).put("shot", "$id.png")
        File(dir, "notes.jsonl").appendText(line.toString() + "\n")
        toast(activity, "Note saved")
    }

    private fun toast(activity: Activity, msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
