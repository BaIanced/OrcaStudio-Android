package app.orcaandroid.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** The desktop's section view panel (GLCanvas3D::_render_section_view_panel): ratio slider, aim, reset. */
@Composable
fun SectionViewPanel(section: SectionView, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp) {
        // Fits the narrow 3D view next to the sidebar: the slider takes what is left.
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.section_view))
            Slider(section.ratio, section::set, Modifier.padding(horizontal = 8.dp).weight(1f))
            Text("%.2f".format(section.ratio), Modifier.width(40.dp))
            IconButton(onClick = section::alignToCamera) { Icon(Icons.Default.Videocam, stringResource(R.string.section_view_align)) }
            IconButton(onClick = { section.set(0f) }, enabled = section.active) { Icon(Icons.Default.Restore, stringResource(R.string.reset)) }
            IconButton(onClick = { section.panelOpen = false }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
    }
}
