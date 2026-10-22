# Muse tasks (written by Claude, 2026-10-22)

Repo: `BaIanced/OrcaStudio-Android` (Android port of OrcaSlicer, Kotlin + Compose, native C++ via JNI). The repo is
PUBLIC: every push is publication, and a later commit does not remove anything from history.

## Status
- **Pilot: Task 1 only.** Tasks 2-4 start after Vincent has reviewed the pilot PR and said so.
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
