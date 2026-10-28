package app.orcaandroid.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent

/** Release stub: the in-app note tool exists only in debug builds (src/debug). */
object DebugNotes {
    fun onKey(activity: Activity, event: KeyEvent, snapshot: () -> String): Boolean = false
    fun onMotion(activity: Activity, event: MotionEvent, snapshot: () -> String): Boolean = false
}
