package app.orcaandroid.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A world-locked text panel: instructions above, live status below. Not the M3 Compose panel: no input, only text drawn into
 * a texture, so its words can change over adb with no build. All calls come from the render thread; text is laid out off it.
 */
internal class InfoPanel(private val scope: CoroutineScope) {
    /** Panel centre in LOCAL metres and its width, set live from `vr_tuning.json`. Above and beyond the plate's first spot. */
    var x = 0f
    var y = 0f
    var z = -1.2f
    var width = 0.7f

    private var text = PanelText.DEFAULT
    private var status = ""
    private var drawn: Pair<String, String>? = null
    private val laying = AtomicBoolean(false)
    private val ready = AtomicReference<Bitmap?>(null)
    private var hasImage = false

    private val program: Int
    private val uMvp: Int
    private val texture: Int
    private val buffer: Int
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    init {
        val vertex = shader(GL_VERTEX_SHADER, "#version 300 es\nlayout(location=0) in vec2 p;uniform mat4 m;out vec2 t;void main(){t=vec2(p.x+.5,.5-p.y);gl_Position=m*vec4(p,0.,1.);}")
        val fragment = shader(GL_FRAGMENT_SHADER, "#version 300 es\nprecision mediump float;uniform sampler2D s;in vec2 t;out vec4 o;void main(){o=vec4(texture(s,t).rgb,1.);}")
        program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        uMvp = glGetUniformLocation(program, "m")
        val ids = IntArray(2)
        glGenBuffers(1, ids, 0)
        buffer = ids[0]
        val data = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD).position(0)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        glBufferData(GL_ARRAY_BUFFER, QUAD.size * 4, data, GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glGenTextures(1, ids, 1)
        texture = ids[1]
    }

    /** An empty [value] hides the panel. */
    fun setText(value: String) {
        text = value
    }

    fun setStatus(value: String) {
        status = value
    }

    /** Before the eyes are bound: starts a layout if the words changed, and uploads a finished one. */
    fun prepare() {
        val wanted = text to status
        if (wanted != drawn && text.isNotBlank() && laying.compareAndSet(false, true)) {
            drawn = wanted
            scope.launch {
                // Uncaught, a failure here would end the whole process.
                runCatching { ready.getAndSet(layout(wanted.first, wanted.second))?.recycle() }
                    .onFailure { Log.e("OrcaXr", "VR_PANEL layout failed", it) }
                laying.set(false)
            }
        }
        val image = ready.getAndSet(null) ?: return
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, image, 0)
        // Mipmaps, or small text shimmers as the head moves.
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)
        image.recycle()
        hasImage = true
    }

    fun draw(viewProj: FloatArray) {
        if (!hasImage || text.isBlank()) return
        model.fill(0f)
        model[0] = width
        model[5] = width * IMAGE_H / IMAGE_W
        model[10] = 1f
        model[12] = x
        model[13] = y
        model[14] = z
        model[15] = 1f
        VrMath.multiply(mvp, viewProj, model)
        glUseProgram(program)
        glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)
        glDrawArrays(GL_TRIANGLES, 0, QUAD.size / 2)
        // The renderer draws with its own attributes in the default vertex array.
        glDisableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    private fun layout(body: String, live: String): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_W, IMAGE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        val inner = IMAGE_W - 2 * MARGIN
        val statusLayout = staticLayout(live, statusPaint, inner)
        val statusTop = (IMAGE_H - MARGIN - statusLayout.height).toFloat()
        // Too much text is cut off above the status, which always stays readable.
        canvas.save()
        canvas.clipRect(0f, 0f, IMAGE_W.toFloat(), statusTop - DIVIDER_GAP)
        canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
        staticLayout(styled(body), bodyPaint, inner).draw(canvas)
        canvas.restore()
        canvas.drawRect(MARGIN.toFloat(), statusTop - DIVIDER_GAP, (IMAGE_W - MARGIN).toFloat(), statusTop - DIVIDER_GAP + 2f, statusPaint)
        canvas.translate(MARGIN.toFloat(), statusTop)
        statusLayout.draw(canvas)
        return bitmap
    }

    private fun staticLayout(content: CharSequence, paint: TextPaint, width: Int): Layout =
        StaticLayout.Builder.obtain(content, 0, content.length, paint, width).setLineSpacing(0f, 1.1f).build()

    /** Lines starting with "# " become headings. */
    private fun styled(body: String): CharSequence {
        val out = SpannableStringBuilder()
        body.lineSequence().forEachIndexed { i, line ->
            if (i > 0) out.append('\n')
            val start = out.length
            if (line.startsWith("# ")) {
                out.append(line.removePrefix("# "))
                out.setSpan(RelativeSizeSpan(1.3f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(HEADING), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(line)
            }
        }
        return out
    }

    private fun shader(type: Int, source: String): Int {
        val id = glCreateShader(type)
        glShaderSource(id, source)
        glCompileShader(id)
        return id
    }

    private val bodyPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 236, 240)
        textSize = 36f
    }
    private val statusPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 196, 110)
        textSize = 30f
    }

    private companion object {
        const val IMAGE_W = 1024
        const val IMAGE_H = 682
        const val MARGIN = 40
        const val DIVIDER_GAP = 16f
        val BACKGROUND = Color.rgb(20, 24, 30)
        val HEADING = Color.rgb(90, 215, 255)
        /** Two triangles, in the panel's own units: [draw] scales them to size. */
        val QUAD = floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f)
    }
}
