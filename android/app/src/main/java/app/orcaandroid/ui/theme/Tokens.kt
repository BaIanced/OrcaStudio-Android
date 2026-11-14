package app.orcaandroid.ui.theme

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Gaps and padding (design spec section 4). */
object Space {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
}

/** Smallest size of anything that is tapped: 48 dp, never below 40 dp after density scaling. */
object TouchTarget {
    val MIN = 48.dp
    val FLOOR = 40.dp
}

/** Every icon button: at least [TouchTarget.MIN] square. Size the icon, not the button. */
fun Modifier.touchTarget(): Modifier = sizeIn(minWidth = TouchTarget.MIN, minHeight = TouchTarget.MIN)
