# Muse tasks (written by Claude, 2026-10-22)

Repo: `BaIanced/OrcaStudio-Android` (Android port of OrcaSlicer, Kotlin + Compose, native C++ via JNI). The repo is
PUBLIC: every push is publication, and a later commit does not remove anything from history.

## Status
- Tasks 1-4 done and merged (PRs #37-#40). Task 4's premise was stale (`log_tail` existed); done as the clamp extraction.
- **Tasks 5-8 approved by Vincent 2026-10-22.** Work in order, one PR each. Branch each task from the latest `muse/work`
  after the previous PR is merged; if it isn't merged yet, branch from the previous task branch (stacked) and say so.
- **Open question for Vincent (undecided):** install JDK 21 + Android SDK (platform + build-tools, no NDK) once in
  Muse's VM, so Muse compiles and runs the unit tests before pushing? Cost: setup time/quota, reinstall if the VM is
  replaced. Until Vincent decides, PRs are `UNCOMPILED` and `pr-check.yml` plus Vincent's local run are the first test.

## Branches, PRs, promotion
- Clone: `git clone --recurse-submodules --shallow-submodules https://github.com/BaIanced/OrcaStudio-Android.git`
  (`src-orca/` is the OrcaSlicer submodule; Task 1 and 2 read it).
- One branch per task: `muse/task-<n>-<slug>`, cut from the latest `origin/muse/work`. Muse pushes only to its own
  `muse/task-*` branches. Never push to `muse/work`, `main`, `claude/*` or any other branch. Never force-push.
- One **draft** PR per task, base `muse/work`. Never base `main` or `claude/*`.
- Every connector write needs Vincent's approval: batch each task into one multi-file push and one PR creation.
- Vincent merges a PR into `muse/work` only after the local check (compile, unit tests, diff read).
- Promotion: verified code commits are cherry-picked by Claude onto the active `claude/*` work branch and reach `main`
  through the normal merge there. `muse/work` itself is never merged into `main`: it carries this file and
  `.github/workflows/pr-check.yml`, which stay off `main`.

## Hard rules
- **Never change anything under `.github/`.** A workflow file in the diff is an automatic reject, unread.
- Do not edit: `src-orca/` (upstream, must stay unmodified), `android/patches/`, `docs/handoff/`, `.claude/`.
- No credentials, tokens, keys or keystores in files, commits, PR text or logs.
- No identifying details in files, commits or PR text (IPs, serials, e-mails, account names, device models). Use
  placeholders.
- No device access, no `adb`. No network calls from code or tests.
- Match the surrounding code style: naming, comment density, idioms. No new dependencies.
- Write code with a file-edit tool, not shell heredocs (they have eaten backslashes before).
- If a task is blocked after 2 honest tries, write why in the log and PR, and move on. A failed task logged clearly is
  worth more than a wrong one that looks done.

## Before every push (in order, read the exit codes, don't grep them away)
1. Privacy self-scan from the repo root (same script the `privacy scan` workflow runs; the workflow is only the
   backstop, by then the leak is public). Both must exit 0:
   ```
   python3 .claude/hooks/privacy_scan.py tree . ':(exclude)src-orca'; echo "exit $?"
   python3 .claude/hooks/privacy_scan.py range origin/muse/work..HEAD . ':(exclude)src-orca'; echo "exit $?"
   ```
   A hit: fix the content and rewrite the local commit before anything is pushed. Never add to `privacy_allow.txt`.
2. Only if the toolchain is installed (see open question), both must exit 0:
   ```
   cd android
   ./gradlew --console=plain -q :app:compileReleaseKotlin; echo "exit $?"
   ./gradlew -q :app:testDebugUnitTest; echo "exit $?"
   ```
   Build output and test results: `~/build/orca-android/gradle/app/` (`test-results/`). Existing test examples:
   `android/app/src/test/java/app/orcaandroid/`.
3. `git diff --name-only origin/muse/work..HEAD` has nothing under `.github/`, `src-orca/`, `android/patches/`,
   `docs/handoff/`, `.claude/`.

## Commits
`<area>: <what>`, one commit per task (review-only tasks: the review file plus the log entry), with a trailer line
`Muse-Task: <n>`.

## PR description (required, copy and fill in)
```
Task <n>: <title>

Build: COMPILED (exit codes: compile <x>, tests <y>; <passed>/<total> tests) | UNCOMPILED (no toolchain)
Tests added/changed: <list or none>
Privacy self-scan: tree exit <x>, range exit <y>
Touched paths: <list>; nothing under .github/, src-orca/, android/patches/, docs/handoff/, .claude/: yes
UNVERIFIED: <each claim not checked by compile/tests/reading the code, or none>
Open questions: <or none>

- [ ] compiled, or marked UNCOMPILED
- [ ] unit tests run, or marked not run
- [ ] UNVERIFIED items listed
- [ ] privacy self-scan done, both exit 0
- [ ] no .github/ changes
- [ ] log entry appended to MUSE-TASKS.md
```

## Task 1: review the native paint-depth patch (read-only, no code change)
Read `android/patches/src-orca/0001-paint-depth-layers.patch` and the code it patches in
`src-orca/src/libslic3r/MultiMaterialSegmentation.cpp` (around :1254 and :1410) plus where `mmu_top_paint_layers` /
`mmu_bottom_paint_layers` are defined (PrintConfig.cpp/.hpp) and `android/app/src/main/java/app/orcaandroid/ui/prepare/PaintDepth.kt`.
Intended: 0 = same as top/bottom shell layers, N = N layers, -1 = all the way through; below the shells the inset stays
constant instead of growing. Attack it: off-by-one in layer ranges, -1 / int casts, objects thinner than N layers, top vs
bottom symmetry, are the keys serialized and do they invalidate the right step (does changing the value re-run
segmentation?), does the patch still apply cleanly (`git -C src-orca apply --check ../android/patches/src-orca/0001-paint-depth-layers.patch`
from `OrcaStudio-Android`, check only, never apply). Write findings to `MUSE-REVIEW-0001.md` at the repo root (in the
task PR), each with file:line and CONFIRMED or SUSPECTED.

## Task 2: colour-change + waste estimate (pure Kotlin + unit tests, no UI)
New file `android/app/src/main/java/app/orcaandroid/core/ColourChangeEstimate.kt`, tests in
`android/app/src/test/java/app/orcaandroid/core/ColourChangeEstimateTest.kt`. No Android imports (plain JVM).
First read how the profile stores flush data: `src-orca/src/libslic3r/PrintConfig.cpp:7686-7715`
(`flush_volumes_matrix`, `flush_volumes_vector`, `flush_multiplier`, all coFloats) and grep `flush_volumes_matrix` in
`src-orca/src/libslic3r/` to learn the layout (row-major from->to, mm3; multi-nozzle printers may concatenate one matrix
per nozzle: support the single-nozzle n*n case, and for longer arrays use the first n*n block and say so in a KDoc line).
API (adjust names to the codebase style):
- `parseFlushMatrix(values: List<Double>, filamentCount: Int): Array<DoubleArray>?` (null if too short / n < 1).
- `estimate(layers: List<List<Int>>, matrix, multiplier: Double, densityGPerCm3: List<Double>, startFilament: Int?): Estimate`
  where `layers[i]` = 0-based filament ids printed on layer i (unordered, may repeat, may be empty).
  Ordering per layer: start with the filament left loaded from the previous layer if it is in this layer, then greedily
  pick the next filament with the smallest flush from the current one (ties: lower id). Count a change for each switch
  (including the first layer if `startFilament` differs). Flush mm3 = matrix[from][to] * multiplier; grams uses the
  density of the filament being flushed **to** (mm3 / 1000 * g/cm3).
- `Estimate(changes: Int, flushMm3: Double, flushGrams: Double, perFilamentGrams: DoubleArray)`.
- Out-of-range ids: ignore that id, don't throw.
Tests (each its own @Test): single colour = 0 changes; two colours alternating every layer; one layer with 3 colours
(greedy order checked against a hand-computed matrix); previous filament reused at layer start; empty layers keep the
loaded filament; startFilament null vs set; multiplier 0 and 1.5; asymmetric matrix (A->B != B->A); malformed matrix
-> null; out-of-range id ignored. Put the hand computation for each expected number in a comment.

## Task 3: unit tests for existing pure logic (tests only, no main-code changes)
Find 2-3 pure, side-effect-free functions in `android/app/src/main/java/app/orcaandroid/render/` or `ui/` that have no
tests yet (e.g. view cube hit/cell math in `ui/components/ViewCube.kt`, camera preset math in `render/Camera.kt`,
scale gizmo math in `ScaleGizmo.kt`). Only test what is already `internal`/public and pure; if a function needs a
visibility change, skip it and note it in the log instead. Assert behaviour the code actually has; if you think the code
is wrong, don't change it: write a disabled test (`@Ignore("suspected bug: ...")`) and log it.

## Task 4 (optional, only if 1-3 are done and green): MCP `log_tail` tool
Look at how existing debug tools are registered and tested (`McpDebugToolsTest.kt`, grep `McpTools` / `McpDebug`).
Add `log_tail` returning the last N lines (default 100, max 500) of the app's own logcat (`logcat -d -t N --pid <own pid>`)
following the same structure, with a test of the argument clamping. If the structure makes this non-trivial, stop and
describe the plan in the log instead of half-building it.

## Task 5: G-code tool-change counter (pure Kotlin + unit tests, no UI)
Cross-check source for Task 2's estimate. New `android/app/src/main/java/app/orcaandroid/core/GcodeToolChanges.kt`, tests in
`android/app/src/test/java/app/orcaandroid/core/GcodeToolChangesTest.kt`, plain JVM. Read how OrcaSlicer writes tool changes
first (grep `toolchange_gcode`, `; CHANGE_LAYER`, `; FEATURE: Prime tower`, `M620 S` in `src-orca/src/libslic3r/GCode*.cpp` and
the Bambu profiles' `change_filament_gcode` under `src-orca/resources/profiles/BBL/machine/`), and write the markers you rely on
into the KDoc with file:line. API (adjust to the codebase style): `parse(lines: Sequence<String>): Result` with
`changes: Int`, `perLayer: List<List<Int>>` (filament ids in print order per layer, same shape Task 2's `estimate` takes) and
`primeTowerMm3: Double` (extrusion inside prime-tower feature blocks, from E deltas, absolute and relative E; assume 1.75 mm
filament, say so). Must handle: Bambu-style `M620 S<n>A` / `T<n>` changes, plain `T<n>`, `T255`/`T1000`-style no-op ids
(ignore), comments, `G92 E0` resets. Tests use small hand-written G-code fixtures as strings (no files from printers, no
real user G-code). One test feeds `perLayer` into `ColourChangeEstimate.estimate` to show the shapes fit.

## Task 6: 3MF export that desktop Orca rejects (investigation, read-only unless trivial)
Open bug: a 3MF saved by the app fails in desktop OrcaSlicer with "Invalid configuration file: <name>.3mf" and
"no geometry data". An earlier fix (commit c9d7fd9, sizes `filament_self_index` / `filament_extruder_variant` to the filament
count) is untested. Read the app's export path (`android/core/jni/OrcaProject.cpp`, grep `store_bbs_3mf` / `store_3mf`; Kotlin
side: grep the JNI call in `core/Engine.kt`) and the desktop's reader (`src-orca/src/libslic3r/Format/bbs_3mf.cpp`: where it
raises "Invalid configuration file" and where "no geometry data" comes from; `PresetBundle` / config validation it runs on load).
List every check the desktop applies on load that the app's export could fail (config keys whose vector length must match the
filament/extruder count, required metadata, version fields, plate/model files), each with file:line and whether the app's
export satisfies it: CONFIRMED / SUSPECTED / OK. Write `MUSE-REVIEW-0006.md`. If one cause is certain and the fix is a few lines
outside `src-orca/`, you may include it in the same PR with a test if one is possible; otherwise review only.

## Task 7: paint mode resets to Fill after Preview (small bug fix)
In the paint shop (colour painting), switching to Preview and back resets the chosen mode (Brush / Fill / Bands / Object) to
Fill. Find where the mode is held (grep the paint panel / paint shop composables under `android/app/src/main/java/app/orcaandroid/ui/`)
and why it resets (state created inside a composable that leaves composition, `remember` vs `rememberSaveable` / view-model,
or an explicit reset on workspace change). Fix it with the smallest change that keeps the mode for the session (not across app
restarts). If the logic can be pulled into something pure, add a unit test; otherwise say in the PR exactly how Claude should
check it on a device (steps). Mark device behaviour UNVERIFIED.

## Task 8: unit tests for more pure logic (tests only, no main-code changes)
Same rules as Task 3. Look in `core/`, `net/AmsMapping.kt` and `ui/prepare/` for pure, already-public/internal functions with
no tests (grep the test folder first to avoid duplicates). 2-4 functions; assert actual behaviour; suspected bugs become
`@Ignore("suspected bug: ...")` tests plus a log line.

## Log
Append-only, one entry per PR (also for PRs that get closed unmerged), newest last. Never edit earlier entries.
Format: `### Task <n> - <date> - <branch> - <commit>` then build/test result or UNCOMPILED, privacy scan exit codes,
UNVERIFIED items, open questions.

### Task 1 - 2026-10-22 - muse/task-1-paint-depth-review - this commit
Review-only, no code changed. Findings in `MUSE-REVIEW-0001.md`: 12 items, 10 CONFIRMED, 2 SUSPECTED.
Headline: the zero-shell gate fix, depth counting, serialization and invalidation all check out;
one SUSPECTED item — the offset freeze sits one step deeper than the patch's own comment says
(finding 4, low, symmetric top/bottom, author to confirm intent).
Build: UNCOMPILED (no toolchain in Muse's VM; review-only task, nothing to compile).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: no slicing run (no toolchain), so no finding rests on observed output; the
object-settings bridge between the Kotlin UI and the native config was not inspected (Kotlin
call site and native key registration only). `git apply --check` passed with the verbatim
command from this file in the full local clone (exit 0).
Open questions: JDK/Android SDK install in Muse's VM (see Status, Vincent's call); finding 4 —
is the comment or the freeze condition the one that is off by one step?

### Task 2 - 2026-10-22 - muse/task-2-colour-change-estimate - this commit
New pure-Kotlin `ColourChangeEstimate` (`core/ColourChangeEstimate.kt`): parses the flat
`flush_volumes_matrix` (row-major from->to, first n x n block only for multi-nozzle values,
null when too short or n < 1) and estimates switches per layer (loaded filament first when
reused, then greedy smallest-flush, ties to the lower id; grams use the density of the
filament flushed to). 13 unit tests in `core/ColourChangeEstimateTest.kt`, every expected
number hand-computed in a comment and cross-checked against a line-by-line mirror of the
algorithm before pushing.
Build: UNCOMPILED (no toolchain in Muse's VM; Vincent's JDK/SDK call still open).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: not compiled and tests not run (no toolchain); `pr-check.yml` and Vincent's
local run are the first execution. Density-missing filaments contribute mm3 but 0 g
(documented in the KDoc); that case is a design choice, not covered by a test.
Open questions: JDK/Android SDK install in Muse's VM (see Status, Vincent's call).

### Task 3 - 2026-10-22 - muse/task-3-pure-logic-tests - this commit
Tests only, no main-code changes. New `render/CameraTest.kt` (11 tests: defaults, presets,
orbit/zoom/aim clamps, frame, fit, focus yaw snap, eye/towardsEye, clip distances, pan,
state/restore) and `render/ScaleGizmoTest.kt` (6 tests: pivot, per-axis scale, colours,
handle visibility from a side camera with and without axes, zero-reach ratio). Expected
values hand-computed in comments; the visibility scenario and pan/frame/fit numbers were
cross-checked against a line-by-line mirror of the algorithms before pushing.
Skipped: `ui/components/ViewCube.kt` — its cell/hit helpers (`basis`, `cells`, `hit`) are
all `private`, and the task rule is to note that instead of changing visibility. Nothing
looked wrong in the code covered, so there are no `@Ignore`d suspected-bug tests.
Build: UNCOMPILED (no toolchain in Muse's VM; Vincent's JDK/SDK call still open).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: not compiled and tests not run (no toolchain); `pr-check.yml` and Vincent's
local run are the first execution. The GL-matrix paths (`viewProjection`, `ray`, `project`)
are deliberately untested here: they need `android.opengl.Matrix`, which a plain JVM unit
test cannot run.
Open questions: JDK/Android SDK install in Muse's VM (see Status, Vincent's call).

### Task 4 - 2026-10-22 - muse/task-4-mcp-log-tail - this commit
`log_tail` already exists and was not rebuilt: it was added 2026-10-19 (commit 6fa7eaa,
with `LogMask` masking and tests in 6513dd1), before this task file was written. As found
on `muse/work` it is registered in `ai/McpTools.kt` with `lines` (1..500, default 100),
`filter` and `level` arguments, reads its own process's logcat
(`logcat -d -v time --pid=<own pid> *:<level>`, then takes the last N lines rather than
the `-t N` the task text suggested), and masks secret-looking values. The one piece the
task asked for that was missing is a test of the argument clamping, which was inline in
the private `logTail` and so untestable. This PR makes the smallest change that fixes
that: the clamp moves verbatim into a pure `ai/LogTail.kt` (`DEFAULT_LINES` 100,
`MAX_LINES` 500, `lineCount(requested)` = coerceIn(1, 500)), `logTail` reads it, and
`McpDebugToolsTest` gains `logTailLineCountClamps`. Behaviour is unchanged.
Build: UNCOMPILED (no toolchain in Muse's VM; Vincent's JDK/SDK call still open).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: not compiled and tests not run (no toolchain); `pr-check.yml` and Vincent's
local run are the first execution. The equivalence claim (clamp behaviour unchanged)
rests on reading the two one-line expressions, not on a run.
Open questions: JDK/Android SDK install in Muse's VM (see Status, Vincent's call);
Task 4's premise in this file is stale — Claude may want to strike it or mark it done.

### Task 5 - 2026-10-22 - muse/task-5-gcode-tool-changes - this commit
New pure-Kotlin `GcodeToolChanges` (`core/GcodeToolChanges.kt`): parses G-code lines into
tool changes, per-layer filament lists (the shape `ColourChangeEstimate.estimate` takes)
and prime-tower extrusion volume. Markers were read from the sources first and are cited
in the KDoc with file:line: `; CHANGE_LAYER` and `; FEATURE: Prime tower` reserved tags,
`GCodeWriter::toolchange` (`T<n>`, BBL `M1020 S<n>`, manual-change comment), and the BBL
profiles' `change_filament_gcode` (`M620 S<n>A` / `T<n>`, `T255` unload). Bambu triple
selections count once, ids >= 255 are ignored, comments never select, absolute and
relative E are both tracked with `G92 E0` resets, and the volume assumes 1.75 mm
filament (stated in the KDoc). 13 unit tests in `core/GcodeToolChangesTest.kt`, all
fixtures hand-written strings; every expected value was cross-checked against a
line-by-line mirror of the parser before pushing, which caught one wrong fixture
(absolute-E reference after a pre-tower move). One test feeds `perLayer` into
`ColourChangeEstimate.estimate` and checks both count the same switches.
Stacked on `muse/work` (Tasks 6-8 branch from this branch in turn).
Build: UNCOMPILED (no toolchain in Muse's VM; Vincent's JDK/SDK call still open).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: not compiled and tests not run (no toolchain); `pr-check.yml` and Vincent's
local run are the first execution. No real printer G-code was parsed (fixtures only, by
design); arc moves (`G2`/`G3`) with E are not tracked.
Open questions: JDK/Android SDK install in Muse's VM (see Status, Vincent's call).

### Task 6 - 2026-10-22 - muse/task-6-3mf-export-review - this commit
Investigation only, no code changed. Findings in `MUSE-REVIEW-0006.md`: 12 checks the
desktop applies on load, each with file:line and CONFIRMED / SUSPECTED / OK against the
app's export. Headline: "Invalid configuration file" is thrown in exactly two places
(both in `PresetBundle::load_config_file_config` — empty `filament_colour`, and the
`filament_self_index` / `filament_extruder_variant` length mismatch), and "no geometry
data" is the downstream symptom when that throw aborts the GUI load, not evidence of
missing geometry. The c9d7fd9 fix targets the second throw and, by reading, makes the
two vectors agree on both export paths; it stays UNVERIFIED (no app 3MF has been opened
in desktop Orca). One new SUSPECTED correctness item: the fix rewrites any mismatched
`filament_self_index` to the identity, which would silently replace a legitimate
multi-nozzle variant mapping. No further cause is certain, so no fix is included.
Stacked on `muse/task-5-gcode-tool-changes` (PR # for Task 5 not merged yet).
Build: UNCOMPILED (no toolchain in Muse's VM; review-only task, nothing to compile).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: no app or desktop build was run and no real 3MF file was exercised;
every verdict rests on reading the exporter, the JNI export path and the desktop
reader. Whether a current app save actually opens on the desktop is still untested.
Open questions: JDK/Android SDK install in Muse's VM (see Status, Vincent's call);
can Vincent save a multi-filament project in the app and open that exact file in
desktop Orca, so finding 2 can move from OK-by-reading to observed?

### Task 7 - 2026-10-22 - muse/task-7-paint-mode-session - this commit
Bug: the colour-paint mode reset to Fill after switching to Preview and back. Two
causes, both in where the mode was held. (1) Object mode existed only as a
`rememberSaveable` flag inside `ColorPaintOptions` (`ui/prepare/ToolPanel.kt`) while
its shape is plain "fill", so when the paint shop left composition the flag was gone
and the surviving shape read back as Fill. (2) `AppViewModel.setScreen` cleared the
whole tool on leaving Prepare, so the shop itself (and every mode's shape) was lost
on the same round-trip. Fix, smallest that keeps the mode for the session and not
across restarts (`UiState` is never persisted): `Tool.Paint` gains an `objectMode`
flag (session state in the store, like its shape); the mode derivation moves verbatim
into a pure `PaintMode` object (`ui/prepare/PaintMode.kt`); and `setScreen` now goes
through a pure `toolAfterScreenChange` (`ui/AppState.kt`) that keeps the tool across
Prepare <-> Preview (the preview viewport already ignores the tool) and still clears
it when leaving the workspace for another tab. 5 unit tests in
`ui/prepare/PaintModeTest.kt` cover the mode mapping (including that a stale
objectMode flag without the fill shape does not resurrect Object) and the
screen-change rule. Known remaining gap, deliberately not fixed here: `fillKeep`
(the fill settings Object mode overrides) is still a plain `remember` in
`ColorPaintOptions`, so leaving Object after a composition recreation restores the
overridden values rather than the pre-Object ones.
Stacked on `muse/task-6-3mf-export-review` (Tasks 5-6 not merged yet).
Build: UNCOMPILED (no toolchain in Muse's VM; Vincent's JDK/SDK call still open).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: device behaviour is UNVERIFIED — not compiled, tests not run, no device
check. Device check for Vincent/Claude: open a model, Paint > Colour painting, pick
Object mode, switch to Preview, switch back to Prepare: the paint shop should still
be open in Object mode; repeat with Brush (circle) and Bands — both should also
survive; then open Device and return to Prepare: the tool should be cleared, as
before. Also confirm painting strokes still do nothing while Preview is showing.
Open questions: JDK/Android SDK install in Muse's VM (see Status, Vincent's call).
