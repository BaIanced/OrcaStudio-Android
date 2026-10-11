package app.orcaandroid.scad

import androidx.annotation.StringRes
import app.orcaandroid.R

/**
 * Built-in starting points for the template gallery. Each is commented for people new to OpenSCAD
 * and has Customizer sliders, so it can be adapted without touching the code.
 */
object ScadExamples {
    data class Example(@StringRes val title: Int, @StringRes val description: Int, val file: String, val source: String)

    val BLANK = Example(R.string.scad_tpl_blank, R.string.scad_tpl_blank_desc, "my_design.scad", """
        // Your design. Drag the sliders in the Customizer tab or change the
        // numbers below: the preview updates as you go.
        // "Insert" adds shapes, moves, cut-outs and repeats at the cursor.
        // All sizes are in millimetres.

        /* [Size] */
        // Width (mm)
        width = 30; // [5:1:200]
        // Depth (mm)
        depth = 20; // [5:1:200]
        // Height (mm)
        height = 10; // [1:1:100]

        cube([width, depth, height]);
    """.trimIndent() + "\n")

    val BOX = Example(R.string.scad_tpl_box, R.string.scad_tpl_box_desc, "box.scad", """
        // A parametric box. Change the values in the Customizer or the code,
        // then "Add to plate" (F6) to slice it.

        /* [Size] */
        // Outer width (mm)
        width = 60; // [10:1:200]
        // Outer depth (mm)
        depth = 40; // [10:1:200]
        // Outer height (mm)
        height = 30; // [5:1:150]

        /* [Walls] */
        // Wall thickness (mm)
        wall = 2; // [0.8:0.2:6]
        // Corner radius (mm)
        radius = 4; // [0:0.5:20]
        // Hollow inside
        hollow = true;

        /* [Hidden] */
        ${'$'}fn = 48;

        module rounded_box(size, r) {
            if (r <= 0) cube(size);
            else hull() for (x = [r, size.x - r], y = [r, size.y - r])
                translate([x, y, 0]) cylinder(r = r, h = size.z);
        }

        difference() {
            rounded_box([width, depth, height], radius);
            if (hollow)
                translate([wall, wall, wall])
                    rounded_box([width - 2 * wall, depth - 2 * wall, height], max(radius - wall, 0));
        }
    """.trimIndent() + "\n")

    val LID_BOX = Example(R.string.scad_tpl_lid_box, R.string.scad_tpl_lid_box_desc, "box_with_lid.scad", """
        // A box and a lid that fits over its top edge, printed side by side.

        /* [Box] */
        // Outer width (mm)
        width = 60; // [20:1:200]
        // Outer depth (mm)
        depth = 40; // [20:1:200]
        // Outer height (mm)
        height = 30; // [10:1:150]
        // Wall thickness (mm)
        wall = 2; // [1.2:0.2:5]
        // Corner radius (mm)
        radius = 4; // [0:0.5:15]

        /* [Lid] */
        // Height of the lid's rim (mm)
        lid_rim = 6; // [3:1:20]
        // Gap between box and lid: larger = looser fit (mm)
        clearance = 0.3; // [0:0.05:1]

        /* [Hidden] */
        ${'$'}fn = 48;

        // A box with rounded vertical edges; radius 0 gives sharp corners.
        module rounded(size, r) {
            r2 = min(r, size.x / 2 - 0.01, size.y / 2 - 0.01);
            if (r2 <= 0) cube(size);
            else hull() for (x = [r2, size.x - r2], y = [r2, size.y - r2])
                translate([x, y, 0]) cylinder(r = r2, h = size.z);
        }

        // The box: a solid block minus a smaller block (the inside).
        difference() {
            rounded([width, depth, height], radius);
            translate([wall, wall, wall])
                rounded([width - 2 * wall, depth - 2 * wall, height], radius - wall);
        }

        // The lid, upside down beside the box.
        gap = wall + clearance;
        translate([width + 10, -gap, 0]) difference() {
            rounded([width + 2 * gap, depth + 2 * gap, lid_rim + wall], radius + gap);
            translate([wall, wall, wall])
                rounded([width + 2 * clearance, depth + 2 * clearance, lid_rim + 1], radius + clearance);
        }
    """.trimIndent() + "\n")

    val WASHER = Example(R.string.scad_tpl_washer, R.string.scad_tpl_washer_desc, "spacer.scad", """
        // A spacer or washer: a short tube with a hole for a screw.

        /* [Size] */
        // Outside diameter (mm)
        outer = 20; // [4:0.5:100]
        // Hole diameter (mm): add 0.2-0.4 mm to the screw for a loose fit
        hole = 5.4; // [1:0.1:80]
        // Height (mm)
        height = 5; // [0.4:0.2:50]
        // Outside shape
        sides = 96; // [96:Round, 6:Hexagon, 8:Octagon, 4:Square]

        /* [Hidden] */
        ${'$'}fn = 96;

        difference() {
            cylinder(h = height, d = outer, ${'$'}fn = sides);
            // The hole is a bit taller than the part so it cuts all the way through.
            translate([0, 0, -1]) cylinder(h = height + 2, d = min(hole, outer - 1));
        }
    """.trimIndent() + "\n")

    val HOOK = Example(R.string.scad_tpl_hook, R.string.scad_tpl_hook_desc, "wall_hook.scad", """
        // A wall hook with two screw holes. It prints lying on its side
        // (as in the preview), which makes the hook strong.

        /* [Hook] */
        // Width (mm)
        width = 20; // [10:1:60]
        // Height of the back plate (mm)
        plate_height = 60; // [30:1:150]
        // How far the hook reaches out (mm)
        reach = 30; // [10:1:80]
        // Height of the tip (mm)
        tip = 12; // [4:1:40]
        // Thickness (mm)
        thickness = 5; // [3:0.5:10]

        /* [Screws] */
        // Screw hole diameter (mm)
        screw = 4; // [2:0.5:8]
        // Countersunk screw heads
        countersink = true;

        /* [Hidden] */
        ${'$'}fn = 48;

        // The side view of the hook, drawn in 2D.
        module profile() {
            square([thickness, plate_height]);                          // back plate
            square([reach, thickness]);                                 // arm
            translate([reach - thickness, 0]) square([thickness, tip]); // tip
        }

        difference() {
            linear_extrude(width) profile();
            // Screw holes through the back plate.
            for (y = [plate_height / 2, plate_height - 10])
                translate([-1, y, width / 2]) rotate([0, 90, 0]) {
                    cylinder(h = thickness + 2, d = screw);
                    if (countersink)
                        translate([0, 0, thickness + 1 - screw / 2])
                            cylinder(h = screw / 2 + 0.01, d1 = screw, d2 = screw * 2);
                }
        }
    """.trimIndent() + "\n")

    val STAND = Example(R.string.scad_tpl_stand, R.string.scad_tpl_stand_desc, "phone_stand.scad", """
        // A phone or tablet stand. It prints lying on its side (as in the
        // preview); the width is the height of the print.

        /* [Stand] */
        // Width (mm)
        width = 70; // [30:1:200]
        // How far the phone leans back (degrees)
        angle = 25; // [5:1:45]
        // Thickness of the phone with its case (mm)
        phone = 12; // [5:0.5:30]
        // Height of the back support (mm)
        back = 80; // [40:1:150]
        // Height of the lip in front of the phone (mm)
        lip = 10; // [4:1:30]
        // Wall thickness (mm)
        wall = 4; // [2:0.5:8]

        /* [Hidden] */
        base = wall + phone + back * sin(angle) + wall;

        // The leaning support, starting behind the phone slot.
        module support() translate([wall + phone, 0]) rotate(-angle) square([wall, back]);

        linear_extrude(width) intersection() {
            union() {
                square([base, wall]);   // foot
                square([wall, lip]);    // front lip
                support();
                // Brace from the middle of the support to the back of the foot.
                hull() {
                    translate([wall + phone, 0]) rotate(-angle) translate([0, back / 2]) square([wall, wall]);
                    translate([base - wall, 0]) square([wall, wall]);
                }
            }
            // Cut off what reaches below the foot, so the stand sits flat.
            square([base + back, back + lip]);
        }
    """.trimIndent() + "\n")

    val GEAR = Example(R.string.scad_tpl_gear, R.string.scad_tpl_gear_desc, "gear.scad", """
        // A spur gear from the bundled MCAD library.
        use <MCAD/involute_gears.scad>

        /* [Gear] */
        // Number of teeth
        teeth = 24; // [8:1:80]
        // Tooth size: mm along the pitch circle per tooth (diameter = teeth * pitch / pi)
        pitch = 5; // [2:0.5:12]
        // Thickness (mm)
        thickness = 6; // [2:0.5:20]
        // Bore diameter (mm)
        bore = 5; // [0:0.5:20]

        gear(number_of_teeth = teeth, circular_pitch = pitch,
             gear_thickness = thickness, rim_thickness = thickness, hub_thickness = thickness,
             bore_diameter = bore);
    """.trimIndent() + "\n")

    val TAG = Example(R.string.scad_tpl_tag, R.string.scad_tpl_tag_desc, "name_tag.scad", """
        // A name tag: raised text on a plate that follows its outline.

        /* [Text] */
        label = "OrcaStudio";
        // Text height (mm)
        size = 10; // [4:1:30]
        font = "Liberation Sans"; // [Liberation Sans, Liberation Serif, Liberation Mono]
        bold = true;

        /* [Plate] */
        // Plate thickness (mm)
        thickness = 2; // [1:0.5:6]
        // Text height above the plate (mm)
        emboss = 1.2; // [0.4:0.2:4]
        // Border around the text (mm)
        margin = 4; // [1:1:15]

        /* [Hidden] */
        ${'$'}fn = 32;
        face = str(font, bold ? ":style=Bold" : "");

        module label_2d() text(label, size = size, font = face, halign = "center", valign = "center");

        linear_extrude(thickness) offset(r = margin) hull() label_2d();
        translate([0, 0, thickness]) linear_extrude(emboss) label_2d();
    """.trimIndent() + "\n")

    val ALL = listOf(BLANK, BOX, LID_BOX, WASHER, HOOK, STAND, TAG, GEAR)
}
