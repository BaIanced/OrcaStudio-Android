# Upstream OrcaSlicer base

This Android port builds the unmodified OrcaSlicer sources in `../src-orca`.

| | |
|---|---|
| Repository | https://github.com/SoftFever/OrcaSlicer |
| Commit | `2769b12ce702f32a2aed72ee9c0d6a5946bf229a` |
| Commit date | 2026-09-30 |
| Version | OrcaSlicer 2.5.0-dev (`SLIC3R_VERSION` 02.08.01.55) |

## Updating to a newer upstream

1. Check out the new commit in `src-orca` and update the table above.
2. Review upstream changes to the files the port depends on:
   - `deps/*/*.cmake` – the Android superbuild (`android/deps`) includes these recipes.
   - `src/libslic3r/CMakeLists.txt` – link list mirrored by `android/core/CMakeLists.txt`.
   - Top-level `CMakeLists.txt` – compile flags/defines replicated in `android/core`.
   - `src/slic3r/GUI/Tab.cpp` – source of the settings layout (`scripts/extract_settings_layout.py`).
   - `src/OrcaSlicer.cpp` (CLI slicing flow) – reference for `android/core/jni/OrcaEngine.cpp`.
   - `src/slic3r/GUI/ConfigManipulation.cpp` – `toggle_print_fff_options` is extracted at build time
     (`scripts/extract_option_toggles.py`) for the greyed-out/hidden option rules.
   - `src/slic3r/Utils/ColorSpaceConvert.cpp` – wx-free part extracted by `scripts/extract_color_space.py`
     (flushing volume calculation).
   - `src/slic3r/GUI/Plater.cpp` (`calib_*`) – ported by hand to `core/jni/OrcaExtras.cpp` (`calib_start`).
   - `src/slic3r/GUI/Jobs/ArrangeJob.cpp`, `GLGizmoCut.cpp` – conventions the scene code relies on
     (items start with `bed_idx = 0`; cut planes are relative to the instance offset).
   - `resources/profiles`, `localization/i18n` – packed into the app by `scripts/pack_resources.py`.
3. Rebuild with `android/scripts/build.sh` (a changed dependency recipe requires deleting
   `~/build/orca-android/deps`).

Workarounds that exist because of upstream structure (re-check them on every update):

- `core/jni/nanosvg_impl.cpp` – upstream defines the nanosvg implementation in the GUI.
- `core/CMakeLists.txt` – empty `fontconfig` target; OpenVDB is required only by SLA hollowing.
- `OrcaEngine::load_presets` marks all loaded presets visible (the desktop hides models not
  ticked in its setup wizard, and `select_preset_by_name` silently falls back otherwise).
- `OrcaEngine::drop_to_bed` computes missing volume convex hulls: volumes created by `Cut` have
  none, but `ensure_on_bed()` and the arrange polygons are built from them.
