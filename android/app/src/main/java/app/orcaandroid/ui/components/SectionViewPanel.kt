package app.orcaandroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.render.SectionView
import app.orcaandroid.ui.prepare.Tip

/** The desktop's section view panel (GLCanvas3D::_render_section_view_panel): ratio slider, aim, reset. */
@Composable
fun SectionViewPanel(section: SectionView, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp) {
        // Two rows, so the slider keeps its width in the narrow 3D view next to the sidebar.
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.section_view), Modifier.weight(1f))
                Text("%.2f".format(section.ratio))
                Tip(stringResource(R.string.section_view_align)) { IconButton(onClick = section::alignToCamera) { Icon(Icons.Default.Videocam, stringResource(R.string.section_view_align)) } }
                Tip(stringResource(R.string.reset)) { IconButton(onClick = { section.set(0f) }, enabled = section.active) { Icon(Icons.Default.Restore, stringResource(R.string.reset)) } }
                Tip(stringResource(R.string.close)) { IconButton(onClick = { section.panelOpen = false }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } }
            }
            Slider(section.ratio, section::set, Modifier.padding(end = 12.dp))
        }
    }
}
