package app.orcaandroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orcaandroid.R

/** Sizes of the tool card (design spec section 6). */
object ToolCardDefaults {
    val HEADER = 48.dp
    val PAD = 12.dp
    val GAP = 8.dp
    val ROW = 48.dp
    val ACTION_WIDTH = 80.dp
    val FOOTER = 56.dp
    val PRIMARY_MIN_WIDTH = 96.dp
    val RADIUS = 16.dp
    const val MAX_HEIGHT_FRACTION = 0.45f
    val SIDE_WIDTH = 320.dp
    val SIDE_WIDTH_WIDE = 360.dp
}

/**
 * A docked tool card: a [ToolCardDefaults.HEADER] with the [title], Fold and (outside rooms, [onClose] not null) Close;
 * a scrolling body; a pinned [footer] (the primary button) under a line. The caller bounds the height (the body scrolls)
 * and the width; [side] = docked at the end of the window, else at the bottom.
 */
@Composable
fun ToolCard(
    title: String, side: Boolean, folded: Boolean, onFold: () -> Unit, onClose: (() -> Unit)?, modifier: Modifier = Modifier,
    footer: (@Composable RowScope.() -> Unit)? = null, content: @Composable () -> Unit,
) {
    val r = ToolCardDefaults.RADIUS
    Surface(modifier, shape = if (side) RoundedCornerShape(topStart = r, bottomStart = r) else RoundedCornerShape(topStart = r, topEnd = r),
        tonalElevation = 6.dp, shadowElevation = 2.dp) {
        Column {
            Row(Modifier.fillMaxWidth().height(ToolCardDefaults.HEADER).padding(start = ToolCardDefaults.PAD), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fold = stringResource(if (folded) R.string.design_unfold else R.string.design_minimize)
                IconButton(onFold, Modifier.size(ToolCardDefaults.HEADER)) { Icon(if (folded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, fold) }
                if (onClose != null) IconButton(onClose, Modifier.size(ToolCardDefaults.HEADER)) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            if (!folded) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = ToolCardDefaults.PAD),
                    verticalArrangement = Arrangement.spacedBy(ToolCardDefaults.GAP)) { content() }
                if (footer != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().height(ToolCardDefaults.FOOTER).padding(horizontal = ToolCardDefaults.PAD),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = footer)
                }
            }
        }
    }
}

/**
 * A text and up to two buttons: [text] takes what the buttons leave (one line, ellipsis), the buttons are exactly
 * [ToolCardDefaults.ACTION_WIDTH] wide (see [CardAction]) and end-aligned, so they never move with the text.
 */
@Composable
fun CardActionRow(text: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(ToolCardDefaults.ROW), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** A text button of a [CardActionRow]: fixed [ToolCardDefaults.ACTION_WIDTH] wide. */
@Composable
fun CardAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick, Modifier.width(ToolCardDefaults.ACTION_WIDTH), enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(label, maxLines = 1)
    }
}

/** The primary button of the footer: filled, 40 dp high in a 48 dp box, at least [ToolCardDefaults.PRIMARY_MIN_WIDTH] wide. */
@Composable
fun CardPrimary(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Button(onClick, Modifier.height(40.dp).defaultMinSize(minWidth = ToolCardDefaults.PRIMARY_MIN_WIDTH), enabled = enabled) { Text(label, maxLines = 1) }
    }
}
