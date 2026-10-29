package app.orcaandroid.ui.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Code
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

/** The small yellow "CAD" tag on the rows that open the design builder. */
@Composable
fun CadBadge() {
    Text("CAD", Modifier.background(Color(0xFFFFD400), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
}

/**
 * The Add menu's builder rows: one "Design" row opening the shape builder (code stays reachable inside via New / Open),
 * or with [split] (Settings) the separate OpenSCAD and Shape builder rows, each badged.
 */
@Composable
fun DesignEntries(vm: AppViewModel, split: Boolean, close: () -> Unit) {
    if (split) {
        DropdownMenuItem(text = { Text(stringResource(R.string.add_openscad)) }, leadingIcon = { Icon(Icons.Default.Code, null) },
            trailingIcon = { CadBadge() }, onClick = { close(); vm.scad.open() })
        DropdownMenuItem(text = { Text(stringResource(R.string.shape_builder)) }, leadingIcon = { Icon(Icons.Default.Architecture, null) },
            trailingIcon = { CadBadge() }, onClick = { close(); vm.scad.newShapes() })
    } else {
        DropdownMenuItem(text = { Text(stringResource(R.string.add_design)) }, leadingIcon = { Icon(Icons.Default.Architecture, null) },
            trailingIcon = { CadBadge() }, onClick = { close(); vm.scad.newShapes() })
    }
}
