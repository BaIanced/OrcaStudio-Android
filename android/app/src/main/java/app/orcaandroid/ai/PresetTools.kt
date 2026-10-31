package app.orcaandroid.ai

import app.orcaandroid.R
import app.orcaandroid.ai.McpTools.Companion.obj
import app.orcaandroid.ai.McpTools.Companion.p
import app.orcaandroid.core.OptionDef
import app.orcaandroid.core.PresetType
import app.orcaandroid.ui.PresetController
import app.orcaandroid.ui.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings tools for [McpTools]: read the printer / process / filament presets, compare them with
 * their system parent, and change settings as unsaved edits (shown as modified in the app, which the
 * user can reset). Saving and deleting presets wait for the user's Allow on screen ([confirm]).
 */
internal class PresetTools(
    private val store: Store,
    private val presets: PresetController,
    private val confirm: suspend (String) -> Boolean,
) {
    private val type = p("string", "printer, process or filament")
    private val slot = p("integer", "Filament slot (1 = first, as plate_describe lists them); default 1")

    val tools = listOf(
        McpTools.Tool("preset_list", "Read: the presets of one type that fit the chosen printer, marking the selected one(s), " +
            "the user's own presets and the system preset each one is based on.",
            obj(listOf("type"), "type" to type, "filter" to p("string", "Only names containing this text"))) { list(it) },
        McpTools.Tool("preset_get", "Read: a preset's settings. By default only what differs from its system parent plus the " +
            "unsaved edits (the selected preset); keys picks settings, all lists every setting (long).",
            obj(listOf("type"), "type" to type, "name" to p("string", "Preset name; default the selected one"), "slot" to slot,
                "keys" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Setting keys"),
                "all" to p("boolean", "true = every setting"))) { get(it) },
        McpTools.Tool("setting_find", "Read: finds settings by words (key, label, category, help text), with their key, current " +
            "value, unit, range or choices and help. Use it to learn the key and value form before settings_edit.",
            obj(listOf("type", "query"), "type" to type, "query" to p("string", "Words to look for, e.g. \"infill density\""))) { find(it) },
        McpTools.Tool("settings_edit", "Edit: changes settings of the selected preset as unsaved edits (they show as modified in " +
            "the app and apply to the next slice; the user can reset them). null resets one to the preset's value. Values take " +
            "the text form preset_get shows (numbers, 15%, 1 / 0 for on / off, enum values, lists comma separated). Selecting " +
            "another preset drops the edits; preset_save keeps them. Returns all unsaved edits of that preset.",
            obj(listOf("type", "values"), "type" to type, "slot" to slot,
                "values" to p("object", "{key: value or null, ...}; filament only: slot_colour \"#RRGGBB\" sets the slot colour shown in the app"))) { edit(it) },
        McpTools.Tool("preset_select", "Edit: selects another process preset, or the filament preset of a slot (not the " +
            "printer: ask the user to pick that in the app). Fails when the current one has unsaved edits unless discard_edits.",
            obj(listOf("type", "name"), "type" to p("string", "process or filament"), "name" to p("string", "Preset name from preset_list"),
                "slot" to slot, "discard_edits" to p("boolean", "true = drop the current preset's unsaved edits"))) { select(it) },
        McpTools.Tool("filament_slots", "Edit: changes how many filament slots the print has (a new slot copies the last slot's preset " +
            "with the next colour; recolour or reassign with settings_edit slot_colour / preset_select), or removes one slot " +
            "(later slots move up; objects painted with it keep the number). Clears slice results. Give count or remove.",
            obj(emptyList(), "count" to p("integer", "Total slots wanted, at least 1"),
                "remove" to p("integer", "Slot (1 = first) to remove"))) { slots(it) },
        McpTools.Tool("preset_save", "Confirm: saves the selected preset with its unsaved edits as the user's preset name (a " +
            "user preset of that name is replaced) and selects it. Waits up to 60 s for the user's Allow in the app.",
            obj(listOf("type", "name"), "type" to type, "name" to p("string", "Name of the user preset"), "slot" to slot)) { save(it) },
        McpTools.Tool("preset_delete", "Confirm: deletes one of the user's own presets that isn't selected anywhere. Waits up to " +
            "60 s for the user's Allow in the app; can't be undone.",
            obj(listOf("type", "name"), "type" to type, "name" to p("string", "User preset name"))) { delete(it) },
    )

    private class Ref(val name: String, val system: Boolean, val base: String, val info: String)

    private fun JSONObject.type(): PresetType = when (optString("type")) {
        "printer" -> PresetType.PRINTER
        "process", "print" -> PresetType.PRINT
        "filament" -> PresetType.FILAMENT
        else -> throw McpTools.ToolError("type must be printer, process or filament")
    }

    private fun label(type: PresetType) = if (type == PresetType.PRINT) "process" else type.id

    /** The 0-based filament slot of [args] ("slot", 1-based). */
    private fun JSONObject.slot(): Int {
        val i = optInt("slot", 1) - 1
        if (store.value.filaments.getOrNull(i) == null) throw McpTools.ToolError("No filament slot ${i + 1}")
        return i
    }

    private fun refs(type: PresetType): List<Ref> {
        val s = store.value
        return when (type) {
            PresetType.PRINTER -> s.visiblePrinters.map { Ref(it.name, it.system, it.base, "${it.model} ${it.nozzle} mm") }
            PresetType.PRINT -> s.setup?.prints.orEmpty().map { Ref(it.name, it.system, it.base, "") }
            PresetType.FILAMENT -> s.setup?.filaments.orEmpty().map { Ref(it.name, it.system, it.base, listOf(it.brandName, it.type).filter(String::isNotEmpty).joinToString(" ")) }
        }
    }

    /** The selected preset of [type] (filament: of slot [slot]) and its unsaved edits. */
    private fun selected(type: PresetType, slot: Int): Pair<String, Map<String, String>> {
        val s = store.value
        return when (type) {
            PresetType.FILAMENT -> s.filaments[slot].let { it.preset to it.overrides }
            else -> (s.presetName(type) ?: throw McpTools.ToolError("No ${label(type)} preset selected")) to s.overrides[type].orEmpty()
        }
    }

    private fun list(args: JSONObject): String {
        val type = args.type()
        val filter = args.optString("filter")
        val s = store.value
        val all = refs(type).filter { filter.isEmpty() || it.name.contains(filter, ignoreCase = true) }
        val shown = all.take(LIST_LIMIT)
        return buildString {
            appendLine("${label(type).replaceFirstChar(Char::uppercase)} presets for printer \"${s.printer}\" (* = selected):")
            for (r in shown) {
                val slots = if (type == PresetType.FILAMENT) s.filaments.indices.filter { s.filaments[it].preset == r.name }.map { it + 1 } else emptyList()
                val sel = slots.isNotEmpty() || (type != PresetType.FILAMENT && s.presetName(type) == r.name)
                append(if (sel) "* " else "  ").append(r.name)
                val notes = buildList {
                    if (r.info.isNotEmpty()) add(r.info)
                    if (!r.system) add(if (r.base.isEmpty()) "user preset, no system parent" else "user preset based on \"${r.base}\"")
                    if (slots.isNotEmpty()) add("slot ${slots.joinToString()}")
                }
                if (notes.isNotEmpty()) append(" (").append(notes.joinToString("; ")).append(")")
                appendLine()
            }
            if (all.size > shown.size) appendLine("... ${all.size - shown.size} more; narrow it with filter.")
            if (all.isEmpty()) appendLine("(none)")
        }
    }

    private suspend fun get(args: JSONObject): String {
        val type = args.type()
        val slot = if (type == PresetType.FILAMENT) args.slot() else 0
        val (current, currentEdits) = selected(type, slot)
        val name = args.optString("name").ifEmpty { current }
        val ref = refs(type).firstOrNull { it.name == name }
            ?: store.value.printers.firstOrNull { it.name == name }?.let { Ref(it.name, it.system, it.base, "") }
            ?: throw McpTools.ToolError("No ${label(type)} preset \"$name\" for this printer (see preset_list)")
        val edits = if (name == current) currentEdits else emptyMap()
        val values = presets.presetValuesOf(type, name)
        // A printer preset without a parent is compared with the system preset of its model and nozzle, as the app
        // falls back to that one for its connection (PresetController.selectPrinterNow).
        val base = ref.base.takeIf { !ref.system && it.isNotEmpty() } ?: store.value.printers.takeIf { type == PresetType.PRINTER && !ref.system }
            ?.let { list ->
                val me = list.firstOrNull { it.name == name } ?: return@let null
                list.firstOrNull { it.system && me.model.isNotEmpty() && it.model == me.model && kotlin.math.abs(it.nozzle - me.nozzle) < 1e-3 }?.name
            }
        val parent = base?.let { runCatching { presets.presetValuesOf(type, it) }.getOrNull() }
        val defs = presets.optionDefs(type).associateBy { it.key }
        val keys = args.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        val all = args.optBoolean("all")
        val shown = when {
            keys != null -> keys
            all -> values.keys.toList()
            else -> (values.keys.filter { k -> parent != null && k !in IGNORED && parent[k] != values[k] } + edits.keys).distinct()
        }
        return buildString {
            append("${label(type).replaceFirstChar(Char::uppercase)} preset \"$name\": ")
            append(when {
                ref.system -> "system preset"
                parent != null && ref.base.isNotEmpty() -> "user preset based on system preset \"$base\""
                parent != null -> "user preset without a system parent (compared with \"$base\", same model and nozzle)"
                else -> "user preset without a system parent"
            })
            if (name == current) append(if (type == PresetType.FILAMENT) ", in slot ${slot + 1}" else ", selected")
            appendLine(", ${edits.size} unsaved edit(s)")
            if (keys == null && !all) appendLine(if (parent != null) "Settings that differ from \"$base\" (system value after |) and unsaved edits:"
                else "Unsaved edits (nothing to compare with: ask with keys or all for the settings):")
            for (k in shown) {
                val def = defs[k]
                val v = values[k]
                if (def == null && v == null) { appendLine("$k: unknown setting"); continue }
                append("$k").append(def?.let { " (${it.label.ifEmpty { it.fullLabel }})" }.orEmpty()).append(": ")
                append(v?.let { show(it, def, keys != null) } ?: "(not in this preset)")
                if (parent != null && parent[k] != v) append(" | system: ").append(parent[k]?.let { show(it, def, keys != null) } ?: "-")
                edits[k]?.let { append(" | unsaved edit: ").append(show(it, def, keys != null)) }
                appendLine()
            }
            if (shown.isEmpty()) appendLine("(nothing)")
        }
    }

    private suspend fun find(args: JSONObject): String {
        val type = args.type()
        val words = args.optString("query").lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) throw McpTools.ToolError("query is empty")
        val s = store.value
        val hits = presets.optionDefs(type).mapNotNull { d ->
            val name = "${d.key.replace('_', ' ')} ${d.label} ${d.fullLabel} ${d.category}".lowercase()
            val text = name + " " + d.tooltip.lowercase()
            if (words.any { it !in text }) null else d to words.count { it in name }
        }.sortedByDescending { it.second }.map { it.first }
        if (hits.isEmpty()) return "No ${label(type)} setting matches \"${args.optString("query")}\"."
        return buildString {
            hits.take(FIND_LIMIT).forEach { d ->
                append("${d.key} (${d.label.ifEmpty { d.fullLabel }})")
                if (d.category.isNotEmpty()) append(" [${d.category}]")
                append(" = ").append(s.value(type, d.key)?.let { show(it, d, false) } ?: "-")
                append("; ").append(d.type)
                if (d.sidetext.contains(' ')) append(" (").append(d.sidetext).append(")")
                if (d.min != null || d.max != null) append(" ${d.min?.let(::num) ?: ""}..${d.max?.let(::num) ?: ""}")
                if (d.enumValues.isNotEmpty()) append(", choices ").append(d.enumValues.zip(d.enumLabels + List(d.enumValues.size) { "" })
                    .joinToString { (v, l) -> if (l.isEmpty() || l == v) v else "$v ($l)" })
                if (d.readonly) append(", read-only")
                if (type == PresetType.PRINT && d.key in s.optionStates.disabled) append(", greyed out by other settings now")
                appendLine()
                d.tooltip.takeIf { it.isNotEmpty() }?.let { appendLine("  " + it.replace('\n', ' ').take(TOOLTIP_CHARS)) }
            }
            if (hits.size > FIND_LIMIT) appendLine("... ${hits.size - FIND_LIMIT} more; use more words.")
        }
    }

    private suspend fun edit(args: JSONObject): String {
        val type = args.type()
        val slot = if (type == PresetType.FILAMENT) args.slot() else store.value.activeFilament
        val (name, _) = selected(type, slot)
        val values = args.optJSONObject("values")?.takeIf { it.length() > 0 } ?: throw McpTools.ToolError("values must be an object with settings")
        if (type == PresetType.FILAMENT && values.has("slot_colour")) {
            val hex = values.optString("slot_colour")
            if (!Regex("#[0-9A-Fa-f]{6}").matches(hex)) throw McpTools.ToolError("slot_colour must be #RRGGBB")
            withContext(Dispatchers.Main.immediate) { presets.setFilamentColor(slot, hex.uppercase()) }
            values.remove("slot_colour")
            if (values.length() == 0) return "Filament slot ${slot + 1} colour is now ${hex.uppercase()}."
        }
        val defs = presets.optionDefs(type).associateBy { it.key }
        val preset = presets.presetValuesOf(type, name)
        val changes = values.keys().asSequence().associateWith { key ->
            val def = defs[key]
            if (def == null || key !in preset) throw McpTools.ToolError("$key is not a ${label(type)} setting (see setting_find)")
            if (values.isNull(key)) null else normalize(def, values.get(key))
        }
        withContext(Dispatchers.Main.immediate) {
            presets.editOverridesNow(type, slot) { cur ->
                changes.entries.fold(cur) { m, (k, v) -> if (v == null || v == preset[k]) m - k else m + (k to v) }
            }
        }
        store.toast(store.str(R.string.ai_edit, "changed ${changes.size} ${label(type)} setting(s)"))
        val (_, edits) = selected(type, slot)
        val disabled = if (type == PresetType.PRINT) store.value.optionStates.disabled else emptySet()
        return buildString {
            appendLine("Unsaved edits of ${label(type)} preset \"$name\" now (${edits.size}):")
            edits.forEach { (k, v) ->
                append("$k = ${show(v, defs[k], false)} (preset: ${preset[k]?.let { show(it, defs[k], false) }})")
                if (k in disabled) append(" - greyed out by other settings, has no effect now")
                appendLine()
            }
        }
    }

    private suspend fun slots(args: JSONObject): String {
        val before = store.value.filaments.size
        if (args.has("remove") == args.has("count")) throw McpTools.ToolError("Give exactly one of count or remove")
        if (args.has("remove")) {
            val i = args.optInt("remove") - 1
            if (before <= 1) throw McpTools.ToolError("The last slot can't be removed")
            if (i !in 0 until before) throw McpTools.ToolError("No filament slot ${i + 1} (1..$before)")
            withContext(Dispatchers.Main.immediate) { presets.removeFilamentNow(i) }
        } else {
            val c = args.optInt("count")
            if (c !in 1..64) throw McpTools.ToolError("count must be 1..64")
            withContext(Dispatchers.Main.immediate) { presets.setFilamentCountNow(c) }
        }
        val now = store.value.filaments.size
        store.toast(store.str(R.string.ai_edit, "filament slots $before -> $now"))
        return "Filament slots: $before -> $now (see plate_describe)."
    }

    private suspend fun select(args: JSONObject): String {
        val type = args.type()
        if (type == PresetType.PRINTER) throw McpTools.ToolError("Selecting the printer is left to the user in the app")
        val slot = if (type == PresetType.FILAMENT) args.slot() else 0
        val name = args.optString("name")
        if (refs(type).none { it.name == name }) throw McpTools.ToolError("No ${label(type)} preset \"$name\" for this printer (see preset_list)")
        val (current, edits) = selected(type, slot)
        if (current == name) return "\"$name\" is already selected."
        if (edits.isNotEmpty() && !args.optBoolean("discard_edits"))
            throw McpTools.ToolError("\"$current\" has ${edits.size} unsaved edit(s) (${edits.keys.joinToString()}); save them with preset_save or pass discard_edits")
        withContext(Dispatchers.Main.immediate) {
            if (type == PresetType.PRINT) presets.selectPrintNow(name) else presets.setFilamentNow(slot, name)
        }
        val where = if (type == PresetType.FILAMENT) "filament slot ${slot + 1}" else "process"
        store.toast(store.str(R.string.ai_edit, "selected \"$name\" ($where)"))
        return "Selected \"$name\" as $where."
    }

    private suspend fun save(args: JSONObject): String {
        val type = args.type()
        val slot = if (type == PresetType.FILAMENT) args.slot() else 0
        val name = args.optString("name").trim()
        if (name.isEmpty() || name.any { it.isISOControl() }) throw McpTools.ToolError("name must be a plain, non-empty name")
        val (current, edits) = selected(type, slot)
        val existing = refs(type).firstOrNull { it.name == name } ?: store.value.printers.firstOrNull { it.name == name }?.let { Ref(it.name, it.system, it.base, "") }
        if (existing?.system == true) throw McpTools.ToolError("\"$name\" is a system preset; pick another name")
        val text = store.plural(R.plurals.ai_confirm_save, edits.size, edits.size, label(type), current, name) +
            (existing?.let { "\n" + store.str(R.string.ai_confirm_overwrites, name) } ?: "")
        if (!confirm(text)) throw McpTools.ToolError("Declined by the user (or no answer within 60 s)")
        withContext(Dispatchers.Main.immediate) {
            store.update { it.copy(busy = store.str(R.string.saving_preset)) }
            try {
                if (type == PresetType.FILAMENT && store.value.activeFilament != slot) presets.setActiveFilamentNow(slot)
                presets.savePresetNow(type, name)
            } finally {
                store.update { it.copy(busy = null) }
            }
        }
        val (now, _) = selected(type, slot)
        if (now != name) throw McpTools.ToolError("Saving failed; \"$now\" is still selected")
        return "Saved ${label(type)} preset \"$name\" (from \"$current\" with ${edits.size} edit(s)); it is selected now."
    }

    private suspend fun delete(args: JSONObject): String {
        val type = args.type()
        val name = args.optString("name")
        val s = store.value
        val ref = (if (type == PresetType.PRINTER) s.printers.map { Ref(it.name, it.system, it.base, "") } else refs(type)).firstOrNull { it.name == name }
            ?: throw McpTools.ToolError("No ${label(type)} preset \"$name\" for this printer (see preset_list)")
        if (ref.system) throw McpTools.ToolError("\"$name\" is a system preset; only the user's own presets can be deleted")
        val inUse = when (type) {
            PresetType.PRINTER -> s.printer == name
            PresetType.PRINT -> s.print == name
            PresetType.FILAMENT -> s.filaments.any { it.preset == name }
        }
        if (inUse) throw McpTools.ToolError("\"$name\" is selected; select another preset first (preset_select, or the user in the app)")
        if (!confirm(store.str(R.string.ai_confirm_delete, label(type), name))) throw McpTools.ToolError("Declined by the user (or no answer within 60 s)")
        withContext(Dispatchers.Main.immediate) {
            store.update { it.copy(busy = store.str(R.string.deleting_preset)) }
            try {
                presets.deleteUnusedPresetNow(type, name)
            } finally {
                store.update { it.copy(busy = null) }
            }
        }
        return "Deleted ${label(type)} preset \"$name\"."
    }

    /** [value] in libslic3r's text form for [def], or a [McpTools.ToolError] saying what is wrong. */
    private fun normalize(def: OptionDef, value: Any): String {
        if (def.readonly) throw McpTools.ToolError("${def.key} is read-only")
        fun scalar(x: Any): String = when (x) {
            is Boolean -> if (x) "1" else "0"
            is Number -> num(x.toDouble())
            else -> x.toString()
        }
        if (def.baseType == "string" || def.type in setOf("point", "points")) {
            val text = if (value is JSONArray) (0 until value.length()).joinToString(if (def.type == "strings") ";" else ",") { scalar(value.get(it)) } else scalar(value)
            // Real line breaks (G-code written out) become the stored escapes.
            val escaped = if (text.contains('\n') || text.contains('\r')) text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") else text
            return if (def.type == "strings" && !escaped.startsWith('"')) "\"" + escaped.replace("\"", "\\\"") + "\"" else escaped
        }
        val parts = when (value) {
            is JSONArray -> (0 until value.length()).map { scalar(value.get(it)) }
            else -> scalar(value).split(',').map { it.trim() }
        }
        if (!def.isVector && parts.size != 1) throw McpTools.ToolError("${def.key} takes one value")
        return parts.joinToString(",") { x ->
            when (def.baseType) {
                "bool" -> when (x.lowercase()) {
                    "1", "true", "on" -> "1"
                    "0", "false", "off" -> "0"
                    else -> throw McpTools.ToolError("${def.key} is on / off: 1 or 0")
                }
                "enum" -> x.takeIf { it in def.enumValues }
                    ?: throw McpTools.ToolError("${def.key} must be one of ${def.enumValues.joinToString()}")
                "int" -> x.toLongOrNull()?.also { range(def, it.toDouble()) }?.toString()
                    ?: throw McpTools.ToolError("${def.key} takes whole numbers")
                "float", "percent", "float_or_percent" -> {
                    val pct = x.endsWith("%")
                    if (pct && def.baseType == "float") throw McpTools.ToolError("${def.key} takes a number, not a percentage")
                    val n = x.removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                        ?: throw McpTools.ToolError("${def.key} takes a number${if (def.baseType != "float") " or a percentage" else ""}")
                    if (!pct) range(def, n)
                    num(n) + if (pct) "%" else ""
                }
                else -> x
            }
        }
    }

    private fun range(def: OptionDef, x: Double) {
        def.min?.let { if (x < it) throw McpTools.ToolError("${def.key} must be at least ${num(it)}") }
        def.max?.let { if (x > it) throw McpTools.ToolError("${def.key} must be at most ${num(it)}") }
    }

    /** A value for the reply; long text (G-code) is cut unless asked for by key. */
    private fun show(v: String, def: OptionDef?, full: Boolean): String {
        if (v.isEmpty()) return "\"\""
        if (!full && v.length > VALUE_CHARS) return "<${v.length} chars; ask with keys>"
        // Units only on plain numbers ("15%" already has its own; "mm or %" isn't one unit).
        val unit = def?.sidetext?.takeIf { it.isNotEmpty() && !it.contains(' ') && !v.endsWith('%') && v.toDoubleOrNull() != null }
        return if (unit != null) "$v $unit" else v
    }

    private fun num(x: Double) = if (x == Math.rint(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString() else x.toString()

    private companion object {
        const val LIST_LIMIT = 150
        const val FIND_LIMIT = 20
        const val VALUE_CHARS = 120
        const val TOOLTIP_CHARS = 200
        /** Bookkeeping keys that always differ from the parent. */
        val IGNORED = setOf("inherits", "print_settings_id", "filament_settings_id", "printer_settings_id", "version", "setting_id",
            "from", "is_custom_defined", "name", "compatible_printers", "compatible_printers_condition", "compatible_prints",
            "compatible_prints_condition", "printer_model", "default_print_profile", "default_filament_profile")
    }
}
