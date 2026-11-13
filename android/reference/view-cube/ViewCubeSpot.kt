// Cut from ui/AppScaffold.kt (private there). Needs imports: Offset, remember/mutableStateOf, offset, roundToInt, dp, ViewCube (ViewCube.kt).
// The call site was: if (viewCubeOn) plateView?.let { ViewCubeSpot(it.camera, viewportSize) } inside the viewport Box.

/**
 * The view cube where the user left it: top right by default; a long press on it picks it up and dragging moves it
 * (kept inside the 3D view, saved as fractions of the free room so it stays put across rotation and resizing).
 */
@Composable
private fun ViewCubeSpot(camera: app.orcaandroid.render.Camera, area: androidx.compose.ui.unit.IntSize) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cube = with(density) { 96.dp.toPx() }
    val roomX = (area.width - cube).coerceAtLeast(0f)
    val roomY = (area.height - cube).coerceAtLeast(0f)
    // removable after lighting review (UA-7): the spot is a constant now, so a drag only lasts until let go.
    val spotX = app.orcaandroid.core.ViewDefaults.VIEW_CUBE_X
    val spotY = app.orcaandroid.core.ViewDefaults.VIEW_CUBE_Y
    fun saved() = if (spotX < 0f || spotY < 0f) Offset(roomX - with(density) { 8.dp.toPx() }, with(density) { 72.dp.toPx() })
        else Offset(spotX * roomX, spotY * roomY)
    var live by remember { mutableStateOf<Offset?>(null) }
    val at = (live ?: saved()).let { Offset(it.x.coerceIn(0f, roomX), it.y.coerceIn(0f, roomY)) }
    app.orcaandroid.ui.components.ViewCube(camera,
        Modifier.offset { androidx.compose.ui.unit.IntOffset(at.x.roundToInt(), at.y.roundToInt()) },
        onMove = { d -> (at + d).let { live = Offset(it.x.coerceIn(0f, roomX), it.y.coerceIn(0f, roomY)) } },
        onMoveEnd = { live = null })
}
