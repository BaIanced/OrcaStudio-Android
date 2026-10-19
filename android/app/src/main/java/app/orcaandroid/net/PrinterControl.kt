package app.orcaandroid.net

import org.json.JSONObject

/**
 * One manual command of the Device tab, as the desktop sends it (DeviceManager.cpp command_set_bed,
 * command_set_nozzle, command_set_printing_speed, command_axis_control, command_go_home; DevFan.cpp
 * command_control_fan; DevLampCtrl.cpp command_set_chamber_light). Exactly one of [print] / [system] is set.
 */
class PrinterControl private constructor(val print: JSONObject? = null, val system: JSONObject? = null) {
    companion object {
        const val SPEED_SILENT = 1
        const val SPEED_STANDARD = 2
        const val SPEED_SPORT = 3
        const val SPEED_LUDICROUS = 4

        private fun gcode(lines: String) = PrinterControl(print = JSONObject().put("command", "gcode_line").put("param", lines))

        fun nozzleTemp(celsius: Int) = gcode("M104 S${celsius.coerceIn(0, 350)}\n")

        fun bedTemp(celsius: Int) = gcode("M140 S${celsius.coerceIn(0, 120)}\n")

        fun speed(level: Int) = PrinterControl(print = JSONObject().put("command", "print_speed").put("param", level.coerceIn(1, 4).toString()))

        /** The part cooling fan (M106 P1), [percent] of full speed. */
        fun partFan(percent: Int) = gcode("M106 P1 S${(percent.coerceIn(0, 100) * 255 + 50) / 100}\n")

        fun light(on: Boolean) = PrinterControl(system = JSONObject()
            .put("command", "ledctrl").put("led_node", "chamber_light").put("led_mode", if (on) "on" else "off")
            .put("led_on_time", 500).put("led_off_time", 500).put("loop_times", 0).put("interval_time", 0))

        /**
         * Moves [axis] (X, Y or Z) by [mm] relative to now. Bed-slinger printers (i3 arch: A1 series) move the
         * bed for Y and Z, so the desktop reverses those; [coreXy] says which kind the printer is.
         */
        fun jog(axis: Char, mm: Double, coreXy: Boolean): PrinterControl {
            val value = if (!coreXy && (axis == 'Y' || axis == 'Z')) -mm else mm
            val feed = if (axis == 'Z') 900 else 3000
            return gcode("M211 S \nM211 X1 Y1 Z1\nM1002 push_ref_mode\nG91 \nG1 $axis${"%.1f".format(java.util.Locale.ROOT, value)} F$feed\nM1002 pop_ref_mode\nM211 R\n")
        }

        fun home() = gcode("G28 \n")

        /**
         * Pushes ([mm] > 0) or pulls back the filament; the printer refuses it while the nozzle is cold. Wrapped like
         * [jog] (the desktop's axis move, DeviceManager.cpp command_axis_control), so push/pop_ref_mode puts the
         * printer's E mode back afterwards. F150 = 2.5 mm/s of 1.75 mm filament, ~6 mm³/s: the desktop's F900 is ~36,
         * more than the hotend melts near the 170 °C unlock.
         */
        fun extrude(mm: Double) = gcode("M211 S \nM211 X1 Y1 Z1\nM1002 push_ref_mode\nM83 \n" +
            "G1 E${"%.1f".format(java.util.Locale.ROOT, mm)} F150\nM1002 pop_ref_mode\nM211 R\n")
    }
}
