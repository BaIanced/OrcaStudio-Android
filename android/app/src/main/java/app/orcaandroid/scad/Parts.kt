package app.orcaandroid.scad

/**
 * Ready-made parts for the shape builder (CAD step 7, asset library): each is a plain group of builder shapes, so it stays
 * fully editable once added (resize a wheel's tire, move a figure's arm, ungroup it).
 */
enum class Part {
    /** A 40 mm wheel lying flat (prints without support): tire, hub, 5 spokes, a 4 mm axle hole. */
    WHEEL,
    /** A 38 mm standing figure: legs, body, arms, head. */
    FIGURE,
    /** Two 26 mm plates side by side: one with a 6 mm peg, one with a 6.4 mm hole (0.2 mm clearance all round) so they press together. */
    PEG_PAIR,
    /** A print-in-place pin hinge (standing, axis up): two leaves, three 8 mm knuckles on a 4 mm pin with 0.4 mm gaps; the middle knuckle turns freely. */
    HINGE;

    /** The part as one group named [name] at the origin, its shapes numbered from [firstId] (the group first). */
    fun build(firstId: Int, name: String): Shape {
        var next = firstId
        fun numbered(s: Shape): Shape = s.copy(id = next++, children = s.children.map(::numbered))
        return numbered(Shape(0, Shape.Kind.GROUP, name, D3(), children = shapes()))
    }

    private fun shapes(): List<Shape> = when (this) {
        WHEEL -> listOf(
            Shape(0, Shape.Kind.TUBE, "Tire", D3(40.0, 40.0, 10.0), extra = mapOf(Shape.WALL to 5.0)),
            Shape(0, Shape.Kind.CYLINDER, "Hub", D3(12.0, 12.0, 10.0)),
            Shape(0, Shape.Kind.GROUP, "Spokes", D3(), D3(0.0, 0.0, 2.0),
                repeat = Repeat(circular = true, count = 5, step = D3(), angle = 360.0, center = D3()),
                children = listOf(Shape(0, Shape.Kind.BOX, "Spoke", D3(11.0, 3.0, 6.0), D3(10.0, 0.0, 0.0)))),
            Shape(0, Shape.Kind.CYLINDER, "Axle hole", D3(4.0, 4.0, 10.0), hole = true),
        )
        FIGURE -> listOf(
            Shape(0, Shape.Kind.CYLINDER, "Left leg", D3(6.0, 6.0, 14.0), D3(-3.5, 0.0, 0.0)),
            Shape(0, Shape.Kind.CYLINDER, "Right leg", D3(6.0, 6.0, 14.0), D3(3.5, 0.0, 0.0)),
            Shape(0, Shape.Kind.ROUNDED_BOX, "Body", D3(16.0, 9.0, 14.0), D3(0.0, 0.0, 13.0), extra = mapOf(Shape.RADIUS to 3.0)),
            Shape(0, Shape.Kind.CYLINDER, "Left arm", D3(4.5, 4.5, 12.0), D3(-9.5, 0.0, 14.0), D3(0.0, 10.0, 0.0)),
            Shape(0, Shape.Kind.CYLINDER, "Right arm", D3(4.5, 4.5, 12.0), D3(9.5, 0.0, 14.0), D3(0.0, -10.0, 0.0)),
            Shape(0, Shape.Kind.SPHERE, "Head", D3(12.0, 12.0, 12.0), D3(0.0, 0.0, 26.0)),
        )
        PEG_PAIR -> listOf(
            Shape(0, Shape.Kind.BOX, "Peg plate", D3(26.0, 16.0, 3.0), D3(-15.0, 0.0, 0.0)),
            Shape(0, Shape.Kind.CYLINDER, "Peg", D3(6.0, 6.0, 8.0), D3(-15.0, 0.0, 3.0)),
            Shape(0, Shape.Kind.BOX, "Hole plate", D3(26.0, 16.0, 3.0), D3(15.0, 0.0, 0.0)),
            Shape(0, Shape.Kind.CYLINDER, "Hole", D3(6.4, 6.4, 3.0), D3(15.0, 0.0, 0.0), hole = true),
        )
        HINGE -> listOf(
            Shape(0, Shape.Kind.CYLINDER, "Pin", D3(4.0, 4.0, 24.8)),
            Shape(0, Shape.Kind.CYLINDER, "Knuckle low", D3(8.0, 8.0, 8.0)),
            Shape(0, Shape.Kind.CYLINDER, "Knuckle high", D3(8.0, 8.0, 8.0), D3(0.0, 0.0, 16.8)),
            Shape(0, Shape.Kind.TUBE, "Knuckle loose", D3(8.0, 8.0, 8.0), D3(0.0, 0.0, 8.4), extra = mapOf(Shape.WALL to 1.5)),
            Shape(0, Shape.Kind.BOX, "Leaf low", D3(20.0, 3.0, 8.0), D3(-12.0, 0.0, 0.0)),
            Shape(0, Shape.Kind.BOX, "Leaf high", D3(20.0, 3.0, 8.0), D3(-12.0, 0.0, 16.8)),
            Shape(0, Shape.Kind.BOX, "Leaf loose", D3(20.0, 3.0, 8.0), D3(12.0, 0.0, 8.4)),
        )
    }
}

/** Parts the user saved from a group ("Save as part"): the group plus the sliders its fields are bound to, as shape-design JSON. */
object SavedParts {
    /** Slider names [g] and its children use (bound fields, sketch constraints). */
    fun dimsUsed(g: Shape): Set<String> = g.all().flatMap { s ->
        s.bind.values.asSequence() + (s.sketch?.cons?.mapNotNull { it.dim }.orEmpty())
    }.toSet()

    /** [group] of [d] at the origin as a standalone design with just the sliders it uses. */
    fun encode(d: ShapeDesign, group: Shape): String {
        val used = dimsUsed(group)
        val kids = group.all().toList()
        return ShapeDesign(listOf(group.copy(pos = D3())), d.dims.filter { it.name in used }, (kids.maxOfOrNull { it.id } ?: 0) + 1).encode()
    }

    /**
     * The saved group renumbered from [firstId] and named [name], with its sliders; a slider whose name is [taken] is
     * renamed (bindings follow) so a second copy gets its own. Null when [json] holds no group.
     */
    fun place(json: String, firstId: Int, name: String, taken: (String) -> Boolean): Pair<Shape, List<Dim>>? {
        val saved = ShapeDesign.decode(json) ?: return null
        val g = saved.shapes.singleOrNull()?.takeIf { it.isGroup } ?: return null
        val names = HashSet<String>()
        val renamed = saved.dims.associate { dm -> dm.name to Dim.ident(dm.name) { taken(it) || it in names }.also { names += it } }
        var next = firstId
        fun fix(s: Shape): Shape = s.copy(
            id = next++,
            bind = s.bind.mapValues { renamed[it.value] ?: it.value },
            sketch = s.sketch?.let { sk -> sk.copy(cons = sk.cons.map { c -> c.copy(dim = c.dim?.let { renamed[it] ?: it }) }) },
            children = s.children.map(::fix),
        )
        return fix(g.copy(name = name)) to saved.dims.map { it.copy(name = renamed.getValue(it.name)) }
    }
}
