# Review 0006 — 3MF saved by the app rejected by desktop OrcaSlicer (Task 6)

Investigation only; no code changed. All line references are against `muse/work` at
ebd8476 (the `src-orca/` submodule as cloned with it). Nothing here rests on running
either program: no 3MF was produced or opened in this review, so every verdict about
the app's export is from reading the code paths on both sides.

## The two error messages, and how they connect

- "Invalid configuration file: \<name\>.3mf" is thrown in exactly two places, both in
  `PresetBundle::load_config_file_config`
  (`src-orca/src/libslic3r/PresetBundle.cpp:5367` and `:5429`). It is **not** raised
  by the 3MF importer itself: a `project_settings.config` that fails to parse only
  records a non-fatal importer error
  (`src-orca/src/libslic3r/Format/bbs_3mf.cpp:2746-2768`, `add_error(...)`, load
  continues). CONFIRMED by reading.
- "The file does not contain any geometry data." is shown by the desktop GUI when a
  load commits zero objects
  (`src-orca/src/slic3r/GUI/Plater.cpp:10225-10232`). When the config load above
  throws, the GUI load aborts before the model is committed, so the geometry warning
  is the *downstream symptom* of the config throw, not evidence that the file lacks
  geometry. CONFIRMED by reading.

So the reported pair of messages points at the config the app writes, not at the
mesh data. The checks below are every load-time check found that the app's export
could plausibly trip, in the order the desktop applies them.

## The app's export path

- Kotlin: `Engine.saveProject(path)` (`android/app/src/main/java/app/orcaandroid/core/Engine.kt:216`),
  called from `FileController` (`android/app/src/main/java/app/orcaandroid/ui/FileController.kt:228,265`).
- JNI: `OrcaEngine::save_project` (`android/core/jni/OrcaProject.cpp:155`) builds the
  config via `selection_config()` (`android/core/jni/OrcaEngine.cpp:456`, a
  `PresetBundle::full_config()` of the selected printer/print/filaments plus edits),
  calls `match_filament_indices(config)` (`OrcaProject.cpp:160`), and stores with
  `store_bbs_3mf` (`OrcaProject.cpp:191`) using strategy
  `Silence | SplitModel | ShareMesh | Zip64` (`OrcaProject.cpp:190`) — the same
  exporter the desktop itself saves with (`src-orca/src/libslic3r/Format/bbs_3mf.cpp:9452`).
  `export_gcode_3mf` (`OrcaProject.cpp:266-329`) takes the same path plus
  `WithGcode | SkipModel | WithSliceInfo`; `SkipModel` there is by design (a
  .gcode.3mf is a print file) and is not the project-save bug.

## Checks the desktop applies on load

### Config validation (`PresetBundle::load_config_file_config`)

1. **`filament_colour` must be non-empty.** `num_filaments` is taken from
   `filament_colour` (`PresetBundle.cpp:5363-5365`); zero throws "Invalid
   configuration file" (`:5366-5367`).
   App export: the config is a `full_config()`, which carries the printer/filament
   presets' `filament_colour`, and `selection_config()` itself dereferences
   `filament_colour` without a null check (`OrcaEngine.cpp:509`), so an absent key
   would fail inside the app first. `match_filament_indices` returns early when the
   key is missing/empty (`OrcaProject.cpp:138-140`) — the c9d7fd9 fix does **not**
   heal this case, it just doesn't need to in practice. **OK in practice; SUSPECTED
   only for a filament preset that carries no `filament_colour` at all.**

2. **`filament_self_index` / `filament_extruder_variant` length vs the filament
   count — the c9d7fd9 check.** Only when the config carries `extruder_variant_list`:
   `filament_extruder_variant`'s size must equal `filament_self_index`'s size and be
   at least `num_filaments`, else "Invalid configuration file"
   (`PresetBundle.cpp:5421-5429`). The reader's own healing before that comparison
   is only partial (`:5407-5420`): a *missing* `filament_self_index` is created at
   `num_filaments`, but a present one is never resized; a short
   `filament_extruder_variant` is grown to `num_filaments`, but a longer one is
   never shrunk. So a config whose two vectors disagree after healing still throws
   — the pre-c9d7fd9 app config was exactly that shape (a one-entry
   `filament_self_index` next to several filaments, per the comment at
   `OrcaProject.cpp:133-135`).
   App export: c9d7fd9 (`match_filament_indices`, `OrcaProject.cpp:136-151`) sizes
   **both** vectors to the `filament_colour` count, and it is called by both export
   paths (`:160`, `:269`), so after it the two sizes agree by construction.
   **OK by reading — this is exactly the throw the earlier fix targets — but the fix
   remains UNVERIFIED: no app-saved 3MF has been opened in desktop Orca as part of
   either that change or this review.**

3. **Side effect of the c9d7fd9 sizing (not a load failure).** The reader treats a
   `filament_self_index` longer than `num_filaments` as the multi-extruder variant
   list and builds `filament_variant_index` from it (`PresetBundle.cpp:5431-5443`).
   `match_filament_indices` rewrites *any* size mismatch to `1..n`
   (`OrcaProject.cpp:143-147`), so on a multi-nozzle printer whose legitimate
   `filament_self_index` has one entry per extruder variant (more than the filament
   count), the app export silently replaces the real mapping with the identity.
   The file still loads; the mapping is wrong. **SUSPECTED correctness issue,
   separate from the reported rejection.**

4. **Variant names invented by the fix.** A short `filament_extruder_variant` is
   padded with the first existing entry or the literal `"Direct Drive Standard"`
   (`OrcaProject.cpp:148-150`). The reader never validates variant names against
   the printer's `extruder_variant_list` on this path (other per-variant vectors
   are merely resized, `PresetBundle.cpp:5554-5555`), so a wrong name cannot cause
   the rejection — it could still select the wrong variant downstream.
   **OK for the reported error; SUSPECTED (low) for variant correctness.**

5. **Every other per-filament vector in this function is healed, not checked.**
   `compatible_printers` / `compatible_process` condition groups, `inherits_group`,
   `different_settings_to_system` and `filament_ids` are all `resize()`d to the
   filament count without throwing (`PresetBundle.cpp:5386-5390`). The app's
   `full_config()` may carry any of them at the preset's length; the desktop does
   not reject for that. **OK.**
   One sharp edge in the same branch: `nozzle_diameter` is dereferenced without a
   null check (`PresetBundle.cpp:5423`). The app's config always carries it from
   the printer preset. **OK.**

6. **App-only keys in the written config.** `selection_config()` adds a resolved
   `plugins` manifest (`OrcaEngine.cpp`, `config.update_plugin_manifest()` at the
   end of the function) and any active calibration overrides. No load check found
   rejects unknown keys: the importer's JSON load tolerates them (forward-
   compatibility substitutions are disabled at `bbs_3mf.cpp:1658`, and failures
   there are non-fatal per the summary above), and `load_config_file_config`
   distributes only the keys it knows. **SUSPECTED (low)** only because a desktop
   build older than the plugin system was not available to test against.

### Archive / geometry checks (`_BBS_3MF_Importer`)

7. **Geometry file presence.** The exporter writes `3D/3dmodel.model`
   unconditionally when not skipping the model (`bbs_3mf.cpp:6501-6508`; the name
   is defined at `:209`). `save_project` does not set `SkipModel`
   (`OrcaProject.cpp:190`), so an app project always contains the model file —
   written by the same code the desktop saves with. **OK.** The one exception is
   an empty scene: `save_project` has no empty-model guard (contrast `slice()`,
   which throws "This plate is empty"), so saving a project with no objects writes
   a genuinely geometry-free file and the desktop warning is then correct.
   **SUSPECTED edge case, not the reported bug.**

8. **`Metadata/model_settings.config` must parse.** A malformed one fails the whole
   load (`bbs_3mf.cpp:1661-1666`). The app does not write this file itself; the
   shared exporter generates it from the model (`bbs_3mf.cpp:8392`). **OK.**

9. **`Metadata/project_settings.config` must exist for config to load at all** (it
   is matched by name at `bbs_3mf.cpp:1656` / `:2012` and written by the exporter
   at `:7997-7998` from the `StoreParams.config` the app supplies). Missing it
   would not raise "Invalid configuration file" — the desktop would load with
   default presets instead — so it does not explain the report. **OK.**

10. **Version fields do not gate loading in this reader.** The 3MF version
    rejection in `_handle_end_metadata` is commented out (`bbs_3mf.cpp:4062-4073`),
    and so are all three painting-version checks that call
    `check_painting_version` (`:4092-4102`; the helper at `:4057` is unused).
    Whatever versions the app's exporter writes are accepted here. **OK.**

11. **Plate data.** The app builds one `PlateData` per plate with
    `objects_and_instances` for the instances on that plate
    (`OrcaProject.cpp:168-182`) and a per-plate `curr_bed_type` set with
    `set_deserialize_strict` (`:180`). The importer rebuilds the plate list from
    the exported plate files and maps objects through `obj_inst_map`
    (`bbs_3mf.cpp:2370-2404`); no check was found that rejects an unknown bed-type
    string or an instance that sits on no plate (such an instance is in the model
    file but in no plate's list). **OK for load; SUSPECTED (low) that off-plate
    instances may land differently on the desktop than in the app.**

12. **Thumbnails.** The app renders one 512x512 thumbnail per plate
    (`OrcaProject.cpp:184-189`). No load check reads them. **OK.**

## Conclusion

- The only two hard rejections in the desktop's config load are findings 1 and 2.
  Finding 2 is the one c9d7fd9 addresses, and by reading, the fix makes the two
  vectors agree for both export paths. Finding 1 is not reachable through the
  app's normal selection path. **No further cause is certain from reading, so this
  PR is review-only, as the task allows** — the remaining items are OK or
  SUSPECTED, and "fixing" any of them blind would be guessing.
- What would settle it: save a project in the app (multi-filament, so
  `extruder_variant_list` is present), open that exact file in desktop Orca, and —
  if it still fails — unzip it and compare `filament_colour`,
  `filament_self_index` and `filament_extruder_variant` lengths inside
  `Metadata/project_settings.config` against findings 1-2 before changing code.
  For a multi-nozzle printer, also diff `filament_self_index` before/after
  `match_filament_indices` (finding 3).
- UNVERIFIED: everything above is code reading. No app build, no desktop build, no
  real 3MF file was exercised; the c9d7fd9 fix in particular has still never been
  observed working.
