# Reference code (not compiled)

Code kept for reference only. This folder is not part of any Gradle source set (`app/build.gradle*` does not point at it),
so nothing here is built or shipped. Cut after 3003a1d.

- `view-cube/`: the orientation cube drawn in Compose (`ViewCube.kt`) and its draggable placement in the 3D view
  (`ViewCubeSpot.kt`, cut from `ui/AppScaffold.kt`). It needed the strings `view_top`, `view_front`, `view_left`,
  `view_right` (still in the app) and `view_bottom`, `view_rear` (removed with it).
- `pie-menu/`: the long-press pie menu (`PieMenu.kt`: `PieTouch`, `PieItem`, `PieMenu`) and how the context menu built its
  wedges (`PieMenuUsage.kt`, cut from `ui/prepare/PrepareOverlay.kt`). `PieTouch` was fed by the view model from
  `PlateView.Listener.onLongPressTouch`; that hook stays in the renderer as a no-op default.

To bring either back, move the files into `app/src/main/java/app/orcaandroid/ui/...`, restore the call sites and add a setting.
