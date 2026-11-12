package app.orcaandroid.render

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Pauses and resumes [view]'s GL thread with the hosting lifecycle (a GLSurfaceView does not do it itself). */
@Composable
fun GlLifecycle(view: GLSurfaceView?) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, view) {
        val v = view ?: return@DisposableEffect onDispose {}
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> v.onResume()
                Lifecycle.Event.ON_PAUSE -> v.onPause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
