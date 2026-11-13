package app.orcaandroid.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.ui.prepare.Tip
import app.orcaandroid.ui.theme.LocalAppColors
import app.orcaandroid.ui.theme.ROOM_CUT
import app.orcaandroid.ui.theme.ROOM_MAKE
import app.orcaandroid.ui.theme.ROOM_PAINT
import app.orcaandroid.ui.theme.ROOM_RESHAPE
import app.orcaandroid.ui.theme.Region
import app.orcaandroid.ui.theme.Slot
import app.orcaandroid.ui.theme.TouchTarget
import app.orcaandroid.ui.theme.onColor

/** The four rooms of direction A (design spec, "Vocabulary"): each has a colour, a word and an icon. */
enum class Room(val slot: Slot, @StringRes val label: Int, val icon: ImageVector) {
    MAKE(ROOM_MAKE, R.string.room_make, Icons.Default.Architecture),
    RESHAPE(ROOM_RESHAPE, R.string.room_reshape, Icons.Default.Gesture),
    PAINT(ROOM_PAINT, R.string.paint, Icons.Default.Brush),
    CUT(ROOM_CUT, R.string.cut, Icons.Default.ContentCut),
}

/** Sizes of the mode strip (design spec section 5). */
object ModeStripDefaults {
    val HEIGHT = 48.dp
    val PAD_START = 16.dp
    val PAD_END = 8.dp
    val ICON = 24.dp
    val WORD_FROM = 600.dp
    val TITLE_MIN = 96.dp
    val DONE_MIN_WIDTH = 72.dp
    val DONE_HEIGHT = 36.dp
    val FRAME = 3.dp
}

@Composable
private fun roomColour(room: Room) = LocalAppColors.current.color(Region.ROOMS, room.slot)

/**
 * The strip across the top of a room (Make, Reshape, Paint, Cut): the room's icon and word, "Editing: <name>", the room's
 * undo and redo (always shown, disabled when empty) and **Done** in the last slot. [actions] sit before Undo.
 */
@Composable
fun ModeStrip(room: Room, name: String, canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onDone: () -> Unit,
              modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val bar = roomColour(room)
    val ink = onColor(bar)
    Surface(modifier, color = bar, contentColor = ink) {
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))) {
            // Short widths drop the room word, then the icon, before the title gets narrower than TITLE_MIN.
            val fixed = ModeStripDefaults.PAD_START + ModeStripDefaults.PAD_END + TouchTarget.MIN * 2 + 4.dp + ModeStripDefaults.DONE_MIN_WIDTH
            val showIcon = maxWidth >= fixed + ModeStripDefaults.ICON + 8.dp + ModeStripDefaults.TITLE_MIN
            val showWord = maxWidth >= ModeStripDefaults.WORD_FROM
            Row(Modifier.fillMaxWidth().height(ModeStripDefaults.HEIGHT).padding(start = ModeStripDefaults.PAD_START, end = ModeStripDefaults.PAD_END),
                verticalAlignment = Alignment.CenterVertically) {
                if (showIcon) {
                    Icon(room.icon, null, Modifier.size(ModeStripDefaults.ICON))
                    Spacer(Modifier.size(8.dp))
                }
                if (showWord) Text(stringResource(room.label), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.mode_editing, name), Modifier.weight(1f).widthIn(min = ModeStripDefaults.TITLE_MIN),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
                val colors = IconButtonDefaults.iconButtonColors(contentColor = ink, disabledContentColor = ink.copy(alpha = 0.38f))
                Tip(stringResource(R.string.undo)) {
                    IconButton(onUndo, Modifier.size(TouchTarget.MIN), enabled = canUndo, colors = colors) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo), Modifier.size(ModeStripDefaults.ICON))
                    }
                }
                Tip(stringResource(R.string.redo)) {
                    IconButton(onRedo, Modifier.size(TouchTarget.MIN), enabled = canRedo, colors = colors) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo), Modifier.size(ModeStripDefaults.ICON))
                    }
                }
                Spacer(Modifier.size(4.dp))
                // Done is inverted: the strip's ink as the pill, the room colour as its label. A 36 dp pill in a 48 dp touch box.
                Box(Modifier.defaultMinSize(minWidth = ModeStripDefaults.DONE_MIN_WIDTH, minHeight = TouchTarget.MIN)
                    .clickable(role = SemanticsRole.Button, onClick = onDone), contentAlignment = Alignment.Center) {
                    Box(Modifier.defaultMinSize(minWidth = ModeStripDefaults.DONE_MIN_WIDTH).height(ModeStripDefaults.DONE_HEIGHT)
                        .background(ink, RoundedCornerShape(ModeStripDefaults.DONE_HEIGHT / 2)).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.done), style = MaterialTheme.typography.labelLarge, color = bar)
                    }
                }
            }
        }
    }
}

/** The room's colour as a 3 dp frame round the 3D view (a border, not a fill, so nothing shows while the view draws its first frame); null = none. */
@Composable
fun roomFrame(room: Room?): Modifier {
    if (room == null) return Modifier
    val c = roomColour(room)
    return Modifier.border(ModeStripDefaults.FRAME, c).padding(ModeStripDefaults.FRAME)
}
