// Cut from ViewContextMenu in ui/prepare/PrepareOverlay.kt. Ran when the pie menu setting was on (it was a constant false):
// the list menu was the else branch. PieTouch was fed by PlateView.Listener.onLongPressTouch (via the view model).
// Icons and strings used: see the imports of the old file (Icons.Default.*, R.string.*, AutoOrientIcon from OverlayParts.kt).

    // "Pie menu" (a constant now, removable after lighting review, UA-7): wedges for the common actions; the last wedge ("More") opens the list.
    var more by remember(at) { mutableStateOf(false) }
    if (at != null && ViewDefaults.PIE_MENU && !more) {
        val close = vm.scene::closeContextMenu
        val sel = state.workspace.selection
        val moreItem = PieItem(Icons.Default.MoreHoriz, stringResource(R.string.more)) { more = true }
        val items = if (state.workspace.multiSelection.size >= 2) listOf(
            PieItem(Icons.Default.CallMerge, stringResource(R.string.assemble)) { vm.scene.assemble() },
            PieItem(Icons.Default.CenterFocusStrong, stringResource(R.string.center_on_plate)) { vm.scene.centerSelected() },
            PieItem(Icons.Default.ContentCopy, stringResource(R.string.duplicate)) { vm.scene.duplicate(1) },
            PieItem(Icons.Default.Delete, stringResource(R.string.delete)) { vm.scene.deleteSelected() },
            moreItem,
        ) else if (sel != null) listOf(
            PieItem(Icons.Default.Rotate90DegreesCcw, stringResource(R.string.rotate), state.workspace.multiSelection.isEmpty()) { vm.scene.toggleRotate() },
            PieItem(Icons.Default.OpenInFull, stringResource(R.string.resize), state.workspace.multiSelection.isEmpty()) { vm.scene.toggleResize() },
            PieItem(Icons.Default.VerticalAlignBottom, stringResource(R.string.lay_on_face)) { vm.scene.setTool(Tool.LayOnFace) },
            PieItem(Icons.Default.ContentCopy, stringResource(R.string.duplicate)) { vm.scene.duplicate(1) },
            moreItem,
            PieItem(Icons.Default.Delete, stringResource(R.string.delete)) { vm.scene.deleteSelected() },
            PieItem(Icons.Default.ContentCut, stringResource(R.string.cut)) { vm.scene.startCut() },
        ) else listOf(
            PieItem(Icons.Default.FileOpen, stringResource(R.string.import_model)) { importModels.launch(arrayOf("*/*")) },
            PieItem(Icons.Default.Interests, stringResource(R.string.add_shape)) { dialog = "primitive" },
            PieItem(Icons.Default.TextFields, stringResource(R.string.add_text)) { dialog = "text" },
            PieItem(Icons.Default.AutoAwesomeMosaic, stringResource(R.string.arrange), !state.workspace.scene.isEmpty) { vm.scene.arrange(false) },
            PieItem(AutoOrientIcon, stringResource(R.string.auto_orient), !state.workspace.scene.isEmpty) { vm.scene.autoOrient() },
            moreItem,
        )
        PieMenu(at, inset, items, vm.pieTouch, close)
