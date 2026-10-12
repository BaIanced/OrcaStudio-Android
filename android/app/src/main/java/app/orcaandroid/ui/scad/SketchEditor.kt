package app.orcaandroid.ui.scad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orcaandroid.R
import app.orcaandroid.scad.Loop
import app.orcaandroid.scad.P2
import app.orcaandroid.scad.ShapeDesign
import app.orcaandroid.scad.Sketch
import app.orcaandroid.ui.components.NumberField
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.tan

private enum class Tool(val label: Int, val hint: Int) {
    SELECT(R.string.sketch_select, R.string.sketch_hint_select),
    LINE(R.string.sketch_lines, R.string.sketch_hint_lines),
    RECT(R.string.sketch_rect, R.string.sketch_hint_rect),
    CIRCLE(R.string.sketch_circle, R.string.sketch_hint_circle),
}

/** What is picked: a whole outline, one corner ([index]) or one side ([index] to [index] + 1). */
private data class Pick(val loop: Int, val what: What, val index: Int = 0) {
    enum class What { LOOP, POINT, SIDE }
}

/** View and edit state of the sketch editor (kept out of composition so the gesture loop sees it live). */
private class SketchState(initial: Sketch) {
    var sketch by mutableStateOf(initial)
    val history = ArrayDeque<Sketch>()
    var undoCount by mutableStateOf(0)
    var tool by mutableStateOf(Tool.SELECT)
    var pick by mutableStateOf<Pick?>(null)
    var draft by mutableStateOf<List<P2>>(emptyList())
    var hover by mutableStateOf<P2?>(null)
    var dragFrom by mutableStateOf<P2?>(null)
    var dragTo by mutableStateOf<P2?>(null)

    /** Screen pixels per mm and the screen position of the sketch origin. */
    var scale by mutableStateOf(8f)
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(Size.Zero)

    fun edit(next: Sketch) {
        if (next == sketch) return
        history.addLast(sketch)
        if (history.size > 200) history.removeFirst()
        undoCount = history.size
        sketch = next
    }

    fun undo() {
        if (draft.isNotEmpty()) { draft = draft.dropLast(1); return }
        sketch = history.removeLastOrNull() ?: return
        undoCount = history.size
        pick = null
    }

    fun toScreen(p: P2) = Offset(origin.x + p.x.toFloat() * scale, origin.y - p.y.toFloat() * scale)
    fun toMm(o: Offset) = P2(((o.x - origin.x) / scale).toDouble(), ((origin.y - o.y) / scale).toDouble())

    /** Grid step (mm): the smallest of 0.5, 1, 2, 5, 10... that is at least [minPx] apart on screen. */
    fun grid(minPx: Float): Double = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0).firstOrNull { it * scale >= minPx } ?: 100.0

    fun fit() {
        if (size.width <= 0f) return
        val b = sketch.bounds()
        val lo = b?.first ?: P2(-20.0, -20.0)
        val hi = b?.second ?: P2(20.0, 20.0)
        val w = max(hi.x - lo.x, 10.0)
        val h = max(hi.y - lo.y, 10.0)
        scale = (min(size.width / w, size.height / h) * 0.75).toFloat().coerceIn(0.5f, 200f)
        val c = (lo + hi) * 0.5
        origin = Offset(size.width / 2 - c.x.toFloat() * scale, size.height / 2 + c.y.toFloat() * scale)
    }

    fun zoom(factor: Float, about: Offset) {
        val s = (scale * factor).coerceIn(0.5f, 200f)
        origin = about + (origin - about) * (s / scale)
        scale = s
    }

    /** [p] snapped: onto a corner nearby, else the grid, then level / plumb with [from]. */
    fun snap(p: P2, slopPx: Float, from: P2? = null, skip: Pair<Int, Int>? = null): P2 {
        val tol = slopPx / scale
        sketch.loops.forEachIndexed { li, l ->
            l.pts.forEachIndexed { pi, q -> if ((li to pi) != skip && q.dist(p) <= tol) return q }
        }
        draft.firstOrNull()?.let { if (it.dist(p) <= tol) return it }
        val g = grid(slopPx)
        var x = round(p.x / g) * g
        var y = round(p.y / g) * g
        if (from != null) {
            if (abs(x - from.x) <= tol) x = from.x
            if (abs(y - from.y) <= tol) y = from.y
        }
        return P2(x, y)
    }

    /** The corner, side handle (of the picked outline) or outline under screen point [o]. */
    fun hit(o: Offset, slopPx: Float): Pick? {
        val loops = sketch.loops
        val order = (listOfNotNull(pick?.loop) + loops.indices).distinct().filter { it in loops.indices }
        for (li in order) loops[li].pts.forEachIndexed { pi, q -> if ((toScreen(q) - o).getDistance() <= slopPx) return Pick(li, Pick.What.POINT, pi) }
        pick?.loop?.takeIf { it in loops.indices }?.let { li ->
            val l = loops[li]
            for (i in l.pts.indices) if ((toScreen(l.sideMid(i)) - o).getDistance() <= slopPx) return Pick(li, Pick.What.SIDE, i)
        }
        val p = toMm(o)
        // Topmost first: later outlines are drawn over earlier ones.
        for (li in loops.indices.reversed()) if (inside(loops[li].polygon(), p)) return Pick(li, Pick.What.LOOP)
        return null
    }

    private fun inside(poly: List<P2>, p: P2): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    fun closeDraft() {
        if (draft.size >= 3) {
            // Counter-clockwise, so a positive bend bulges outwards.
            val area = draft.indices.sumOf { i -> val a = draft[i]; val b = draft[(i + 1) % draft.size]; a.x * b.y - b.x * a.y }
            val pts = if (area < 0) draft.reversed() else draft
            edit(sketch.copy(loops = sketch.loops + Loop(pts)))
            pick = Pick(sketch.loops.size - 1, Pick.What.LOOP)
        }
        draft = emptyList()
    }

    fun setLoop(li: Int, f: (Loop) -> Loop, record: Boolean = true) {
        val next = sketch.copy(loops = sketch.loops.mapIndexed { i, l -> if (i == li) f(l) else l })
        if (record) edit(next) else sketch = next
    }

    fun deletePicked() {
        val p = pick ?: return
        val l = sketch.loops.getOrNull(p.loop) ?: return
        if (p.what == Pick.What.POINT && l.pts.size > 3) {
            setLoop(p.loop, { it.copy(pts = it.pts.filterIndexed { i, _ -> i != p.index }, bulge = it.bulge.filterIndexed { i, _ -> i != p.index }) })
            pick = Pick(p.loop, Pick.What.LOOP)
        } else {
            edit(sketch.copy(loops = sketch.loops.filterIndexed { i, _ -> i != p.loop }))
            pick = null
        }
    }

    /** Puts a corner in the middle of the picked side; an arc stays the same arc, in two halves. */
    fun split() {
        val p = pick?.takeIf { it.what == Pick.What.SIDE } ?: return
        setLoop(p.loop, { l ->
            val half = tan(atan(l.bulgeAt(p.index)) / 2)
            val pts = l.pts.toMutableList().apply { add(p.index + 1, l.sideMid(p.index)) }
            val bulge = l.pts.indices.map { l.bulgeAt(it) }.toMutableList().apply { set(p.index, half); add(p.index + 1, half) }
            l.copy(pts = pts, bulge = bulge)
        })
        pick = Pick(p.loop, Pick.What.POINT, p.index + 1)
    }
}

/**
 * Full-screen 2D sketch editor: draw outlines (lines, rectangles, circles), drag corners, bend sides
 * into arcs, mark outlines as holes. Snaps to corners, the grid, and level / plumb lines. [revolve]
 * shows the turning axis (x = 0): the profile on its right is turned about it.
 */
@Composable
fun SketchEditor(initial: Sketch, revolve: Boolean, onDone: (Sketch) -> Unit, onCancel: () -> Unit) {
    val st = remember { SketchState(initial) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    e.isCtrlPressed && e.key == Key.Z -> { st.undo(); true }
                    e.key == Key.Delete || e.key == Key.Backspace -> { st.deletePicked(); true }
                    e.key == Key.Enter -> { st.closeDraft(); true }
                    e.key == Key.Escape && (st.draft.isNotEmpty() || st.pick != null) -> { st.draft = emptyList(); st.pick = null; true }
                    else -> false
                }
            }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                    Text(stringResource(if (revolve) R.string.sketch_title_revolve else R.string.sketch_title),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = st::undo, enabled = st.undoCount > 0 || st.draft.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.shape_undo))
                    }
                    IconButton(onClick = st::fit) { Icon(Icons.Default.FitScreen, stringResource(R.string.sketch_fit)) }
                    TextButton(onClick = { st.closeDraft(); onDone(st.sketch) }) { Text(stringResource(R.string.sketch_done)) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tool.entries.forEach { t ->
                        FilterChip(st.tool == t, { st.closeDraft(); st.tool = t }, label = { Text(stringResource(t.label)) })
                    }
                }
                SketchCanvas(st, revolve, Modifier.weight(1f).fillMaxWidth())
                PickBar(st)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun SketchCanvas(st: SketchState, revolve: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    var fitted by remember { mutableStateOf(false) }
    Box(modifier.onSizeChanged {
        st.size = Size(it.width.toFloat(), it.height.toFloat())
        if (!fitted && it.width > 0) { fitted = true; st.fit() }
    }) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            val slop = 14.dp.toPx()
            awaitPointerEventScope {
                var down: Offset? = null
                var moved = false
                var target: Pick? = null
                var grabMm: P2? = null
                var startSketch: Sketch? = null
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull() ?: continue
                    when (e.type) {
                        PointerEventType.Scroll -> {
                            st.zoom(Math.pow(1.15, -ch.scrollDelta.y.toDouble()).toFloat(), ch.position)
                            ch.consume()
                        }
                        PointerEventType.Press -> if (down == null) {
                            down = ch.position
                            moved = false
                            target = if (st.tool == Tool.SELECT) st.hit(ch.position, slop) else null
                            grabMm = st.toMm(ch.position)
                            startSketch = st.sketch
                        }
                        PointerEventType.Move -> {
                            val d0 = down
                            if (d0 == null) { st.hover = st.snap(st.toMm(ch.position), slop, st.draft.lastOrNull()); continue }
                            if (e.changes.count { it.pressed } >= 2) {
                                // Two fingers: pan and pinch.
                                moved = true
                                st.zoom(e.calculateZoom(), e.calculateCentroid())
                                st.origin += e.calculatePan()
                                st.dragFrom = null
                                continue
                            }
                            if (!moved && (ch.position - d0).getDistance() < viewConfiguration.touchSlop) continue
                            moved = true
                            val p = st.toMm(ch.position)
                            when (st.tool) {
                                Tool.SELECT -> {
                                    val t = target
                                    val start = startSketch ?: st.sketch
                                    when (t?.what) {
                                        Pick.What.POINT -> {
                                            val q = st.snap(p, slop, skip = t.loop to t.index)
                                            st.sketch = start.copy(loops = start.loops.mapIndexed { i, l ->
                                                if (i == t.loop) l.copy(pts = l.pts.mapIndexed { k, v -> if (k == t.index) q else v }) else l
                                            })
                                            st.pick = t
                                        }
                                        Pick.What.SIDE -> {
                                            val l = start.loops[t.loop]
                                            val a = l.pts[t.index]
                                            val b = l.pts[(t.index + 1) % l.pts.size]
                                            val len = a.dist(b)
                                            if (len > 0) {
                                                val r = Loop.right(a, b)
                                                val m = (a + b) * 0.5
                                                var k = 2 * ((p.x - m.x) * r.x + (p.y - m.y) * r.y) / len
                                                k = k.coerceIn(-3.0, 3.0)
                                                if (abs(k) < 0.05) k = 0.0
                                                if (abs(abs(k) - 1) < 0.05) k = if (k > 0) 1.0 else -1.0
                                                st.sketch = start.copy(loops = start.loops.mapIndexed { i, lp ->
                                                    if (i == t.loop) lp.copy(bulge = lp.pts.indices.map { j -> if (j == t.index) k else lp.bulgeAt(j) }) else lp
                                                })
                                            }
                                            st.pick = t
                                        }
                                        Pick.What.LOOP -> {
                                            val g = st.grid(slop)
                                            val from = grabMm ?: p
                                            val dx = round((p.x - from.x) / g) * g
                                            val dy = round((p.y - from.y) / g) * g
                                            st.sketch = start.copy(loops = start.loops.mapIndexed { i, l ->
                                                if (i == t.loop) l.copy(pts = l.pts.map { v -> P2(v.x + dx, v.y + dy) }) else l
                                            })
                                            st.pick = t
                                        }
                                        null -> st.origin += ch.position - ch.previousPosition
                                    }
                                }
                                Tool.RECT, Tool.CIRCLE -> {
                                    if (st.dragFrom == null) st.dragFrom = st.snap(grabMm ?: p, slop)
                                    st.dragTo = st.snap(p, slop, st.dragFrom)
                                }
                                Tool.LINE -> st.origin += ch.position - ch.previousPosition
                            }
                            ch.consume()
                        }
                        PointerEventType.Release -> if (e.changes.none { it.pressed }) {
                            val p = st.toMm(ch.position)
                            if (!moved) when (st.tool) {
                                Tool.SELECT -> st.pick = st.hit(ch.position, slop)
                                Tool.LINE -> {
                                    val q = st.snap(p, slop, st.draft.lastOrNull())
                                    if (st.draft.size >= 3 && q == st.draft.first()) st.closeDraft()
                                    else if (q != st.draft.lastOrNull()) st.draft = st.draft + q
                                }
                                else -> {}
                            } else {
                                val a = st.dragFrom
                                val b = st.dragTo
                                when {
                                    st.tool == Tool.SELECT -> startSketch?.let { s0 ->
                                        val after = st.sketch
                                        if (after != s0) { st.sketch = s0; st.edit(after) }
                                    }
                                    st.tool == Tool.RECT && a != null && b != null && a.x != b.x && a.y != b.y -> {
                                        st.edit(st.sketch.copy(loops = st.sketch.loops + Loop.rect(a, b)))
                                        st.pick = Pick(st.sketch.loops.size - 1, Pick.What.LOOP)
                                    }
                                    st.tool == Tool.CIRCLE && a != null && b != null && a.dist(b) > 0 -> {
                                        st.edit(st.sketch.copy(loops = st.sketch.loops + Loop.circle(a, a.dist(b))))
                                        st.pick = Pick(st.sketch.loops.size - 1, Pick.What.LOOP)
                                    }
                                }
                            }
                            st.dragFrom = null
                            st.dragTo = null
                            down = null
                            target = null
                            startSketch = null
                        }
                        else -> {}
                    }
                }
            }
        }) {
            drawRect(scheme.surfaceContainerLowest)
            // Grid: fine lines every step, stronger every 10 mm (or every 5 steps when coarser).
            val g = st.grid(10.dp.toPx())
            val major = if (g < 10) 10.0 else g * 5
            val lo = st.toMm(Offset(0f, size.height))
            val hi = st.toMm(Offset(size.width, 0f))
            fun lines(step: Double, color: Color, w: Float) {
                var x = kotlin.math.floor(lo.x / step) * step
                while (x <= hi.x) { val sx = st.toScreen(P2(x, 0.0)).x; drawLine(color, Offset(sx, 0f), Offset(sx, size.height), w); x += step }
                var y = kotlin.math.floor(lo.y / step) * step
                while (y <= hi.y) { val sy = st.toScreen(P2(0.0, y)).y; drawLine(color, Offset(0f, sy), Offset(size.width, sy), w); y += step }
            }
            lines(g, scheme.outlineVariant.copy(alpha = 0.35f), 1f)
            lines(major, scheme.outlineVariant.copy(alpha = 0.8f), 1f)
            val o = st.origin
            drawLine(Color(0xFFD05050), Offset(0f, o.y), Offset(size.width, o.y), 2f)
            drawLine(if (revolve) scheme.tertiary else Color(0xFF50A050), Offset(o.x, 0f), Offset(o.x, size.height), if (revolve) 5f else 2f,
                pathEffect = if (revolve) PathEffect.dashPathEffect(floatArrayOf(18f, 10f)) else null)

            fun path(pts: List<P2>, close: Boolean) = Path().apply {
                pts.forEachIndexed { i, p -> val s = st.toScreen(p); if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y) }
                if (close) close()
            }
            val picked = st.pick
            st.sketch.loops.forEachIndexed { li, l ->
                val poly = path(l.polygon(), true)
                val sel = picked?.loop == li
                val c = if (l.hole) scheme.error else scheme.primary
                drawPath(poly, c.copy(alpha = if (l.hole) 0.12f else 0.22f))
                drawPath(poly, c, style = Stroke(if (sel) 5f else 3f, pathEffect = if (l.hole) PathEffect.dashPathEffect(floatArrayOf(14f, 8f)) else null))
                l.pts.forEachIndexed { pi, q ->
                    val on = sel && picked.what == Pick.What.POINT && picked.index == pi
                    drawCircle(if (on) scheme.tertiary else c, if (on) 11f else 7f, st.toScreen(q))
                }
                if (sel) l.pts.indices.forEach { i ->
                    val m = st.toScreen(l.sideMid(i))
                    val on = picked.what == Pick.What.SIDE && picked.index == i
                    val r = if (on) 10f else 7f
                    drawRect(if (on) scheme.tertiary else scheme.secondary, Offset(m.x - r, m.y - r), Size(2 * r, 2 * r))
                }
            }
            // Lines being drawn, with a rubber band to the pointer.
            if (st.draft.isNotEmpty()) {
                drawPath(path(st.draft + listOfNotNull(st.hover), false), scheme.tertiary, style = Stroke(3f))
                st.draft.forEach { drawCircle(scheme.tertiary, 7f, st.toScreen(it)) }
                drawCircle(scheme.tertiary, 12f, st.toScreen(st.draft.first()), style = Stroke(2f))
            }
            val a = st.dragFrom
            val b = st.dragTo
            if (a != null && b != null) {
                val l = if (st.tool == Tool.CIRCLE) Loop.circle(a, a.dist(b)) else Loop.rect(a, b)
                drawPath(path(l.polygon(), true), scheme.tertiary, style = Stroke(3f))
            }
            st.hover?.takeIf { st.tool != Tool.SELECT }?.let { drawCircle(scheme.tertiary, 5f, st.toScreen(it)) }
        }
    }
}

/** Below the drawing: what the tool does, or the picked corner / side / outline's settings. */
@Composable
private fun PickBar(st: SketchState) {
    fun n(v: Double) = ShapeDesign.n(v)
    val p = st.pick
    val l = p?.let { st.sketch.loops.getOrNull(it.loop) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val a = st.dragFrom
        val b = st.dragTo
        val info = when {
            a != null && b != null && st.tool == Tool.CIRCLE -> stringResource(R.string.sketch_diameter, n(2 * a.dist(b)))
            a != null && b != null -> stringResource(R.string.sketch_wh, n(abs(b.x - a.x)), n(abs(b.y - a.y)))
            st.tool == Tool.LINE && st.draft.isNotEmpty() && st.hover != null ->
                stringResource(R.string.sketch_length, n(st.draft.last().dist(st.hover!!)))
            else -> null
        }
        val pos = st.hover?.let { "x ${n(it.x)}, y ${n(it.y)}" }
        Text(listOfNotNull(stringResource(st.tool.hint), info, pos).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (st.tool == Tool.LINE && st.draft.size >= 3) {
            TextButton(onClick = st::closeDraft) { Text(stringResource(R.string.sketch_close)) }
        }
        if (p == null || l == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (p.what) {
                Pick.What.POINT -> {
                    val q = l.pts[p.index]
                    fun set(v: P2) = st.setLoop(p.loop, { it.copy(pts = it.pts.mapIndexed { i, o -> if (i == p.index) v else o }) })
                    NumberField(q.x.toFloat(), { set(q.copy(x = it.toDouble())) }, Modifier.width(110.dp), label = "X")
                    NumberField(q.y.toFloat(), { set(q.copy(y = it.toDouble())) }, Modifier.width(110.dp), label = "Y")
                }
                Pick.What.SIDE -> {
                    val k = l.bulgeAt(p.index)
                    NumberField(k.toFloat(), { v -> st.setLoop(p.loop, { lp -> lp.copy(bulge = lp.pts.indices.map { j -> if (j == p.index) v.toDouble().coerceIn(-3.0, 3.0) else lp.bulgeAt(j) }) }) },
                        Modifier.width(130.dp), label = stringResource(R.string.sketch_bend))
                    TextButton(onClick = { st.setLoop(p.loop, { lp -> lp.copy(bulge = lp.pts.indices.map { j -> if (j == p.index) 0.0 else lp.bulgeAt(j) }) }) }, enabled = k != 0.0) {
                        Text(stringResource(R.string.sketch_straight))
                    }
                    TextButton(onClick = st::split) { Text(stringResource(R.string.sketch_split)) }
                }
                Pick.What.LOOP -> {
                    Text(stringResource(R.string.shape_hole))
                    Switch(l.hole, { h -> st.setLoop(p.loop, { it.copy(hole = h) }) })
                }
            }
            IconButton(onClick = st::deletePicked) { Icon(Icons.Default.Delete, stringResource(R.string.shape_delete)) }
        }
    }
}
