package app.orcaandroid.ui.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.RoomTool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.theme.TouchTarget

private class RoomToolEntry(val tool: RoomTool, val label: Int, val icon: ImageVector)

/** The five shape tools of the focus room, in the spec's fixed order (docs/design/FOCUS-ROOM.md section 5). */
private val ROOM_TOOLS = listOf(
    RoomToolEntry(RoomTool.SHAPES, R.string.room_tool_shapes, Icons.Default.Category),
    RoomToolEntry(RoomTool.PUSH_PULL, R.string.room_tool_push_pull, Icons.Default.Height),
    RoomToolEntry(RoomTool.RESHAPE, R.string.room_tool_reshape, Icons.Default.Gesture),
    RoomToolEntry(RoomTool.MESH_EDIT, R.string.room_tool_mesh_edit, Icons.Default.GridOn),
    RoomToolEntry(RoomTool.SKETCH, R.string.room_tool_sketch, Icons.Default.Draw),
)

/**
 * The focus room's toolbar under the mode strip: Shapes, Push / pull, Reshape, Mesh edit, Sketch, always all five, each at
 * least 48 dp high. It takes the place of the plate's tool column while the room is open. A tool of the other family
 * converts the object first ([AppViewModel.setRoomTool]). Reshape and Mesh edit wait for the first shape of a new part.
 */
@Composable
internal fun RoomToolbar(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val focus = state.workspace.focus ?: return
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
      Box {
        // A switch that converts the object (a big mesh's wrap) takes a while; taps meanwhile are ignored, so show it.
        if (vm.roomRouting) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ROOM_TOOLS.forEach { e ->
                val selected = focus.tool == e.tool
                val enabled = focus.obj >= 0 || e.tool == RoomTool.SHAPES || e.tool == RoomTool.SKETCH || e.tool == RoomTool.PUSH_PULL
                val ink = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    else if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).defaultMinSize(minHeight = TouchTarget.MIN)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                    .selectable(selected, enabled, Role.Tab) { vm.setRoomTool(e.tool) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(e.icon, null, Modifier.size(24.dp), tint = ink)
                    Text(stringResource(e.label), style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
      }
    }
}

/** "Keep as a fixed shape?", before a mesh tool changes a design's surface directly (in the room or from the More menu). */
@Composable
internal fun FreezeDialog(state: UiState, vm: AppViewModel) {
    val ask = state.workspace.freezeAsk ?: return
    AlertDialog(
        onDismissRequest = vm::cancelFreeze,
        title = { Text(stringResource(R.string.freeze_title)) },
        text = { Text(stringResource(R.string.freeze_text, ask.name)) },
        confirmButton = { TextButton(onClick = vm::confirmFreeze) { Text(stringResource(R.string.freeze_keep)) } },
        dismissButton = { TextButton(onClick = vm::cancelFreeze) { Text(stringResource(R.string.cancel)) } },
    )
}
