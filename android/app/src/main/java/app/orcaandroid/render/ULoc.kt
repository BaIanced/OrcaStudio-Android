package app.orcaandroid.render

import android.opengl.GLES30

/**
 * Uniform location cache. glGetUniformLocation by name costs a JNI call plus string compares in the driver
 * (about 6% of GL-thread time while orbiting, 2026-11 profile), so look each name up once per program.
 * Thread-local because every GLSurfaceView has its own GL thread and context, and program ids repeat across contexts.
 */
internal object ULoc {
    private val cache = ThreadLocal.withInitial { HashMap<Int, HashMap<String, Int>>() }

    fun get(program: Int, name: String): Int {
        val perProgram = cache.get()!!.getOrPut(program) { HashMap() }
        return perProgram.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }
    }

    /** Call right after glLinkProgram: a new program may reuse an id (also after context loss). */
    fun reset(program: Int) {
        cache.get()!!.remove(program)
    }
}

internal fun uloc(program: Int, name: String): Int = ULoc.get(program, name)
