# Review 0001 — native paint-depth patch (Task 1, review-only)

Scope: `android/patches/src-orca/0001-paint-depth-layers.patch` against the code it patches in
`src-orca` at the pinned submodule commit `f8dd56053c52c729eab3acf871f1b75451eaeabb`
(`src/libslic3r/MultiMaterialSegmentation.cpp`, `PrintConfig.cpp/.hpp`, `Preset.cpp`,
`PrintObject.cpp`), plus the Android side in
`android/app/src/main/java/app/orcaandroid/ui/prepare/PaintDepth.kt`.
Line numbers below are the pre-patch files in `src-orca` unless a patch hunk is named.
No code was changed by this review.

Intended semantics (from MUSE-TASKS.md): 0 = same as top/bottom shell layers,
N = N layers, -1 = all the way through; below the shells the inset stays constant
instead of growing.

## Findings

### 1. CONFIRMED — the zero-shell gate fix is necessary, and it is per-side correct
`segmentation_top_and_bottom_layers` only projects painted facets at all when
`max_top_layers > 0 || max_bottom_layers > 0`
(`MultiMaterialSegmentation.cpp:1268`, gate; the maxima come from region shell layers
at `:1250-1256`). With 0 shell layers and no patch, painted top/bottom surfaces would
never reach `top_raw`/`bottom_raw`, so a paint depth could never take effect. The
patch's first hunk bumps each maximum to at least 1 only when that side's setting is
non-zero, so top and bottom stay independent — the same independence the
`slice_mesh_slabs` calls already have (`max_top_layers > 0 ? &top : nullptr`, `:1296`/`:1300`).
The maxima are used nowhere else in the function except this gate and those pointer
checks, so bumping them to 1 (not to N) does not truncate the depth: the depth is
driven by the per-surface loops, finding 3.

### 2. CONFIRMED — setting 0 reproduces the original code exactly
The `paint_depth` lambda (patch, inserted in `layer_color_stat` after
`MultiMaterialSegmentation.cpp:1421`) returns the stat's shell layers for setting 0,
which is exactly what both loops used before (`stat.top_shell_layers` at `:1442`,
`stat.bottom_shell_layers` at `:1462`). The offset-freeze condition is also inert in
this case: with paint == shell, every loop step has distance `d <= paint - 1 < max(shell, 1)`,
so the offset decrements on every step just as the original did. No behaviour change
for existing profiles.

### 3. CONFIRMED — depth counting matches the shell convention, symmetric top/bottom
N counts the painted surface layer itself. The top loop visits `paint - 1` layers
below the surface (patch hunk at `MultiMaterialSegmentation.cpp:1442`: `last_idx`
from `layer_idx - 1` down to `layer_idx - paint + 1` exclusive bound), the bottom
loop visits `paint - 1` layers above it (hunk at `:1462`). That is the same counting
`top_shell_layers` uses — its own tooltip says the count includes the top surface
layer (`PrintConfig.cpp:7584`). Top and bottom are mirror images, including the edge
behaviour: at layer 0 the top loop runs zero times, at the last layer the bottom
loop runs zero times.

### 4. SUSPECTED — freeze level is one step deeper than the patch's own comment says (low)
Patch comment: "Below the shells (a deeper paint depth) the inset stays where the
shells left it." The condition (both loops) is
`distance <= std::max(shell_layers, 1)`, so the inset at distance `d` is
`min(d, max(shell, 1))` steps of `extrusion_spacing + extrusion_width`. The deepest
shell layer itself (`d = shell - 1`) carries `shell - 1` steps; the first below-shell
layer (`d = shell`) takes one more step, then it freezes. So the code freezes one
step deeper than "where the shells left it" reads. Against that, the code and the
new top tooltip ("tapers inwards over the first top shell layers and keeps that
inset below them" — taper over `shell` layers counted below the surface) are
self-consistent, and the behaviour is symmetric top/bottom. Looks like the comment
is the loose part, but the author should confirm which is intended: freeze at
`shell - 1` steps (condition `d < shell`, plus the `max(..., 1)` special case) or
freeze at `shell` steps (fix the comment). Visual impact either way is a single
spacing+width step, and only on layers below the shells.

### 5. CONFIRMED — objects thinner than N layers are safe
Both loops hard-clamp: top at layer 0 (`std::max(..., int(0))`), bottom at
`num_layers`. For -1 the depth resolves to the full layer count and the same clamps
apply. Where top and bottom projections land on the same layer of a thin object,
they go through the existing merge path (projections gathered per target layer,
then unioned, then trimmed against the painted regions in colour order) — the patch
adds no new overlap path. `layer_slices_trimmed` keeps intersecting every layer
between surface and target in both directions, unchanged from the original.

### 6. CONFIRMED — -1 and the int casts are safe
Any negative setting is treated as "through" (the lambda tests `setting < 0`); the
config def sets `min = -1`, and a hand-edited profile value below -1 behaves the
same, harmless. `int(num_layers)` in the lambda captures `layers.size()`; realistic
layer counts are nowhere near `INT_MAX`. The top bound computes
`int(layer_idx) - paint_layers` in int arithmetic: with `layer_idx >= 0` and
`paint_layers <= INT_MAX` the result cannot underflow past `INT_MIN + 1`, and it is
clamped at 0. The bottom bound adds in `size_t` and clamps at `num_layers`. The
patch's top bound is also strictly cleaner than the original
`int(layer_idx - stat.top_shell_layers)`, which subtracted in `size_t` and then cast.

### 7. CONFIRMED — the keys are registered everywhere the sibling MMU keys are
- Definition: `PrintConfig.cpp`, patch inserts both defs (coInt, default 0,
  `min = -1`, no max — the same pattern as `top_shell_layers` at `:7580-7587`)
  immediately after `mmu_segmented_region_interlocking_depth` and before
  `interlocking_beam`, i.e. with the other MMU segmentation defs (`:4808-4827`).
- Storage: `PrintConfig.hpp`, both members added to `PrintObjectConfig` next to
  `mmu_segmented_region_max_width` / `mmu_segmented_region_interlocking_depth`
  (`:1193-1194` pre-patch neighbours, class defined at `:1172`). Object scope
  matches how the Android UI writes them (finding 10).
- Preset handling: `Preset.cpp`, both keys added to `s_Preset_print_options`
  right after `mmu_segmented_region_interlocking_depth` (`:1446-1448`), so they
  are saved, inherited and diffed like the sibling keys.
So the values persist in profiles/projects and round-trip by name through the
normal dynamic-config mechanism.

### 8. CONFIRMED — changing the value invalidates the right step
`PrintObject.cpp`: the patch adds both keys to the same branch of
`invalidate_state_by_config_options` as `mmu_segmented_region_max_width` and
`mmu_segmented_region_interlocking_depth` (`:1313-1316`), and that branch enqueues
`posSlice`. Multi-material segmentation runs inside the slice step, so changing
only a paint-depth value re-runs segmentation — no stale segmented layers.

### 9. CONFIRMED — the patch applies cleanly to the pinned submodule
The verbatim command from MUSE-TASKS.md, run from the repo root of a full local
clone with the submodule checked out at the gitlink recorded on `muse/work`
(`f8dd56053c52c729eab3acf871f1b75451eaeabb`):
`git -C src-orca apply --check ../android/patches/src-orca/0001-paint-depth-layers.patch`
exits 0, checking all five files. The patch's context lines and line numbers match
the pinned sources everywhere this review cites them.

### 10. CONFIRMED — Android UI semantics match the native side (PaintDepth.kt)
- Top/bottom rows write `mmu_top_paint_layers` / `mmu_bottom_paint_layers` as
  per-object settings (`PaintDepth.kt:36-37`, `setObjectSetting`), matching the
  `PrintObjectConfig` scope of the native keys.
- 0 removes the key (`set()` passes null at `:46-47`), so the native default 0
  (follow shells) applies — no stale explicit 0 stored.
- The slider bounds N to 1..40 (`:61`); -1 is only reachable via the "through"
  chip. The native side has no max, so a hand-edited profile above 40 still works
  and is clamped by the loop bounds (finding 6). No mismatch found.
- Values are stored as integer strings and parsed back via `toFloatOrNull` +
  `roundToInt` (`:44`, `:47`), consistent with the coInt native type.

### 11. SUSPECTED — cosmetic: bottom tooltip omits the taper sentence
The top def's tooltip documents the taper/freeze behaviour; the bottom def's
tooltip (both in the patch's `PrintConfig.cpp` hunk) stops at "-1 goes all the
way through the part." Same behaviour, less documentation. Trivial fix if the
patch is ever regenerated.

### 12. CONFIRMED — N smaller than the shells is an exact override, per spec
With `0 < N < shell layers`, `paint_depth` returns N and the projection stops at
N layers — shallower than the old shell projection. That follows the written
spec ("0 = same as shell layers, N = N layers"; tooltip: "0 follows the top
shell layers"), so N is an exact depth, not a minimum. Flagged only because a
reader could expect "at least N": behaviour matches the documentation, no
change recommended.

## UNVERIFIED
- No slicing was run: there is no toolchain in this environment (UNCOMPILED per
  MUSE-TASKS.md), so all findings are from reading the patch against the pinned
  sources, not from observed output. Runtime geometry of the freeze (finding 4)
  in particular has not been seen in a sliced result.
- The object-settings bridge between the Kotlin UI and the native config
  (how `setObjectSetting` applies keys natively) was not inspected; finding 10
  covers the Kotlin call site and the native key registration only.
