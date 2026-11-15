package app.orcaandroid.ui.prepare

import app.orcaandroid.R
import app.orcaandroid.ui.UiState

/** Most shortcuts the dock shows in one context (docs/design/EDGE-MENU.md section 7). */
const val MAX_SHORTCUTS = 6

/**
 * The shortcut list as pure functions: an ordered list of menu node ids (MenuTree.kt), stored as one string joined with ",".
 * A shortcut shows in the contexts its id's scope names ([MenuScope.of]); the cap holds for each context, so a `view.*`
 * shortcut counts against both. Ids that name no leaf any more (renamed or removed) are skipped and dropped on the next change.
 */
object Shortcuts {
    fun parse(raw: String?): List<String> = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun join(ids: List<String>): String = ids.joinToString(",")

    /** The id names a leaf that runs something. */
    fun known(id: String): Boolean = findMenuNode(id)?.let { it.isLeaf && it.action != null } == true

    fun clean(ids: List<String>): List<String> = ids.filter(::known).distinct()

    /** The id shows in Reshape ([reshaping] true) or outside it. */
    fun inContext(id: String, reshaping: Boolean): Boolean = when (MenuScope.of(id)) {
        MenuScope.PLATE -> !reshaping
        MenuScope.RESHAPE -> reshaping
        MenuScope.BOTH -> true
    }

    /** What the dock shows in a context, in order. */
    fun forContext(ids: List<String>, reshaping: Boolean): List<String> = clean(ids).filter { inContext(it, reshaping) }

    /** A new shortcut for [id] fits: a known leaf, not there yet, and no context it shows in is full. */
    fun canAdd(ids: List<String>, id: String): Boolean =
        known(id) && id !in ids && listOf(false, true).all { r -> !inContext(id, r) || forContext(ids, r).size < MAX_SHORTCUTS }

    /** [ids] with [id] appended, or null when it does not fit ([canAdd]). */
    fun add(ids: List<String>, id: String): List<String>? = if (canAdd(ids, id)) clean(ids) + id else null

    fun remove(ids: List<String>, id: String): List<String> = clean(ids) - id

    /** [id] back at [index] (an Undo of [remove]), or null when it no longer fits. */
    fun insert(ids: List<String>, id: String, index: Int): List<String>? =
        if (canAdd(ids, id)) clean(ids).toMutableList().also { it.add(index.coerceIn(0, it.size), id) } else null

    /**
     * The dock's [visible] shortcuts were reordered: they take, in that order, the places the same ids held in [ids]. Shortcuts
     * of other contexts stay where they are. A [visible] that is not a reordering of some of [ids] changes nothing.
     */
    fun reorder(ids: List<String>, visible: List<String>): List<String> {
        val all = clean(ids)
        val set = visible.toSet()
        if (set.size != visible.size || !all.containsAll(set)) return all
        val next = visible.iterator()
        return all.map { if (it in set) next.next() else it }
    }

    /**
     * Why the shortcut [id] cannot run now: a string resource, or null when it can. A shortcut whose node is hidden by the
     * context (Cut with nothing selected) or disabled stays in the dock, dimmed, and tapping it shows this.
     */
    fun blockedReason(state: UiState, id: String): Int? {
        val chain = menuChain(id) ?: return null
        val node = chain.last()
        val ws = state.workspace
        if (chain.any { !it.visible(state) }) {
            return when {
                ws.focus != null && MenuScope.of(id) == MenuScope.PLATE -> R.string.shortcut_not_in_room
                else -> when (chain.first().id) {
                    "object", "plate.edit", "plate.duplicate", "plate.delete" ->
                        if (ws.multiSelection.isNotEmpty()) R.string.shortcut_need_one else R.string.shortcut_need_object
                    "selection" -> R.string.shortcut_need_many
                    "plate.arrange", "plate.measure" -> R.string.shortcut_need_scene
                    else -> R.string.shortcut_unavailable
                }
            }
        }
        if (!node.enabled(state)) return if (id == "selection.assemble") R.string.shortcut_need_many else R.string.shortcut_unavailable
        return null
    }
}
