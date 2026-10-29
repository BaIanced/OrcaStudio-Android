package app.orcaandroid.scad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** A point of a sketch (mm). */
data class P2(val x: Double, val y: Double) {
    operator fun plus(o: P2) = P2(x + o.x, y + o.y)
    operator fun minus(o: P2) = P2(x - o.x, y - o.y)
    operator fun times(k: Double) = P2(x * k, y * k)
    fun dist(o: P2) = hypot(x - o.x, y - o.y)
}

/**
 * A closed outline: corners in order, and per side (corner i to i + 1) a bulge that bends it into an
 * arc. The bulge is tan(arc angle / 4): 0 = straight, 1 = half circle; positive bends to the right
 * of the side's direction, i.e. outwards on an outline drawn counter-clockwise. A circle is two
 * corners with bulge 1 on both sides. A hole outline is cut from the outlines it overlaps. An [open] loop (a sweep
 * path) has no side from its last corner back to the first.
 */
data class Loop(val pts: List<P2>, val bulge: List<Double> = List(pts.size) { 0.0 }, val hole: Boolean = false, val open: Boolean = false) {

    fun bulgeAt(i: Int) = bulge.getOrElse(i) { 0.0 }

    /** Its sides' indices (side i runs from corner i to i + 1). */
    val sides: IntRange get() = 0 until if (open) pts.size - 1 else pts.size

    /** The point halfway along side [i] (on the arc when bent). */
    fun sideMid(i: Int): P2 {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        val m = (a + b) * 0.5
        val l = a.dist(b)
        if (l == 0.0) return m
        return m + right(a, b) * (bulgeAt(i) * l / 2)
    }

    /** The outline as straight segments, arcs split so a side is at most [fs] mm or [fa] degrees. */
    fun polygon(fs: Double = 0.4, fa: Double = 4.0): List<P2> {
        val out = ArrayList<P2>()
        for (i in sides) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            out += a
            val k = bulgeAt(i)
            val l = a.dist(b)
            if (k == 0.0 || l == 0.0) continue
            val s = k * l / 2                           // sagitta, signed
            val r = (l * l / 4 + s * s) / (2 * s)       // radius, signed like s
            val c = (a + b) * 0.5 + right(a, b) * (s - r)
            val sweep = sign(k) * 4 * atan(abs(k))
            val a0 = atan2(a.y - c.y, a.x - c.x)
            val n = max(2, min(ceil(Math.toDegrees(abs(sweep)) / fa), ceil(abs(sweep * r) / fs)).toInt())
            for (j in 1 until n) {
                val t = a0 + sweep * j / n
                out += P2(c.x + abs(r) * cos(t), c.y + abs(r) * sin(t))
            }
        }
        if (open && pts.isNotEmpty()) out += pts.last()
        return out
    }

    fun json(): JSONObject = JSONObject()
        .put("pts", JSONArray().apply { pts.forEach { put(JSONArray().put(it.x).put(it.y)) } })
        .apply {
            if (bulge.any { it != 0.0 }) put("bulge", JSONArray().apply { pts.indices.forEach { put(bulgeAt(it)) } })
            if (hole) put("hole", true)
            if (open) put("open", true)
        }

    companion object {
        /** Unit normal to the right of the direction a -> b. */
        fun right(a: P2, b: P2): P2 {
            val l = a.dist(b)
            return if (l == 0.0) P2(0.0, 0.0) else P2((b.y - a.y) / l, -(b.x - a.x) / l)
        }

        fun rect(a: P2, b: P2) = Loop(listOf(P2(min(a.x, b.x), min(a.y, b.y)), P2(max(a.x, b.x), min(a.y, b.y)),
            P2(max(a.x, b.x), max(a.y, b.y)), P2(min(a.x, b.x), max(a.y, b.y))))

        fun circle(c: P2, r: Double) = Loop(listOf(P2(c.x - r, c.y), P2(c.x + r, c.y)), listOf(1.0, 1.0))

        fun of(o: JSONObject): Loop? {
            val a = o.optJSONArray("pts") ?: return null
            val pts = (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let { p -> P2(p.optDouble(0), p.optDouble(1)) } }
            if (pts.size < 2) return null
            val b = o.optJSONArray("bulge")
            return Loop(pts, pts.indices.map { b?.optDouble(it, 0.0)?.takeIf { v -> v.isFinite() } ?: 0.0 }, o.optBoolean("hole"), o.optBoolean("open"))
        }
    }
}

/** A side of a sketch: outline [loop], from corner [i] to the next one. */
data class Side(val loop: Int, val i: Int) {
    fun json(): JSONArray = JSONArray().put(loop).put(i)

    companion object {
        fun of(a: JSONArray?): Side? = a?.takeIf { it.length() == 2 }?.let { Side(it.optInt(0), it.optInt(1)) }
    }
}

/**
 * A light constraint on sides: LENGTH (side [a] is [value] mm, or the slider [dim] when set; an arc's
 * corner-to-corner distance), EQUAL / PARALLEL (sides [a] and [b]), LEVEL / PLUMB (side [a]).
 */
data class Con(val kind: Kind, val a: Side, val b: Side? = null, val value: Double = 0.0, val dim: String? = null) {
    enum class Kind(val key: String) { LENGTH("length"), EQUAL("equal"), PARALLEL("parallel"), LEVEL("level"), PLUMB("plumb") }

    fun touches(s: Side) = a == s || b == s

    fun json(): JSONObject = JSONObject().put("kind", kind.key).put("a", a.json()).apply {
        b?.let { put("b", it.json()) }
        if (kind == Kind.LENGTH) put("value", value)
        dim?.let { put("dim", it) }
    }

    companion object {
        fun of(o: JSONObject): Con? {
            val kind = Kind.entries.firstOrNull { it.key == o.optString("kind") } ?: return null
            val a = Side.of(o.optJSONArray("a")) ?: return null
            val b = Side.of(o.optJSONArray("b"))
            if ((kind == Kind.EQUAL || kind == Kind.PARALLEL) && (b == null || b == a)) return null
            return Con(kind, a, b, o.optDouble("value", 0.0).takeIf { it.isFinite() } ?: 0.0, o.optString("dim").takeIf { it.isNotEmpty() })
        }
    }
}

/** A 2D drawing (outlines in mm) that a sketch shape extrudes or revolves, with light constraints on its sides. */
data class Sketch(val loops: List<Loop> = emptyList(), val cons: List<Con> = emptyList()) {

    fun json(): JSONObject = JSONObject().put("loops", JSONArray().apply { loops.forEach { put(it.json()) } })
        .apply { if (cons.isNotEmpty()) put("cons", JSONArray().apply { cons.forEach { put(it.json()) } }) }

    // --- Constraints ----------------------------------------------------------------------------

    private fun valid(s: Side) = s.loop in loops.indices && s.i in loops[s.loop].pts.indices
    private fun valid(c: Con) = valid(c.a) && c.b?.let(::valid) != false

    /** The constraints' targets, a LENGTH's slider value first. */
    private fun length(c: Con, dim: (String) -> Double?) = max(0.01, c.dim?.let(dim) ?: c.value)

    /**
     * The drawing moved so its constraints hold as far as they can: each constraint nudges its corners
     * in turn, round after round. Corners in [pinned] (outline to corner) stay put. [dim] gives a slider's value.
     */
    fun solve(dim: (String) -> Double? = { null }, pinned: Set<Pair<Int, Int>> = emptySet()): Sketch {
        val live = cons.filter(::valid)
        if (live.isEmpty()) return this
        val pts = loops.map { it.pts.toMutableList() }
        for (round in 0 until 2000) {
            var err = 0.0
            for (c in live) err = max(err, project(pts, c, dim, pinned))
            if (err < 1e-9) break
        }
        // Moved corners rounded to 1e-6 mm, so a solved 20 stays 20 and not 19.9999999997.
        fun r(v: Double) = Math.round(v * 1e6) / 1e6
        return copy(loops = loops.mapIndexed { i, l -> l.copy(pts = pts[i].mapIndexed { k, p -> if (p == l.pts[k]) p else P2(r(p.x), r(p.y)) }) })
    }

    /** Constraints that don't hold (to 0.001 mm), e.g. because they fight each other. */
    fun unmet(dim: (String) -> Double? = { null }): List<Con> {
        val pts = loops.map { it.pts.toMutableList() }
        return cons.filter { valid(it) && project(pts.map { l -> l.toMutableList() }, it, dim, emptySet()) > 1e-3 }
    }

    /** One step towards [c] on [pts]; returns how far off it was (mm, an angle as its offset along the side). */
    private fun project(pts: List<MutableList<P2>>, c: Con, dim: (String) -> Double?, pinned: Set<Pair<Int, Int>>): Double {
        fun ends(s: Side) = (s.loop to s.i) to (s.loop to (s.i + 1) % pts[s.loop].size)
        fun at(k: Pair<Int, Int>) = pts[k.first][k.second]
        fun put(k: Pair<Int, Int>, v: P2) { pts[k.first][k.second] = v }
        fun w(k: Pair<Int, Int>) = if (k in pinned) 0.0 else 1.0
        fun len(s: Side) = ends(s).let { (p, q) -> at(p).dist(at(q)) }
        /** A side can move unless both its corners are pinned. */
        fun free(s: Side) = ends(s).let { (p, q) -> if (w(p) + w(q) == 0.0) 0.0 else 1.0 }
        fun stretch(s: Side, to: Double) {
            val (p, q) = ends(s)
            val wp = w(p)
            val wq = w(q)
            if (wp + wq == 0.0) return
            val a = at(p)
            val b = at(q)
            val l = a.dist(b)
            val u = if (l < 1e-9) P2(1.0, 0.0) else (b - a) * (1 / l)
            val off = l - to
            put(p, a + u * (off * wp / (wp + wq)))
            put(q, b - u * (off * wq / (wp + wq)))
        }
        fun angle(s: Side) = ends(s).let { (p, q) -> atan2(at(q).y - at(p).y, at(q).x - at(p).x) }
        fun turn(s: Side, by: Double) {
            if (by == 0.0) return
            val (p, q) = ends(s)
            val pivot = when { w(p) == 0.0 -> at(p); w(q) == 0.0 -> at(q); else -> (at(p) + at(q)) * 0.5 }
            val cs = cos(by)
            val sn = sin(by)
            for (k in listOf(p, q)) if (w(k) != 0.0) {
                val v = at(k) - pivot
                put(k, pivot + P2(v.x * cs - v.y * sn, v.x * sn + v.y * cs))
            }
        }
        /** LEVEL (y) or PLUMB (x): both corners to the pinned one's value, else to their middle. */
        fun line(s: Side, y: Boolean): Double {
            val (p, q) = ends(s)
            val a = at(p)
            val b = at(q)
            val off = abs(if (y) a.y - b.y else a.x - b.x)
            val v = when {
                w(p) + w(q) == 0.0 -> return off
                w(p) == 0.0 -> if (y) a.y else a.x
                w(q) == 0.0 -> if (y) b.y else b.x
                else -> if (y) (a.y + b.y) / 2 else (a.x + b.x) / 2
            }
            put(p, if (y) a.copy(y = v) else a.copy(x = v))
            put(q, if (y) b.copy(y = v) else b.copy(x = v))
            return off
        }
        return when (c.kind) {
            Con.Kind.LENGTH -> {
                val to = length(c, dim)
                abs(len(c.a) - to).also { stretch(c.a, to) }
            }
            Con.Kind.EQUAL -> {
                val b = c.b ?: return 0.0
                val la = len(c.a)
                val lb = len(b)
                val wa = free(c.a)
                val wb = free(b)
                if (wa + wb == 0.0) return abs(la - lb)
                // A fixed side keeps its length, the free one follows; two free sides meet halfway.
                val to = (wb * la + wa * lb) / (wa + wb)
                stretch(c.a, to)
                stretch(b, to)
                abs(la - lb)
            }
            Con.Kind.PARALLEL -> {
                val b = c.b ?: return 0.0
                var diff = angle(b) - angle(c.a)
                diff -= Math.PI * kotlin.math.round(diff / Math.PI)   // either direction is parallel
                val wa = free(c.a)
                val wb = free(b)
                val off = abs(sin(diff)) * max(len(c.a), len(b))
                if (wa + wb == 0.0) return off
                turn(c.a, diff * wa / (wa + wb))
                turn(b, -diff * wb / (wa + wb))
                off
            }
            Con.Kind.LEVEL -> line(c.a, true)
            Con.Kind.PLUMB -> line(c.a, false)
        }
    }

    /** Constraints after outline [li]'s sides were renumbered by [f] (null drops the constraints on that side). */
    private fun remap(li: Int, f: (Int) -> Int?): List<Con> = cons.mapNotNull { c ->
        fun m(s: Side) = if (s.loop != li) s else f(s.i)?.let { Side(li, it) }
        val a = m(c.a) ?: return@mapNotNull null
        val b = c.b?.let { m(it) ?: return@mapNotNull null }
        c.copy(a = a, b = b)
    }

    /** Without outline [li] (and the constraints on it). */
    fun withoutLoop(li: Int) = Sketch(loops.filterIndexed { i, _ -> i != li }, cons.mapNotNull { c ->
        fun m(s: Side) = when { s.loop == li -> null; s.loop > li -> s.copy(loop = s.loop - 1); else -> s }
        val a = m(c.a) ?: return@mapNotNull null
        val b = c.b?.let { m(it) ?: return@mapNotNull null }
        c.copy(a = a, b = b)
    })

    /** Without corner [pi] of outline [li]: its two sides become one, their constraints dropped. */
    fun withoutPoint(li: Int, pi: Int): Sketch {
        val n = loops[li].pts.size
        val next = copy(loops = loops.mapIndexed { i, l ->
            if (i == li) l.copy(pts = l.pts.filterIndexed { k, _ -> k != pi }, bulge = l.bulge.filterIndexed { k, _ -> k != pi }) else l
        })
        return next.copy(cons = remap(li) { s -> when { s == pi || s == (pi - 1 + n) % n -> null; s > pi -> s - 1; else -> s } })
    }

    /** [l] in place of outline [li] after side [side] was split in two (the constraints on that side dropped). */
    fun withSplit(li: Int, side: Int, l: Loop) = copy(loops = loops.mapIndexed { i, o -> if (i == li) l else o },
        cons = remap(li) { s -> when { s == side -> null; s > side -> s + 1; else -> s } })

    /** With outline [l] added; a rectangle's sides stay level and plumb. */
    fun plus(l: Loop, rect: Boolean = false): Sketch {
        val li = loops.size
        return Sketch(loops + l, cons + if (rect) rectCons(li) else emptyList())
    }

    /** Bounds of every outline (arcs included); null when empty. */
    fun bounds(): Pair<P2, P2>? {
        val all = loops.flatMap { it.polygon() }
        if (all.isEmpty()) return null
        return P2(all.minOf { it.x }, all.minOf { it.y }) to P2(all.maxOf { it.x }, all.maxOf { it.y })
    }

    /**
     * OpenSCAD 2D code for the drawing moved by [shift]: solid outlines joined, hole outlines cut.
     * Outlines are written as polygons, arcs already split.
     */
    fun toScad(shift: P2 = P2(0.0, 0.0)): String {
        fun poly(l: Loop) = "polygon(" + l.polygon().joinToString(", ", "[", "]") { "[${ShapeDesign.n(it.x + shift.x)}, ${ShapeDesign.n(it.y + shift.y)}]" } + ");"
        val solids = loops.filter { !it.hole && it.pts.size >= 2 }.map(::poly)
        val holes = loops.filter { it.hole && it.pts.size >= 2 }.map(::poly)
        if (solids.isEmpty()) return "square(0.01);"
        val joined = if (solids.size == 1) solids[0] else "union() { ${solids.joinToString(" ")} }"
        return if (holes.isEmpty()) joined else "difference() { $joined ${holes.joinToString(" ")} }"
    }

    companion object {
        /** The drawing a new sketch shape starts with: a 20 x 20 mm square. */
        val START = Sketch().plus(Loop.rect(P2(-10.0, -10.0), P2(10.0, 10.0)), rect = true)

        /** [Loop.rect]'s sides: bottom and top level, right and left plumb. */
        fun rectCons(li: Int) = listOf(Con(Con.Kind.LEVEL, Side(li, 0)), Con(Con.Kind.PLUMB, Side(li, 1)),
            Con(Con.Kind.LEVEL, Side(li, 2)), Con(Con.Kind.PLUMB, Side(li, 3)))

        /** The signed area of closed outline [p] (positive when counter-clockwise). */
        fun area(p: List<P2>): Double = p.indices.sumOf { i -> val a = p[i]; val b = p[(i + 1) % p.size]; a.x * b.y - b.x * a.y } / 2

        /**
         * Closed outline [p] as [n] points evenly spaced along it, in its own direction, starting where the ray from its
         * centre (vertex average) towards +x leaves it, so two outlines resampled alike line up for a loft.
         */
        fun resample(p: List<P2>, n: Int): List<P2> {
            val c = P2(p.sumOf { it.x } / p.size, p.sumOf { it.y } / p.size)
            // Start on the side the +x ray crosses, at the crossing (the farthest one if it crosses more than once).
            var start = 0
            var startT = 0.0
            var best = Double.NEGATIVE_INFINITY
            for (i in p.indices) {
                val a = p[i] - c
                val b = p[(i + 1) % p.size] - c
                if ((a.y < 0) == (b.y < 0) || a.y == b.y) continue
                val t = -a.y / (b.y - a.y)
                val x = a.x + (b.x - a.x) * t
                if (x > best) { best = x; start = i; startT = t }
            }
            val ring = List(p.size + 1) { p[(start + it) % p.size] }
            val first = ring[0] + (ring[1] - ring[0]) * startT
            val path = listOf(first) + ring.subList(1, ring.size) + listOf(first)
            val len = path.zipWithNext { a, b -> a.dist(b) }
            val total = len.sum()
            if (total == 0.0) return List(n) { first }
            val out = ArrayList<P2>(n)
            var seg = 0
            var done = 0.0
            for (k in 0 until n) {
                val at = total * k / n
                while (seg < len.size - 1 && done + len[seg] < at) { done += len[seg]; seg++ }
                val u = if (len[seg] == 0.0) 0.0 else (at - done) / len[seg]
                out += path[seg] + (path[seg + 1] - path[seg]) * u
            }
            return out
        }

        fun of(o: JSONObject?): Sketch? = o?.optJSONArray("loops")?.let { a ->
            val c = o.optJSONArray("cons")
            Sketch((0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Loop::of) },
                (0 until (c?.length() ?: 0)).mapNotNull { c?.optJSONObject(it)?.let(Con::of) })
        }
    }
}
