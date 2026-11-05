package app.orcaandroid.vr

import android.app.Activity

/** OpenXR through `src/quest/cpp/xr_shim.cpp`. Every call must run on the render thread, with the EGL context current. */
internal object XrShim {
    /** [nativePollEvents] result bits. */
    const val RUNNING = 1
    const val EXIT = 2

    const val EYES = 2
    /** One view in [nativeBeginFrame]'s array: pose x y z, quaternion x y z w, fov left right up down. */
    const val FLOATS_PER_VIEW = 11

    init {
        System.loadLibrary("orca_xr")
    }

    @JvmStatic external fun nativeCreate(activity: Activity, eglDisplay: Long, eglConfig: Long, eglContext: Long): Boolean
    @JvmStatic external fun nativePollEvents(): Int
    @JvmStatic external fun nativeSwapchainSize(eye: Int, out: IntArray)
    /** True when the caller should draw. [nativeEndFrame] must follow either way. */
    @JvmStatic external fun nativeBeginFrame(outViews: FloatArray): Boolean
    /** The GL texture to draw this eye into, or 0 when no image could be acquired. */
    @JvmStatic external fun nativeAcquireImage(eye: Int): Int
    @JvmStatic external fun nativeReleaseImage(eye: Int)
    @JvmStatic external fun nativeEndFrame(rendered: Boolean)
    @JvmStatic external fun nativeDestroy()
}
