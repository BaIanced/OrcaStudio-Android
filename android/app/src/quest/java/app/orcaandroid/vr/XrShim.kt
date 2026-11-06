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

    const val HANDS = 2
    /** One hand in [nativeInput]'s array, left then right. */
    const val FLOATS_PER_HAND = 12
    /** 1 when the controller is tracked and the session has focus; everything else is 0 otherwise. */
    const val HAND_ACTIVE = 0
    /** Aim pose in LOCAL: position x y z, quaternion x y z w. */
    const val HAND_POSE = 1
    const val HAND_GRIP = 8
    const val HAND_STICK_X = 9
    const val HAND_STICK_Y = 10
    const val HAND_CLICK = 11

    init {
        System.loadLibrary("orca_xr")
    }

    @JvmStatic external fun nativeCreate(activity: Activity, eglDisplay: Long, eglConfig: Long, eglContext: Long, passthrough: Boolean): Boolean
    /** True when the passthrough layer runs, so the background must be cleared with alpha 0. */
    @JvmStatic external fun nativePassthroughActive(): Boolean
    @JvmStatic external fun nativePollEvents(): Int
    @JvmStatic external fun nativeSwapchainSize(eye: Int, out: IntArray)
    /** True when the caller should draw. [nativeEndFrame] must follow either way. */
    @JvmStatic external fun nativeBeginFrame(outViews: FloatArray): Boolean
    /** Controller state for the frame just begun. Only call after [nativeBeginFrame] returned true. */
    @JvmStatic external fun nativeInput(outHands: FloatArray)
    /** The GL framebuffer to draw this eye into, or 0 when no image could be acquired. */
    @JvmStatic external fun nativeAcquireImage(eye: Int): Int
    @JvmStatic external fun nativeReleaseImage(eye: Int)
    @JvmStatic external fun nativeEndFrame(rendered: Boolean)
    @JvmStatic external fun nativeDestroy()
}
