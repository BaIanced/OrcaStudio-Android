package app.orcaandroid.ui.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orcaandroid.R
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState

/** The small yellow "CAD" tag on the rows that open the design builder. */
@Composable
fun CadBadge() {
    Text("CAD", Modifier.background(Color(0xFFFFD400), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
}

/**
 * The Add menu's builder row: one "Make a part" row opening the shape builder (code stays reachable inside via New / Open).
 * With the focus room on, the same row opens the builder inside a room (and is left out while a room is open).
 */
@Composable
fun DesignEntries(state: UiState, vm: AppViewModel, close: () -> Unit) {
    val room = state.chrome.focusRoom
    if (room && state.workspace.focus != null) return
    DropdownMenuItem(text = { Text(stringResource(R.string.add_design)) }, leadingIcon = { Icon(Icons.Default.Architecture, null) },
        trailingIcon = { CadBadge() }, onClick = { close(); if (room) vm.makePart() else vm.scad.newShapes() })
}

/** "Edit shape" (focus room on): opens the room on object [obj]. */
@Composable
fun EditShapeItem(vm: AppViewModel, obj: Int, close: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.edit_shape)) }, leadingIcon = { Icon(Icons.Default.Architecture, null) },
        onClick = { close(); vm.openRoom(obj) })
}
