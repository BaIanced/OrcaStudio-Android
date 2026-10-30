package app.orcaandroid.scad

/**
 * Ready-made parts for the shape builder (CAD step 7, asset library): each is a plain group of builder shapes, so it stays
 * fully editable once added (resize a wheel's tire, move a figure's arm, ungroup it).
 */
enum class Part {
    /** A 40 mm wheel lying flat (prints without support): tire, hub, 5 spokes, a 4 mm axle hole. */
    WHEEL,
    /** A 38 mm standing figure: legs, body, arms, head. */
    FIGURE;

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
    }
}
