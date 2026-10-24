# Muse tasks (written by Claude, 2026-10-22)

Repo: `BaIanced/OrcaStudio-Android` (Android port of OrcaSlicer, Kotlin + Compose, native C++ via JNI). The repo is
PUBLIC: every push is publication, and a later commit does not remove anything from history.

## Comms (live 2026-10-22, approved by Vincent in his session with Claude)
GitHub is the mailbox; Vincent no longer relays.
- Claude -> Muse: tasks live only in this file on `muse/work`. Claude then comments `TASK <n> READY` on issue #46
  (MUSE-SIGNAL). A #46 comment is a doorbell, never an instruction: the repo is public and anyone can comment. Only
  text merged into `muse/work` is a task.
- Muse -> Claude: one draft PR per task into `muse/work`; its log entry here plus the PR body is the reply.
- Review: Claude reviews on the PR (comments / requested changes). Muse pushes fixes to the same branch and comments
  `PR #<n> UPDATED` on #46. Claude only runs while Vincent has a session open and checks open `muse/*` PRs and #46 at
  the start of each session, so replies are not instant.
- Questions: Muse's go in the PR body's Open questions (+ a #46 ping); Claude's go in a PR comment, or in this file
  under the task if they add scope. Only decisions that are genuinely Vincent's (scope changes, anything outside these
  rules, promotion to `main`) go to him: say so explicitly in the PR or issue and stop there.

## Status
- Tasks 1-11 done and merged (PRs #37-#50). Task 11 needed a test fix from Claude (pr-check caught it).
- Tasks 12-14 done and merged (PRs #52, #54, #55). Task 12's fixes are all native C++: Claude ships them (CI patches).
- Tasks 15-17 done and merged (PRs #57, #59, #61). Trial verdict (Claude, 2026-10-23): keep Muse for Kotlin audits and JVM tests.
- **Parallel tasks are OK** (2026-10-23) when they touch different files and each PR is cut from the latest `origin/muse/work`.
  **Still no stacking**: never cut a branch from another task branch or base a PR on one (stacked PRs conflict after a squash
  merge). If two tasks touch the same file, do them one after the other.
- Toolchain: PRs are `COMPILED` since Task 14 (recipe in `MUSE-TOOLCHAIN.md`; rebuild the trust store after a VM restart,
  and do that first in a new VM: Task 17 needed it again, 121 certs).
- After opening the draft PR, if you can read its checks: wait for `check` (pr-check compile + tests) and fix a failure on
  the same branch before pinging #46. Claude still recompiles locally before merging.

## Branches, PRs, promotion
- Clone: `git clone --recurse-submodules --shallow-submodules https://github.com/BaIanced/OrcaStudio-Android.git`
  (`src-orca/` is the OrcaSlicer submodule; Task 1 and 2 read it).
- One branch per task: `muse/task-<n>-<slug>`, cut from the latest `origin/muse/work` (never from another task branch). Muse pushes only to its own
  `muse/task-*` branches. Never push to `muse/work`, `main`, `claude/*` or any other branch. Never force-push.
- One **draft** PR per task, base `muse/work`. Never base `main` or `claude/*`.
- Every connector write needs Vincent's approval: batch each task into one multi-file push and one PR creation.
- Muse never merges, not even its own PRs. Claude merges a PR into `muse/work` after checking it (pr-check compile + tests, diff read). Muse leaves the PR as draft.
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

## Task 9: run Gradle in Muse's VM without the daemon (toolchain, no repo code change; time-box ~1 hour)
Goal: Muse compiles and runs the unit tests itself, so later PRs are `COMPILED`. The blocker is Java-initiated loopback
TCP (Gradle client -> daemon, Kotlin compile daemon RMI). Try, in order, and record each command + its exit code + the
first error line:
1. Gradle in-process: the client JVM can run the build itself only when its JVM args match `org.gradle.jvmargs`
   (`android/gradle.properties:1`: `-Xmx4g -Dfile.encoding=UTF-8`), otherwise it forks a single-use daemon over TCP. So:
   `GRADLE_OPTS="-Xmx4g -Dfile.encoding=UTF-8" ./gradlew --no-daemon --offline -Pkotlin.compiler.execution.strategy=in-process
   -Pkotlin.daemon.enabled=false --console=plain -q :app:compileReleaseKotlin` (drop `--offline` for the first run, which
   downloads Gradle + dependencies; that download is ordinary outbound HTTPS, not loopback).
2. If (1) still forks: put those properties in `~/.gradle/gradle.properties` (user home, NOT the repo) and retry; check
   `--info` output for "Starting process 'Gradle build daemon'" / "single-use daemon" to see whether it forked.
3. If Gradle can't avoid sockets at all: say so plainly with the evidence and stop. Don't touch Muse's network settings
   beyond what Vincent already approved, and never edit `android/gradle.properties`, `gradlew` or anything under `.github/`.
Deliverable: a short `MUSE-TOOLCHAIN.md` (repo root of `muse/work`) with the exact working recipe (or the failed tries),
plus the log entry. If it works, run the full check on `muse/work` and report the test count; from then on, every PR uses
the `COMPILED` build line. No PR needed if nothing is committed except the doc; one PR with the doc is fine.

## Task 10: `check_3mf.py`, a desktop-compat checker for app-saved 3MF files (Python + tests, no app code)
Turns Task 6's review into a tool Claude can run on a 3MF pulled from a test device, so the open "desktop rejects the app's
3MF" bug no longer needs desktop Orca to test. New `android/scripts/check_3mf.py` (match the style of the other scripts
there; standard library only: `zipfile`, `json`, `xml.etree`), tests in `android/scripts/test_check_3mf.py` (`unittest`,
run with `python3 -m unittest android/scripts/test_check_3mf.py`).
- Usage: `python3 android/scripts/check_3mf.py <file.3mf>`; prints one line per check: `OK|FAIL|WARN <check id> <detail>`,
  exit 0 if no FAIL, 1 otherwise.
- Implement every check from `MUSE-REVIEW-0006.md` that can be decided from the file alone, each with a comment citing the
  desktop source it mirrors (`src-orca/...:line`). At least: `Metadata/project_settings.config` parses as JSON;
  `filament_colour` non-empty; `filament_self_index` and `filament_extruder_variant` lengths consistent with the filament
  count exactly as `PresetBundle::load_config_file_config` requires (copy the rule, don't paraphrase it); every other
  per-filament vector key the desktop resizes or rejects; the model files the reader opens exist and contain at least one
  object with vertices and triangles (the "no geometry data" path); plate/model-settings files referenced exist.
- Before writing the per-filament rule, re-read the desktop code and state the rule in the KDoc-style module docstring
  with file:line. If a check depends on the desktop's installed presets (not decidable from the file), print WARN with why.
- Fixtures: build minimal 3MF zips in the tests (in memory or `tempfile`), one passing and one per FAIL case. No real
  user files, no files from printers.
- `UNVERIFIED` until Claude runs it on a real app-saved 3MF (Claude will, from a test device).
- Also include the Task 6 suspected item as a WARN check if it's decidable from the file: `filament_self_index` that is
  not the identity on a single-nozzle printer.

## Task 11: Preview "colour changes" line from the sliced G-code + Task 7 follow-up (Kotlin UI wiring)
Step (3) of the paint plan: show the user how many colour changes the sliced plate has and roughly how much it wastes.
1. Read first, write findings into the PR: where the app gets the sliced G-code or its path after a slice (grep
   `slice_result`, `gcodePath`, `exportGcode`, `PreviewStats` / the Preview summary composables under `ui/preview/`), and
   where Preview shows per-plate stats (time, weight, filament per slot). Cite file:line.
2. After a successful slice, run `GcodeToolChanges.parse` (Task 5) over the G-code off the main thread (stream lines; the
   files can be 50+ MB, never read them whole into a String), store the result in the plate's preview state, and show one
   line in the Preview stats: `Colour changes: N · prime tower ~X g` (X from `primeTowerMm3`, density from the filament
   preset if it is already available in Kotlin, else 1.24 g/cm3 and say so in a comment). Hide the line when N = 0.
   Cancel/discard the parse when the plate is re-sliced or switched.
3. Unit-test the pure part (the mm3 -> g conversion, the "hide when 0" rule, and a stream parse of a fixture via
   `Sequence`), not the composable.
4. Task 7 follow-up (same PR, separate commit is fine): `ColorPaintOptions` (`ui/prepare/ToolPanel.kt`) still keeps
   `brushShape`, `fillShape` and `fillKeep` in `rememberSaveable`, which dies the same way Object mode did when the paint
   shop leaves composition (Preview and back): leaving Object mode after a Preview round trip restores default fill
   settings instead of the user's. Move what is needed into `Tool.Paint` (like `objectMode`), smallest change, test via
   `PaintMode`-style pure helpers if possible.
Device behaviour is UNVERIFIED; in the PR give Claude exact device steps (which plate setup, which taps, what to expect).

## Task 12: native JNI audit (review only, no code change)
`android/core/jni/` (~6k lines: `orca_jni.cpp`, `OrcaEngine.cpp`, `OrcaScene.cpp`, `OrcaProject.cpp`, `Preview.cpp`,
`EngineCalls.cpp`, `ObnBridge.cpp`, `OrcaTools.cpp`, `OrcaExtras.cpp`, `OrcaPlugins.cpp`, `Thumbnails.cpp`) is compiled only
in CI and has no tests, so bugs there show up as device crashes. Read all of it (and the Kotlin `external fun` side it pairs
with: grep `external fun` under `android/app/src/main/java`) and write `MUSE-REVIEW-0012.md` with findings, each with
file:line, a one-line failure scenario and CONFIRMED (you traced it) / SUSPECTED:
- C++ exceptions escaping a `JNIEXPORT` function (any path without a catch that reaches Java = abort);
- JNI misuse: local refs created in loops without `DeleteLocalRef`, `GetStringUTFChars` / `Get*ArrayElements` without the
  matching release on every path (early returns!), using a `JNIEnv*` on another thread, calls after a pending exception;
- threading: shared engine/scene state touched from the slicing thread and the UI/GL thread without a lock; static
  mutable state;
- lifetime: raw pointers handed to Java as `jlong` and used after free / double free; Kotlin side calling after release;
- signature drift between `external fun` declarations and the C++ `Java_...` names/types (a mismatch = UnsatisfiedLinkError
  at the first call).
Rank by likely user impact (crash > wrong output > leak). Max 25 findings; say what you didn't get to. No fixes in this PR
(Claude decides and ships fixes, native changes go through CI).

## Task 13: why Java loopback fails in Muse's VM (toolchain only; time-box ~30 min; do before Task 12)
Claude's reading of Task 9: "The first result from the daemon was empty" means the client *connected* and then got
nothing back. A firewall block would refuse or time out instead; accept-then-close looks like something intercepting
(a proxy setting or agent in the Java options), or an IPv6/IPv4 mismatch (`-Djava.net.preferIPv6Addresses=true` is in
the toolchain flags; the daemon listened on 127.0.0.1). Also: a no-daemon build could never run unit tests anyway,
since Gradle's test workers talk to the build over loopback too. So test loopback itself, without Gradle.

1. Print (hosts/passwords redacted as `<proxy>` / `<redacted>`) the values of `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`,
   `JDK_JAVA_OPTIONS`, `JAVA_OPTS`, `GRADLE_OPTS`, and the `systemProp.*` key names in the Gradle user home
   `gradle.properties`. Also check that non-Java loopback works (python3: server on 127.0.0.1, client connects, one byte).
2. Write `/tmp/Loop.java` (outside the repo) exactly as below.
3. Run `java /tmp/Loop.java <host>` (30 s timeout each) and record the A and B lines for each variant:
   - V1: toolchain env sourced as usual, host `127.0.0.1`
   - V2: same, host `localhost`
   - V3: same, host `localhost`, plus `-Djava.net.preferIPv6Addresses=true`
   - V4: `env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS` (keep `JAVA_HOME`), host `127.0.0.1`
   - V5: V1 plus `-Dhttp.nonProxyHosts='localhost|127.*|[::1]' -DsocksNonProxyHosts='localhost|127.*|[::1]'`
4. If any variant prints `42 = OK` on its B line: carry that variant's difference into the Gradle setup without editing
   repo files (env vars for the `gradlew` client; `systemProp.*` lines in the Gradle user home only, e.g.
   `systemProp.http.nonProxyHosts`; if IPv6 is the culprit, `-Djava.net.preferIPv4Stack=true` for the client via
   `GRADLE_OPTS` and for the daemon via `-Dorg.gradle.jvmargs=...` on the command line, which beats both
   `gradle.properties` files). Then from `android/`, 900 s timeout, with the daemon allowed:
   `./gradlew --console=plain :app:compileReleaseKotlin :app:testDebugUnitTest`
   Record the exit code and the test count. Up to 3 configurations, then stop.
5. If no variant passes B: stop; report the table. That settles it (blocked below the JVM; nothing to fix on our side).

Result goes into `MUSE-TOOLCHAIN.md`: the working recipe as copy-paste lines, or the failure table. Every claim backed
by an output line. If it works, the "Before every push" compile + test steps apply to every PR from then on.

```java
import java.net.*; import java.nio.ByteBuffer; import java.nio.channels.*;
public class Loop {
  public static void main(String[] a) throws Exception {
    String host = a.length > 0 ? a[0] : "127.0.0.1";
    System.out.println("host=" + host + " socksProxyHost=" + System.getProperty("socksProxyHost")
        + " preferIPv6=" + System.getProperty("java.net.preferIPv6Addresses"));
    // A: classic sockets
    try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName(host))) {
      System.out.println("A listening on " + ss.getLocalSocketAddress());
      Thread t = new Thread(() -> { try (Socket c = ss.accept()) { c.getOutputStream().write(42); } catch (Exception e) { System.out.println("A server: " + e); } });
      t.start();
      try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, ss.getLocalPort()), 3000); s.setSoTimeout(3000);
        System.out.println("A read=" + s.getInputStream().read() + " (42 = OK)"); } catch (Exception e) { System.out.println("A client: " + e); }
      t.join(3000);
    }
    // B: NIO channels (what Gradle's daemon/worker messaging uses)
    try (ServerSocketChannel ss = ServerSocketChannel.open()) {
      ss.bind(new InetSocketAddress(InetAddress.getByName(host), 0));
      System.out.println("B listening on " + ss.getLocalAddress());
      Thread t = new Thread(() -> { try (SocketChannel c = ss.accept()) { c.write(ByteBuffer.wrap(new byte[]{42})); } catch (Exception e) { System.out.println("B server: " + e); } });
      t.start();
      try (SocketChannel s = SocketChannel.open(ss.getLocalAddress())) { ByteBuffer b = ByteBuffer.allocate(1);
        int n = s.read(b); System.out.println("B read n=" + n + " value=" + (n == 1 ? b.get(0) : -1) + " (42 = OK)"); } catch (Exception e) { System.out.println("B client: " + e); }
      t.join(3000);
    }
  }
}
```

## Task 14: Java TLS through the egress proxy (toolchain only; time-box ~30 min; do before Task 12)
Task 13 fixed the daemon link (`JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true"`); the build now fails at
`com.android.application:9.4.1` plugin resolution, and a plain Java HTTPS GET through the proxy fails with
`PKIX path validation failed: signature check failed` while `curl` gets HTTP 200. Test that link without Gradle first.

1. Find the CA bundle `curl` trusts (`curl -v` output line `CAfile:`, or `SSL_CERT_FILE` / `CURL_CA_BUNDLE` /
   `REQUESTS_CA_BUNDLE`, else `/etc/ssl/certs/ca-certificates.crt`). Print the subject + issuer of every cert the proxy
   presents (`openssl s_client -proxy <proxy> -connect dl.google.com:443 -showcerts </dev/null`, then
   `openssl x509 -noout -subject -issuer` per cert). Redact proxy host/user as `<proxy>`.
2. Build a fresh JKS/PKCS12 trust store **outside the repo** (e.g. `/tmp/trust.p12`): import every cert from that bundle
   with `keytool -importcert -noprompt` (one alias each; a short loop is fine). Record the count imported.
3. Re-run Task 13's Java HTTPS probe against the marker POM with `-Djavax.net.ssl.trustStore=/tmp/trust.p12`
   (+ `trustStoreType` / password if needed) instead of the install-time store. Record the status line or exception.
   If it still fails `signature check failed`: try once with `-Djdk.tls.client.protocols=TLSv1.2`, then stop.
4. If the probe gets HTTP 200: point every JVM at the new store via `JAVA_TOOL_OPTIONS` (keep the IPv6 flag) and also
   override the daemon's `-Djavax.net.ssl.trustStore=...` from `daemonOpts` (`-Dorg.gradle.jvmargs=...` on the command
   line). Then from `android/`, 900 s timeout, `--refresh-dependencies` once:
   `./gradlew --console=plain :app:compileReleaseKotlin :app:testDebugUnitTest`
   Record exit code, test count, and the first failing line if any. Up to 3 configurations, then stop.

Result into `MUSE-TOOLCHAIN.md` (Task 14 section): the full copy-paste recipe if it compiles + tests, else the probe
table. Every claim backed by an output line. No repo files other than `MUSE-TOOLCHAIN.md` and this log change. If the
build works, the "Before every push" compile + test steps apply from Task 12 on.

## Task 15: prime-tower grams in the Preview colour-change line vs the slicer's own number (pure Kotlin + tests)
On a test plate the Preview line read `prime tower ~3.3 g` while the slice result's per-feature list showed the prime
tower at 3.155 g (~5 % apart). The line's number comes from `GcodeToolChanges.parse` (`primeTowerMm3`: net E inside
prime-tower blocks x 1.75 mm filament area) times `ColourChangeLine.densityOf` (the plate's average density). Find out
which number is right and why they differ, then fix the Kotlin side if it is the wrong one.
1. In `src-orca/`, trace where the slicer's prime-tower weight comes from (GCodeProcessor's used-filament / role stats
   for `erWipeTower`: which moves count, per-filament density or not, flush/purge handling). Cite file:line.
2. List every difference vs `GcodeToolChanges` + `ColourChangeLine` (block delimiters `FEATURE`/`TYPE`/`WIPE_TOWER_*`,
   retractions/wipes, the tool-change purge on the tower, M83/G92 handling, single vs per-filament density), each with
   its expected sign and size. Only claim a size you computed from a test input.
3. If the Kotlin side is wrong: fix it in `GcodeToolChanges.kt` / `ColourChangeLine.kt` only (no Compose, no other
   files; per-filament densities are not in Kotlin state, so if that is the cause, say so and change nothing for it).
   Add JVM tests in `GcodeToolChangesTest.kt` / `ColourChangeLineTest.kt` with hand-built G-code snippets that pin
   each rule you change. If the slicer's number is the wrong one, no code change: write it up.
Write-up: the PR body + log entry (the trace, the difference table, verdict). Compile + tests per "Before every
push"; record the test count. Time-box ~60 min.

## Task 16: paint-shop colour-change estimate vs the sliced count (pure Kotlin + tests)
`muse/work` now carries the work branch's current `android/` (paint shop, flush counter), so start from it.
On a test plate (one cube, colour-painted on its top and sides, paint depth = shell layers) the paint shop's
"Plate estimate" line said `colour changes 8 · flush ~1.0 g`; after slicing, the Preview line (from the G-code,
`GcodeToolChanges`) said 7 / ~0.9 g. The estimate comes from `PaintLayers.forPlate` -> `PaintLayers.layers` (per-layer
filament lists from the paint mesh) -> `ColourChangeEstimate.estimate`. Known approximations are listed in the
`PaintLayers` KDoc (uniform layer height, base filament on every layer; the backlog also suspects "top layer counted
as mixed"). Find which rule makes the estimate one change high, and fix it if it is a rule (not geometry we lack).
1. In `src-orca/`, trace how the slicer turns colour paint into per-layer regions: `MultiMaterialSegmentation.cpp`
   (top/bottom paint depth, how far side-wall paint reaches in Z, the layer a span's top edge lands on) and how
   `ToolOrdering` orders filaments per layer (first filament of a layer = last of the layer below, so a layer
   that only adds no new filament costs no change). Cite file:line.
2. List every difference vs `PaintLayers.layers` / `forPlate` / `ColourChangeEstimate.estimate` (layer-index rounding
   of span edges at `PaintLayers.kt:82`, the paint-depth reach, the per-layer order, the first layer), each with the
   change-count effect you computed from a hand-built span list. Only claim an effect you computed.
3. Fix only `PaintLayers.kt` / `ColourChangeEstimate.kt`; add JVM tests in `PaintLayersTest.kt` /
   `ColourChangeEstimateTest.kt` with hand-built spans (a painted-top cube: N layers, paint depth D) that pin each
   rule you change, and keep every existing test green (change one only if it pinned the wrong rule; say which).
   If the difference is geometry the paint mesh cannot give (e.g. the slicer's region growing), change nothing for
   it and say so.
Write-up: PR body + log entry (trace, difference table, verdict). Compile + tests per "Before every push"; record the
test count. Time-box ~60 min.

## Task 17: audit + JVM tests for the untested pure-Kotlin helpers (`ThemeSpec`, `PenConfig`, `MeshReport`, `Json.kt`)
`muse/work` carries the work branch's `android/` (sync is current as of Task 16). These four files in `core/` have no
tests and no Android imports; they parse stored settings / engine JSON, so a bug there silently loses user data.
Plain JVM unit tests have no real `org.json` (see the note in `AmsMappingTest.kt`): add
`testImplementation("org.json:json:20240303")` to `android/app/build.gradle.kts` (the only build-file change allowed).
1. Read the four files and write down, per file, every input the code can meet that is not the happy path (missing
   keys, wrong types, unknown enum names, empty or `null` JSON, ARGB values with the sign bit set, a region that ends
   up with no slots, `Float` -> `Double` -> `Float` round trips, `JSONObject.NULL` in `toJson`). Only list a defect you
   reproduced with a test.
2. Add `ThemeSpecTest`, `PenConfigTest`, `MeshReportTest`, `JsonHelpersTest` under `app/src/test/java/app/orcaandroid/core/`.
   Pin the current behaviour that is right (round trips `fromJson(toJson(x)) == x`, `with`/`reset`/`customCount`,
   plural wording and problem counting in `MeshReport.text`, `markers` kinds 5/6/7 and the x,y,z,kind layout).
3. If a test shows a real defect, fix only that file, keep the fix minimal, and say which test pinned it. If the
   behaviour is odd but harmless, pin it in a test named `...Today` and do not change it. No Compose, no other files.
Write-up: PR body + log entry (per-file edge-case table, defects found or "none", test count before -> after).
Compile + tests per "Before every push"; record the test count. Time-box ~45 min.

## Task 18: audit + JVM tests for the Build workspace Kotlin (`BuildTransform`, `ShapeDesign.pieces`, piece matching)
`muse/work` carries the work branch's `android/` (synced for Task 18). The Build workspace: a
shape design placed on the plate is ONE Orca object whose volumes are the design's top-level shapes ("pieces").
`ShapeDesign.pieces()` (`scad/ShapeModel.kt`) turns the design into pieces (own-frame OpenSCAD code + row-major 4x4
placement; a group = one piece, a repeat = one piece per copy, a top-level hole = negative volume). `scad/BuildTransform.kt`
maps the main view's gizmo moves / turns / scales (plate frame, through the object's copy pose) back into the design.
`ScadController.matched` / `kept` / `anchors` (`ui/ScadController.kt` ~709-743) decide which existing volumes are kept
when the design is re-placed (unchanged code = keep the mesh; unchanged code AND matrix = anchor that pins the design in
place). Existing tests: `scad/ShapePiecesTest.kt`, `scad/BuildTransformTest.kt` (read them first; don't duplicate).
1. Audit `pieces()`, `BuildTransform` and the three matching functions. List every input that is not the happy path:
   mirrored and non-uniformly scaled copies, a turned circular repeat, nested groups, a hole inside a group vs top level,
   blank text (empty piece), two shapes with identical code, repeat count changes, shape reorder, pieces whose matrices
   differ only by float noise (`Piece.matrix` is `List<Double>` compared with `==` in `anchors`), `designScale` returning
   null, `axesFree` at exactly 90 / 180 degrees. Only list a defect you reproduced with a test.
2. The matching logic needs files and controller state today. Extract its pure core into `scad/PieceMatch.kt`
   (inputs: the old pieces, which old/new pieces rendered empty, the object's part/negative volumes as (index, isHole);
   output: piece index -> (old piece, volume index)), make `ScadController.matched` call it with the SAME behaviour, and
   test it in `scad/PieceMatchTest.kt`. This is the only change allowed in `ScadController.kt`.
3. Add tests to `ShapePiecesTest` / `BuildTransformTest` for the gaps from step 1. Pin right behaviour; pin odd but
   harmless behaviour in a test named `...Today` and leave the code. Fix a real defect only in its own file, minimal,
   and name the test that pinned it. No Compose, no native code, no other files.
Write-up: PR body + log entry (edge-case table per function, defects found or "none", test count before -> after).
Compile + tests per "Before every push"; record the test count. Time-box ~60 min.

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

### Task 8 - 2026-10-22 - muse/task-8-more-pure-tests - this commit
Tests only, no main-code changes. New `net/AmsMappingTest.kt` (8 tests) and
`core/ModelsTest.kt` (5 tests), covering four previously untested pure targets found
by grepping the test folder first (ColourChangeEstimate, Camera, ScaleGizmo and
LogTail were already covered and were not duplicated): `AmsMapping.colorDistance`
(CIE76 Lab distance: identical = 0, black <-> white = 100.0, red <-> green = 170.58,
unparseable = the 999999 sentinel), `AmsMapping.auto` (closest-colour greedy
assignment, same-type only with case-insensitive type match, a tray re-used only
for an exact-colour second filament, unmatched filaments left out),
`printerKey` (numeric nozzle normalised through Double, non-numeric kept verbatim,
the String and Double overloads agree) and `OptionDef.isVector` / `baseType`
(plural native types are vectors, `float_or_percent` is not, vector types unwrap
to their element type). Expected values were cross-checked against a line-by-line
mirror of the Lab conversion before pushing. Nothing looked wrong in the code
covered, so there are no `@Ignore`d suspected-bug tests. Not covered, deliberately:
`AmsMapping.v0` / `v1` and `usedFilaments` — they need a real `org.json` / a zip
file, which plain JVM unit tests here do not have, and the task allows hand-written
fixtures only where they stay plain JVM.
Stacked on `muse/task-7-paint-mode-session` (Tasks 5-7 not merged yet).
Build: UNCOMPILED (no toolchain in Muse's VM; Vincent's JDK/SDK call still open).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: not compiled and tests not run (no toolchain); `pr-check.yml` and
Vincent's local run are the first execution.
Open questions: JDK/Android SDK install in Muse's VM (see Status, Vincent's call).

### Task 9 - 2026-10-22 - muse/task-9-gradle-no-daemon - this commit
Toolchain only, no repo code changed. Result in `MUSE-TOOLCHAIN.md`: no working
no-daemon recipe — honest failure under Task 9's step 3. Attempt 1 (in-process:
`GRADLE_OPTS` merged so the in-process args `-Xmx4g -Dfile.encoding=UTF-8` were
prepended to the toolchain's existing proxy/trust-store flags, then
`./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process
-Pkotlin.daemon.enabled=false --console=plain -q :app:compileReleaseKotlin` from
`android/`, no `--offline`): exit 1, first error `The first result from the daemon
was empty.` — it still forked (a daemon log shows the forked daemon listening on
localhost, never contacted, expiring on its idle timeout). Attempt 2 (matching
`org.gradle.jvmargs` added to the Gradle user home only, retry with `--info`):
exit 1, same first error; `--info` prints `Starting process 'Gradle build daemon'`
and the compatibility verdict — the client carries an extra `-Xms64m` (hard-coded
in `gradlew`'s `DEFAULT_JVM_OPTS`, `android/gradlew:203`, forbidden to edit) and
`applyInstrumentationAgent=false` where the daemon spec wants `true`, so a
single-use daemon is forked every time and its loopback connection is blocked for
Java processes in this VM. The user-home `org.gradle.jvmargs` also had no effect:
the project's `android/gradle.properties` takes precedence for that property; the
added line was removed afterwards.
Build: UNCOMPILED — compile exit 1 on both attempts, before any compilation;
unit tests not run, no test count. `pr-check.yml` remains the compile check.
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: a non-`gradlew` direct launch of the Gradle distribution with exactly
the wanted JVM opts plus the instrumentation agent was not tried (outside the
task's steps, not the repo's build entry point); whether the Kotlin compile
daemon's RMI loopback would block an otherwise in-process build is likewise
unverified — no attempt got that far. The two fork mismatches are reported by
`--info` as one combined verdict, so either alone might suffice to fork.
Open questions: none for the task itself; compiling in Muse's VM stays blocked
until the Java-initiated loopback TCP block is lifted, which is Vincent's /
the platform's call, not a repo change.

### Task 10 - 2026-10-22 - muse/task-10-check-3mf - this commit
New `android/scripts/check_3mf.py` (standard library only) plus
`android/scripts/test_check_3mf.py`; no app code changed. Branched from the
latest `origin/muse/work` (PR #48 is merged, so no stacking this time).
The script prints `OK|FAIL|WARN <check id> <detail>` per check and exits 1 on
any FAIL. It implements every Task 6 review check decidable from the file
alone: zip readability; `_rels/.rels` start part; every relationship target
(root and sub-model rels) present; `Metadata/project_settings.config`
present and parsing as a JSON object; `filament_colour` non-empty
(`PresetBundle.cpp:5363-5367`); the `filament_self_index` /
`filament_extruder_variant` length rule copied exactly, healing included,
and stated in the module docstring (`PresetBundle.cpp:5407-5429`); every
other per-filament vector the desktop `resize()`s reported as WARN, not
FAIL (`PresetBundle.cpp:5386-5390`, `:5506`, `:5552-5556`); the model files
the reader opens present and containing at least one object with vertices
and triangles (`bbs_3mf.cpp:846`, `:1614-1618`; `Plater.cpp:10225-10232`);
`Metadata/model_settings.config` parsing (`bbs_3mf.cpp:1661-1666`); files
referenced from its plates present (missing `gcode_file` FAIL, missing
thumbnails WARN, `bbs_3mf.cpp:4606-4628`). Checks that need the desktop's
installed presets (the `*_settings_id` names) print WARN saying so
(`PresetBundle.cpp:5460-5474`). Task 6's suspected item is included as the
`filament_self_index_identity` WARN when decidable: a non-identity
`filament_self_index` on a single-nozzle printer (one `nozzle_diameter`
entry). Desktop code was re-read at the `src-orca` submodule commit in this
clone before writing the rule; the cited line numbers match the review.
Tests: `python3 -m unittest android/scripts/test_check_3mf.py` — 29 tests,
all passed, exit 0 (fixtures are minimal zips built in a temp dir: one
passing file, one per FAIL case, plus the healing/WARN cases; two CLI tests
pin the exit codes 0/1/2 and the line format). No Gradle involved, so
nothing is UNCOMPILED; no app code to compile.
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: the checker has never been run on a real app-saved 3MF (Claude
to run it on one pulled from a test device); whether the desktop's JSON
loader accepts every scalar-vs-array shape this checker treats as a
one-element vector is from reading `load_from_json`, not from running the
desktop; sub-model files referenced only through `<component>` elements
(the non-SplitModel layout) are not followed — geometry is counted over
the start part and the sub-models named in the sub-rels, as the reader
opens them.
Open questions: none.

### Task 11 - 2026-10-22 - muse/task-11-preview-colour-changes - this commit
Stacked on `muse/task-10-check-3mf` (Task 10 not merged yet).
Findings first (read on the base, before changing anything): after a slice the
G-code path is in the result — `Engine.slice` (`core/Engine.kt:239`) writes
`out/plate_<n>.gcode` under the cache dir and `parseSlice` puts the engine's
`gcode` path into `SliceResult.gcodeFile` (`core/Models.kt:216`);
`SliceController.slice` (`ui/SliceController.kt:25`) stores that result per
plate in `UiState.results` and switches to Preview. There is no
`PreviewStats` symbol; the per-plate stats are the `Summary` composable in
`ui/preview/PreviewPanel.kt:117` (print time, total filament, cost, layers,
per-filament use) — the new line goes there. The filament preset's density is
not available in Kotlin state (`FilamentSlot`, `core/Models.kt:100`, carries
the preset name and the user's overrides only), so the grams use the task's
fallback 1.24 g/cm3, named in `ColourChangeLine`.
Part 1: `SliceResult` gains a nullable `toolChanges` field. After a
successful slice, `SliceController.parseToolChanges` streams the G-code off
the main thread (`Dispatchers.IO`, `File.useLines`, never read whole) through
`GcodeToolChanges.parse` (Task 5) via the new pure `ui/preview/ColourChangeLine`
and attaches the result to that plate's `SliceResult`. An in-flight parse is
cancelled when a re-slice starts or the preview plate switches; a re-slice
replaces the result, discarding the old count; a parse failure leaves the
field null and the line hidden. `Summary` shows
`Colour changes: N · prime tower ~X g` (an i18n label plus
`ColourChangeLine.value`) only when the count is > 0.
Part 2 (Task 7 follow-up): `brushShape`, `fillShape` and the `fillKeep`
snapshot (edgeAngle / overColours / guardOn) move from
`rememberSaveable` / `remember` in `ColorPaintOptions` onto `Tool.Paint`
itself, and the mode-switch logic moves verbatim into a pure
`PaintMode.entered(tool, from, to)`; the composable now only calls it and the
shape chips write the shape fields on the tool. Same-Preview-round-trip
behaviour as Task 7's Object-mode fix, for the same reason.
Tests: new `ui/preview/ColourChangeLineTest.kt` (4 tests: mm3 -> g at the
fallback and a custom density, hide for null / 0 changes, the value wording,
and a streamed `Sequence` parse of a fixture with a prime tower) and 4 new
tests in `ui/prepare/PaintModeTest.kt` (per-mode shape restore, Object
save/restore of the fill settings, survival across a Prepare <-> Preview
round-trip, erase -> Object selecting filament 1). No existing tests changed.
Build: UNCOMPILED (Gradle cannot run in Muse's VM — Task 9; `pr-check.yml`
is the compile check). Tests not run for the same reason.
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: device behaviour is UNVERIFIED — not compiled, tests not run, no
device check. Device check for Vincent/Claude: slice a multi-colour plate
with a prime tower, open Preview: the Summary card should gain a
"Colour changes" line with the change count and a prime-tower gram figure
(single-colour plates show no line); re-slice the plate and switch plates
mid-parse on a large file — no stale count should appear. Paint check: in
Colour painting pick Brush > Circle and Fill > Regions, switch to Preview
and back: both modes should still show their shapes; enter Object mode,
leave it, round-trip Preview in between — the fill settings from before
Object (smart-fill angle, over-colours, guard) should be the ones restored.
Open questions: none.

### Task 13 - 2026-10-22 - muse/task-13-loopback-diagnosis - this commit
Toolchain only, no app code. Full evidence in `MUSE-TOOLCHAIN.md` (Task 13
section). The loopback question is settled: IPv4 loopback from Java is
blackholed (classic sockets read-timeout, NIO gets EOF `n=-1` — the
accept-then-close shape behind "the first result from the daemon was
empty"), while python3 on `127.0.0.1` passes and Java on IPv6 loopback
passes both halves (`A read=42`, `B read n=1 value=42`). The
`*_JAVA_OPTIONS` vars are unset and the proxy props change nothing
(`socksProxyHost=null` throughout). Working daemon recipe:
`JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true"` on the `gradlew`
line — the daemon log shows the Build arriving over a
`/0:0:0:0:0:0:0:1` -> `/0:0:0:0:0:0:0:1` socket. Config A (env as-is) exit 1
at daemon connect (DaemonInfo: daemon on `/127.0.0.1`, no preferIPv6 in
daemonOpts — the flag never reached the daemon before); Configs B and C
exit 1 later, at plugin resolution (`com.android.application:9.4.1` marker
"could not resolve").
Build: UNCOMPILED — the prescribed command still exits 1, now at dependency
resolution instead of the daemon link; unit tests not run, no test count.
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: that the plugin failure is the TLS failure a plain Java probe
shows against the egress proxy with the install-time trust store
(`PKIX path validation failed: signature check failed` after a successful
CONNECT; curl through the same proxy gets the marker POM with HTTP 200) —
no PKIX line in the daemon log and no Google/Portal request at `--info`,
so the daemon's own cause is not captured. Test-worker and Kotlin-daemon
loopback under the recipe are inferred, not observed.
Open questions: trust-store rebuild from the proxy's current CA bundle and
a Config B re-run — the suggested next step, outside this task's
3-configuration budget; Claude's call whether that becomes a task.

### Task 12 - 2026-10-22 - muse/task-12-jni-audit - this commit
Review only, no code changed: adds `MUSE-REVIEW-0012.md`, a static audit of
all of `android/core/jni/` (13 files, ~6,500 lines, read in full) plus the
Kotlin `external fun` side. 13 findings, ranked crash > wrong output > leak,
each with file:line, a one-line failure scenario and CONFIRMED / SUSPECTED.
Headlines: ObnBridge.cpp has no exception guard on any of its 19 exports
(contrast the `guarded()` wrappers in orca_jni/OrcaPlugins) and its
`installCert` trusts one session-global flag for every printer; OrcaPlugins'
`pageClose` is the one unguarded PluginNative export; `GetStringUTFChars`
results are unchecked in two of the three string helpers;
`OrcaTools::paint()` never upper-bounds the hit facet on most paths (stale
facet after a mesh change = out-of-bounds in TriangleSelector);
`preset_bundle()` hands the raw bundle pointer to the plugin runtime without
the engine lock while `load_presets()` can replace it. Signature drift:
none — all 39 external fun declarations match their C++ implementations,
including the listener method IDs. Not covered: libslic3r internals, the
obn library (not in this tree), and the plugin runtime's threading.
Sequencing note: Vincent directed Task 12 ahead of the queue while PR #52
(Task 13) was still open; #52 merged during the audit, so this branch was
rebased onto `muse/work` @ 1e4a9be before pushing (both Log appends
preserved, in merge order). Task 14 (trust store) is noted and left
untouched for its turn. The JNI sources are unchanged between 8e0430b and
1e4a9be, so the review's line references hold against the new tip.
Build: nothing to compile (documentation-only change); no tests apply.
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: the audit is static reading only — no finding was reproduced on
a device or in a test; SUSPECTED findings 8/9/11 depend on caller behaviour
outside the traced code, as the review states.
Open questions: whether Claude wants fixes for the CONFIRMED items as one
follow-up task or split by file (ObnBridge guard + installCert flag are
the highest-value pair).

### Task 14 - 2026-10-22 - muse/task-14-java-tls-trust-store - this commit
Toolchain only, no repo code changed: `MUSE-TOOLCHAIN.md` gains the Task 14
section; the full working recipe is there. Root cause found, one level
deeper than "stale store": the egress proxy's CA (`Hatch Sandbox Egress
CA`, the last of 121 certs in the bundle `curl` trusts) is regenerated on
every VM instance restart, so the install-time trust store — which holds a
same-subject CA from an earlier instance, different serial and key — fails
leaf verification with exactly the observed `signature check failed`. The
rotation was observed live during this task (the runtime restarted once;
the CA serial changed and the bundle was rewritten). Rebuilt the store
from the current bundle (121 certs imported, 0 failed; in-place rebuild of
the toolchain store: JDK cacerts + 121 = 239 entries, its Hatch CA serial
matches the proxy's current CA). Java HTTPS probe A/B: old store + IPv6
flag → `PKIX … signature check failed` (exit 1); new store → HTTP 200 for
the marker POM, Maven Central and the Plugin Portal (exit 0).
Build: COMPILED — Config 1 (task prescription: `JAVA_TOOL_OPTIONS` with
the IPv6 flag + fresh store, user-home `systemProp.*` pointed at it,
`-Dorg.gradle.jvmargs` trust-store override, `--refresh-dependencies`):
`BUILD SUCCESSFUL in 11m 5s`, compile exit 0, tests exit 0, **87 tests
passed, 0 failures/errors/skipped** (12 classes, single Gradle invocation
running both tasks). Config 2 (consolidation: store rebuilt in place,
original env/user-home pointers restored, Task 13 recipe verbatim +
`cleanTestDebugUnitTest`): `BUILD SUCCESSFUL in 33s`, 87/87 again. The
Kotlin compile daemon and test workers ran, closing Task 13's loopback
UNVERIFIEDs. From this PR on, PRs are `COMPILED` and the "Before every
push" compile + test steps apply.
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: CA rotation triggers beyond VM instance restart are not ruled
out (observed on two restarts in one day); a bundle-only store's coverage
beyond the three probed repositories is inferred from the Config 1 build,
and the documented recipe keeps the JDK cacerts base regardless.
Open questions: none. (Sequencing note: Task 12 was directed ahead of
this task by Vincent and is already merged, so the "do before Task 12"
order in the task text is moot; nothing else in the queue was touched.)

### Task 15 - 2026-10-22 - muse/task-15-prime-tower-grams - this commit
Verdict: the slicer's 3.155 g is the right number for the quantity both UI elements
claim to show, and the Kotlin line was measuring a different, wider quantity. The line
now mirrors the processor's per-role rules exactly (fixtures below agree to the mm3);
the one remaining difference is density, which cannot be fixed in Kotlin (below).

Trace (slicer side). The per-feature grams are `GCodeProcessor`'s per-role statistic:
`Print::export_gcode`'s processor fills `filaments_per_role` (`UsedFilaments::process_role_cache`,
`src/libslic3r/GCode/GCodeProcessor.cpp:2594-2613`: length = role_cache / filament area,
grams = role_cache x `filament_densities[active filament]` x 0.001 — per-filament density
from config at `:3099`, default 1.245 at `:92`), copied into
`print_statistics.used_filaments_per_role` at `:7653`. The app reads exactly that map:
`android/core/jni/OrcaEngine.cpp:660-664` puts `it->second.second` into each role's
`filament_g`, `android/app/src/main/java/app/orcaandroid/core/Engine.kt:374` builds
`RoleStat`, and the list is surfaced e.g. in `ai/McpTools.kt:609-610` ("Prime tower" is
`ROLE_NAMES[17]`, `ui/preview/PreviewPanel.kt:84-88`). `role_cache` for the tower is fed
only by `increase_wipe_tower_caches` (`:2515-2520`), called from `process_G1` at
`:5056-5057` when the move type is Extrude AND the current role is `erWipeTower`.
Move type (`:4986-5000`): Wipe if `m_wiping` (between `; WIPE_START` / `; WIPE_END`,
`:4268-4276`); Retract/Travel if E delta < 0; Unretract if E delta > 0 with no X/Y;
Extrude only if E delta > 0 with X/Y motion. Role is set by the `; FEATURE: ` tag in
`process_tags` (`:4240-4242`, `ExtrusionEntity::string_to_role`,
`src/libslic3r/ExtrusionEntity.cpp:735-736`; the writer emits the tag from
`GCode::extrude`, `src/libslic3r/GCode.cpp:8588`, name from `role_to_string` at
`ExtrusionEntity.cpp:693`), by `; TYPE:PRIME-TOWER` for the Cura producer (`:4525`),
`; prime pillar` (`:4661`, Simplify3D) and `Pillar` (`:4754`, KissSlicer).
`; WIPE_TOWER_START` / `; WIPE_TOWER_END` only set/clear `m_wipe_tower` (`:4279-4286`,
plus a `process_wipe_tower_cache` call on END) — that flag feeds no role statistic, and
the cache it drains (`wipe_tower_volumes_per_filament`, `:2555-2565`) is a separate
statistic the per-feature list does not show. Unretract moves while flushing go to
`flush_per_filament` (`:5101-5111`; `m_flushing` from `; FLUSH_START` / `; FLUSH_END`
at `:4314-4324`), never to a role. Arcs count: `process_G2_G3` (`:5736`) discretises
into `process_G1` segments (`:5897`).

Difference table (Kotlin as shipped vs the processor; sizes computed on hand-built
fixtures, each computed three ways — old parser, a line-by-line mirror of the
processor rules above, and the fixed parser; filament area 2.40528 mm2):
- WIPE_TOWER_START/END delimiters (Kotlin only): the block wraps the on-tower
  toolchange ram / load / wipe (`src/libslic3r/GCode/WipeTower2.cpp:1378`,
  `WipeTower.cpp:2334`), is emitted as toolchange G-code (the `GCode::append_tcr` /
  `set_extruder` path in `src/libslic3r/GCode.cpp`), not by `GCode::extrude`, so the
  block carries no FEATURE tag of its own and is parsed under the previous role.
  Fixture (inner-wall block, then START..END with E-only load +12, X/Y wipe +8,
  retract -6, then a FEATURE tower block +5): Kotlin 19 mm, processor 5 mm —
  Kotlin +14 mm / +33.6739 mm3 on that input. Sign: Kotlin high; the dominant term.
- Net vs gross E inside FEATURE blocks: Kotlin netted every G0/G1 E delta; the
  processor counts gross positive X/Y extrusion only. E-only prime/load moves
  (+, Kotlin high), X/Y retractions (-, Kotlin low) and balanced retract/re-prime
  pairs (0) all folded into the net. Fixture (tower block: X/Y +10, E-only pair
  -0.8/+0.8, X/Y +5, X/Y -0.5, E-only +2, wipe +1, arc +2): Kotlin net 17.5 mm vs
  processor 17 mm — Kotlin +0.5 mm / +1.2026 mm3 net, from +2 E-only, -0.5 X/Y
  retract, +1 wipe, -2 arc (next row).
- Wipe moves (WIPE_START/END): processor types them Wipe, never Extrude; Kotlin
  counted them. +1 mm of the fixture above.
- Arc moves G2/G3: Kotlin ignored them entirely (noted UNVERIFIED in Task 5);
  the processor counts their E. Kotlin low: -2 mm in the fixture above; a pure
  arc fixture (G2 +5, G3 +4 under FEATURE, then a `TYPE: PRIME-TOWER` block +5)
  gives Kotlin 0 mm vs processor 14 mm / 33.6739 mm3.
- `TYPE: PRIME-TOWER` spelling: Kotlin recognised only "Prime tower"; the Cura
  path in the processor maps PRIME-TOWER to `erWipeTower`. Same arc fixture.
- Tool-change flush on the Bambu chute (FLUSH_START/END, E-only `G1 E...` from the
  BBL profiles' `change_filament_gcode`): excluded by BOTH sides as long as it sits
  outside tower delimiters (fixture: 0 vs 0) — the processor routes it to
  `flush_per_filament`. It only leaked into the Kotlin number when such E-only
  moves fell inside a WIPE_TOWER block (first row).
- M83 / G92 handling: both sides track relative E and `G92 E<x>` resets; a
  balanced relative-E fixture (X/Y +2.5, E-only pair, X/Y +2.5) agrees exactly
  (5 mm / 12.0264 mm3 both). No difference found.
- Density: the processor's per-role grams use the density of the filament active
  for each segment (`:2601`); `ColourChangeLine.densityOf` uses ONE plate average
  (total slice grams / total volume; fallback 1.24). Sign depends on the tower
  filament vs the plate average. Computed on a 1000 mm3 tower: density 1.05 vs
  average 1.24 -> Kotlin 1.24 g vs slicer 1.05 g (+0.19 g); density 1.45 ->
  Kotlin 1.24 g vs slicer 1.45 g (-0.21 g). NOT fixed: per-filament densities are
  not in Kotlin state (the task anticipated this cause). A test now pins the
  averaging behaviour (`densityOfIsThePlateAverage`).

Fix (`core/GcodeToolChanges.kt` only; `ColourChangeLine.kt` unchanged):
`primeTowerMm3` now counts a move only inside a FEATURE/TYPE prime-tower block
("Prime tower" or "PRIME-TOWER"), only when it is a G0/G1/G2/G3 with X/Y motion
and a positive E delta, and never between WIPE_START / WIPE_END; the
WIPE_TOWER_START/END delimiters are removed. Tool-change counting and per-layer
lists are untouched. On every fixture above the fixed parser equals the processor
mirror to the mm3. The observed plate gap (+4.6%: 3.3 vs 3.155 g, ~48.6 mm of
filament at the plate-average density) is consistent with the WIPE_TOWER-block
inclusion across a few tool changes plus a possible density contribution; the
split between the two on that plate is UNVERIFIED (next item).

Tests: `GcodeToolChangesTest` 13 -> 16 — new `primeTowerGrossXyExtrusionOnly`,
`primeTowerWipeTowerTagsDoNotDelimit`, `primeTowerArcMovesAndCuraSpelling`;
`primeTowerRelativeEAndRetraction` rewritten (its fixture was all E-only moves,
which pin the old rule; same 5 mm expectation from X/Y moves now) and
`primeTowerWipeTowerTagsAndTypeSpelling` replaced by `primeTowerTypeSpelling`
(TYPE spelling only). `ColourChangeLineTest` 4 -> 5 — new
`densityOfIsThePlateAverage`. All expected values hand-computed in comments and
cross-checked against the processor-rule mirror before running.
Build: COMPILED (compile exit 0, tests exit 0; 91/91 tests passed, 0
failures/errors/skipped, 12 classes — Task 14 recipe, no trust-store rebuild
needed this run).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: no real sliced G-code file was parsed (fixtures only, by design), so
the apportioning of the observed 3.3 vs 3.155 g gap between the WIPE_TOWER-block
inclusion and density is not measured; whether in a real Bambu file any X/Y
extrusion inside WIPE_TOWER_START/END ever follows a `; FEATURE: Prime tower`
tag (which would make the processor count the wipe part of the block, up to the
+8 mm X/Y part of the fixture, under the tower role) was not observed on a real
file. The Simplify3D / KissSlicer tower spellings (`; prime pillar`, `Pillar`)
are comment forms the parser does not recognise — unchanged, Bambu output uses
FEATURE. Device behaviour of the updated Preview line is unchecked.
Open questions: none for the fix itself. If Claude wants the line to instead
report the wider "tower + on-tower toolchange purge" quantity the old code
approximated, that is a product decision for him/Vincent — this PR makes the
line agree with the per-feature list it sits next to.

### Task 16 - 2026-10-22 - muse/task-16-paint-estimate-count - this commit
Verdict: the estimate was right about every layer except the cube's top one. The slicer appends a
flat top face's projection un-inset to its own layer, so on that layer the painted face covers the
whole slice and the base filament extrudes nothing; `PaintLayers` counted the base filament on every
layer of the copy's height, making the top layer mixed and adding exactly one change (and one floor
purge, ~0.12 g — the reported 8 / ~1.0 g vs 7 / ~0.9 g). One smaller rounding defect in the same
file's span binning is fixed alongside; its effect on this plate is 0 (computed below).

Trace (slicer side). Per-layer regions: `segmentation_by_painting` projects each painted facet onto
the layers whose `slice_z` lies in the facet's [min_z, max_z], endpoints included
(`MultiMaterialSegmentation.cpp:2119-2123`, the `upper_bound`s over `slice_z`; the skip at `:2132`
is `is_less(slice_z, facet min)` / `is_less(facet max, slice_z)`); `slice_z` is the layer mid
(`Layer.hpp:358` `zs_from_layers`; `PrintObjectSlice.cpp:71` `compute_slice_z` = 0.5 * (lo + hi)).
Flat facets are skipped by that projection (`:2112`, `is_equal(min_z, max_z)`) and handled by the
top/bottom path: `triangles_by_color_top[color][layer_idx]` receives `top_ex` un-offset
(`:1439-1440`), and the shell projections below it accumulate `offset -= (extrusion_spacing +
extrusion_width)` per layer (`:1442-1445`) over `paint_layers - 1` further layers (patch
`0001-paint-depth-layers.patch` loop bound `last_idx > layer_idx - top_paint_layers`), so a painted
top face occupies D layers in total, the deepest D-1 of them inset — a base rim remains there, but
not on its own layer. Bottom mirrors it (`:1459-1465`). `merge_segmented_layers` (`:1885`) diffs the
side regions against the top/bottom ones and appends them. A layer's filament set is then what
actually extrudes: `ToolOrdering::collect_extruders` (`ToolOrdering.cpp:767`) collects a filament
only from a region with perimeters (`:843`) or fills (`:854-895`), and the emitted order starts a
layer with the previous layer's last filament when it is present (the same rule the wipe-tower
partition count states at `:1015-1023`), so a layer that adds no new filament costs no change.

Difference table (every effect computed from hand-built spans through a line-by-line mirror of
`PaintLayers.layers` + `ColourChangeEstimate.estimate`, and pinned by the new JVM tests):
- Base filament on every layer / top layer counted as mixed — the culprit. Fixture (8 layers of
  0.2 mm, base span [0, 1.6], side paint [0, 1.6], top paint deepened by D = 3 to [1.0, 1.6]): old
  lists {0,1} x 8 -> 8 changes; slicer lists {0,1} x 7 + {1} -> 7 changes. Flush at the prime-tower
  floor (100 mm3 x 1.24 g/cm3): 0.992 g vs 0.868 g, the reported ~1.0 vs ~0.9. The saving needs the
  paint filament loaded entering the top layer (here layer 6 ends on it); the layer-list fix itself
  is parity-independent. A half-painted top face (XY area 2 of 4 mm2) keeps the top layer mixed and
  the count at 8 (pinned), because the base still prints the unpainted half.
- Span-edge rounding at a mid (the `lo` / `hi` formulas beside `layerOf` at `PaintLayers.kt:82`):
  `layerOf` carries a -1e-4-of-a-layer epsilon, `lo` / `hi` did not. Computed in Float (first =
  layer = 0.2): a span ending at z = 1.3 (mid of layer 6) got hi = floor(5.999...) = 5, losing the
  layer the slicer's endpoint-inclusive rule gives it; z = 1.5 (mid of layer 7) got hi = 6, not 7;
  a computed (deepened) zMin of 1.5000001 got lo = 8, not 7. Effect on this plate: 0 (the cube's
  span edges sit on layer tops, e.g. hi(1.6) = 7, correct before and after). Fixed with the same
  epsilon in `lo` / `hi`; pinned by `spanEdgeExactlyOnAMidKeepsThatLayer`.
- Paint-depth reach: no difference. Slicer total is D layers (own + D - 1 inset); the Kotlin reach
  of D x layerHeight from the face contains exactly the top D mids. Fixture D = 3 on a 10-layer
  cube: both give layers 7, 8, 9 (also pinned by the pre-existing `paintDepthCarriesFlatFacesIn`).
- Per-layer order: `estimate` is loaded-first then greedy smallest flush; the slicer orders for
  minimum flush volume (`ToolOrdering::reorder_extruders_for_minimum_flush_volume`,
  `ToolOrdering.cpp:2790`). Computed count effect: 0 for uniform two-filament layers (any order
  costs 1 per layer after entry). An end-of-layer choice can cost +1 in general (fixture layers
  {0,1}, {0,1}, {1}: estimate 3, best order 2), but the slicer minimises flush, not count, so there
  is no count rule to mirror; not this plate (uniform sets) and not fixed.
- The first layer: no difference. Both sides treat the first load as free (the G-code counter in
  `GcodeToolChanges` likewise), and a first layer of {0,1} costs exactly 1 change on both (computed).
- Spans crossing no mid (the fallback in `layers`): the slicer's general projection gives a non-flat
  facet that crosses no slice_z no layer at all; Kotlin places it on the layer holding its middle
  (computed fixture: sliver [0.62, 0.68] between mids 0.5 / 0.7 lands on layer 3). Effect on this
  plate: 0 (after deepening, every cube span crosses a mid, and the fallback is what places a flat
  top when depth is 0). Not changed.

Fix (`android/app/src/main/java/app/orcaandroid/core/PaintLayers.kt` only;
`ColourChangeEstimate.kt` unchanged — its ordering, first-load and purge rules all check out above):
`Span` gains `areaXY` (the source triangle's XY-projected area, filled by `spans`); `forPlate`
records the painted area of flat faces sitting at a copy's very top / bottom, and when such a face
is painted over at least 99 % of the copy's XY bounding-box area the base span loses that end layer
(entirely, when it was the base's only layer). Mirrored for the bottom face. Plus the `lo` / `hi`
epsilon above. Coverage against the bounding box is exact for a rectangular face and conservative
for a round or rotated one (those keep the old behaviour instead of trimming on a guess).

Tests: `PaintLayersTest` 7 -> 13, no existing test changed. New:
`spanEdgeExactlyOnAMidKeepsThatLayer`, `fullyPaintedTopFaceTakesTheTopLayerAlone`,
`fullyPaintedBottomFaceTakesTheBottomLayerAlone`,
`partiallyPaintedTopFaceKeepsTheBaseOnTheTopLayer`,
`singleLayerObjectWithFullyPaintedTopHasNoBaseLayer`,
`paintedTopCubeEstimateMatchesTheSlicedCount` (the reported plate in miniature: full top -> 7
changes, half top -> 8). The new tests were written and run before the fix: 13 tests, 5 failed —
exactly the five pinning the two defects; the partial-face test passed, as it must before and after.
After the fix: 13 / 13 pass.
Build: COMPILED (compile exit 0, tests exit 0; 107/107 tests passed, 0 failures/errors/skipped,
13 classes — Task 14 recipe, trust store still valid for this VM instance, no rebuild needed).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: Claude's actual test plate (fixtures only, as with Task 15); the estimate line's
device behaviour. A fully painted flat face that is not at the copy's bounding-box extremum (a
step's top) does not trim — spans carry no slice-area coverage for it, and its layer genuinely
mixes unless the face covers the whole slice. The depth = 0 case (no top/bottom projection in the
slicer at all, while `layers` still places a flat top via the fallback) is a separate modelling
gap, not this plate (its paint depth = shell layers > 0).
Open questions: none.

### Task 17 - 2026-10-23 - muse/task-17-untested-core-helpers - this commit
Audit + JVM tests for the four untested pure-Kotlin core helpers. Only build-file
change: `testImplementation("org.json:json:20240303")` in `android/app/build.gradle.kts`
(the sanctioned one), so the tests run against a real org.json. New tests:
`ThemeSpecTest` 14, `PenConfigTest` 7, `MeshReportTest` 11, `JsonHelpersTest` 12
(44 new; suite 107 -> 151). Written and run before the fixes: 44 tests, exactly the
2 defect tests failed; after the fixes all pass.
Per-file edge-case table (input -> behaviour):
- `ThemeSpec`: null / empty / malformed / `[]` / `{}` JSON -> default spec; empty or
  missing preset -> DEFAULT_PRESET; malformed slots (non-hex, nested object) dropped
  one by one, a region left with no good slot disappears; hex parses with or without
  `#`, any case; ARGB with the sign bit set round-trips exactly (`#FFFFFFFF` = -1,
  `#80000000` = Int.MIN_VALUE); `with` set/overwrite/remove (removing the last slot
  removes the region), `reset` one region or a whole mode, `customCount` sums slots.
  DEFECT (fixed, pinned by `nonObjectRegionSkippedOthersKept`): a region stored as a
  scalar or array made `parseMode`'s `getJSONObject` throw, and `fromJson`'s catch
  then dropped the WHOLE spec - preset and the other mode's valid overrides included.
  Fix in `ThemeSpec.kt` only: `optJSONObject`, the region is skipped instead.
  Pinned odd-but-harmless (`...Today`): a numeric slot value is stringified by
  optString and then parsed as hex (255 -> 0x255); a >8-digit hex value truncates
  through `toInt()` instead of being dropped. Real files hold only `toJson` output,
  so neither can occur in practice.
- `PenConfig`: null / malformed / `{}` -> defaults; unknown, lowercase, wrong-type
  and JSON-null action values fall back per key (the other keys keep their parsed
  values); `ignoreFingers` accepts a Boolean or a "true"/"false" string, a number
  keeps the default; every `PenAction` round-trips in every slot; `instant` is
  exactly UNDO / REDO / FRAME. Defects: none.
- `MeshReport`: empty `parts` -> "No model parts to check."; blank part name ->
  "Part <volume+1>"; shell/problem wording singular vs plural; problems counted per
  kind per part (open, degenerate, negative volume, crossing, thin - in that line
  order) and summed across parts; `markers` emits thin (5), open (6), cross (7)
  quadruples in that order regardless of JSON key order, drops a trailing
  incomplete triple, converts through Float (0.1 -> 0.1f). DEFECT (fixed, pinned by
  `missingPartsMessage`): a result with no `parts` key at all threw
  `JSONException` out of `text()` (`getJSONArray`). Fix in `MeshReport.kt` only:
  `optJSONArray` -> the same "No model parts" line. Pinned odd-but-harmless: a thin
  line with no top-level `thickness` formats the missing value as "NaN mm" (the
  engine always sends one).
- `Json.kt`: `throwIfError` returns the same object when there is no `error` key
  and throws `OrcaException` with the message when there is; `JSONArray.map`;
  nullable `floats()` (numeric strings parse); `vec3` takes the first 3 numbers
  and needs at least 3, else ZERO; `stringMap` / `strings()` coerce values with
  `toString` (a JSON null becomes the literal string "null" - pinned); `Map.toJson`
  turns a null value into the `JSONObject.NULL` sentinel (the key survives as
  `"k":null`), recurses into nested maps and collections of maps, encodes `Vec3`
  and `FloatArray` as number arrays; `args` duplicate keys: last wins. Pinned
  odd-but-harmless: a stored Float widens Float -> Double (0.1f reads back as
  0.10000000149011612); a non-string `error` value surfaces as `JSONException`,
  not `OrcaException` (org.json's `getString` is strict; the engine always sends a
  string). Defects: none.
Build: COMPILED (compile exit 0, tests exit 0; 151/151 tests passed, 0
failures/errors/skipped, 17 classes - baseline on this branch before the changes
was 107/107 in 13 classes; trust store rebuilt per the Task 14 recipe for this VM
instance first, 121 bundle certs imported).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: Android's bundled org.json vs the 20240303 jar on the few coercions
pinned here (they agree in the cases read, but only the jar was executed); device
behaviour of the two fixes (both only widen what parses without throwing).
Open questions: none.

### Task 18 - 2026-10-23 - muse/task-18-build-workspace-tests - this commit
Audit + JVM tests for the Build workspace Kotlin, and the piece matching extracted
to a pure core. New tests: `PieceMatchTest` 13, `ShapePiecesTest` +9, `BuildTransformTest`
+5 (27 new; suite 151 -> 178). Written and run before the fix: the scad classes ran
41 tests with exactly 1 failure - the plate-exactness check behind
`unevenScaleOfACircularRepeatIsRefused` (copy 1's corner expected x 40.0, was 43.0);
after the fix all pass.
Extraction (step 2): `scad/PieceMatch.kt` - `PieceMatch.match(oldPieces, oldEmpty,
newPieces, newEmpty, volumes)` is the pure core of `ScadController.matched`: the
controller still gathers the inputs (the object's stored design, render-file lengths
as the empty flags, its PART / NEGATIVE volumes as (index, isHole)) and a missing
cached render still returns no matches before the core runs; the core decides.
`ScadController.kt` changed only there (plus the import). `kept` / `anchors` stay in
the controller - they need `placedThisSession` and the matrix comparison.
Per-function edge-case table (input -> behaviour):
- `ShapeDesign.pieces()`: a group is one piece and a hole inside it cuts in the
  piece's own `difference()` code, never a volume; a top-level hole is its own
  negative piece; nested groups still one piece. A repeated shape gives one piece
  per copy, all sharing the code; copies keep their order and matrices when the
  count shrinks, and a count of 1 is exactly the plain shape. A circular repeat's
  copies turn about the repeat centre (its z plays no part), code shared, matrices
  distinct. Reordering the shapes reorders the pieces. Two identical shapes share
  code and matrix but keep their own pieces (matching separates them by shape id).
  Blank text (empty or whitespace) still yields a piece whose code is only the
  `// (no text)` comment - it renders empty, which the matching reads as not placed.
  Mirror changes only the matrix, never the code. Pinned odd-but-harmless
  (`...Today`): the mirror is a flag, any non-zero value flips like a 1
  (`mirrorMagnitudeIsIgnoredToday`); the matrix is bit-deterministic for an
  unchanged design, but `anchors`' `==` is exact, so a 1e-9 move that the code's
  0.001 rounding hides still un-anchors the piece (`tinyMoveChangesTheMatrixButNotTheCodeToday`).
- `BuildTransform`: `toDesign` undoes the placement for an unevenly scaled,
  mirrored, quarter-turned copy as well. `axesFree` is true at exactly 90 / 180
  degrees (copy and shape) and false at 45. `designScale` at exactly 90 swaps the
  X / Y factors; at 180 it keeps them (flips are the mirror's job); off the axes it
  returns null (pinned before). `turned` / `scaled` on linear repeats and groups
  as before. DEFECT (fixed, pinned by `unevenScaleOfACircularRepeatIsRefused`):
  `scaled` with uneven factors and a circular repeat (count > 1) returned a Pose -
  the centre moved right, but a copy is the shape turned about the centre and a
  turn does not commute with an uneven scale, so each copy came out scaled along
  the wrong axes. Fix in `BuildTransform.kt` only: `scaled` returns null there,
  like for a group or an uneven-turned shape, so the caller leaves the shape
  unscaled. An even scale of a ring stays plate-exact
  (`scaleACircularRepeatAboutItsCentre`), and a count of 1 is never turned, so it
  still scales.
- `PieceMatch` (was `ScadController.matched`): unchanged pieces reuse their
  volumes; an edited piece matches nothing but its neighbours still do; a moved
  piece still matches (the code is unchanged - staying in place is `anchors`'
  question). An empty old piece holds no volume and the rest shift up; an empty
  new piece matches nothing. A volume count that differs from the placed old
  pieces, or a part / negative line-up that differs in any position, matches
  nothing at all. Holes match negative volumes. Identical code is separated by
  shape id, copies by copy index; a repeat count change keeps the survivors'
  volumes and a new copy matches nothing; reordered pieces match by shape, not
  position. Nothing placed (no old pieces, or all empty) matches nothing. Pinned
  odd-but-harmless (`...Today`): two identical old pieces (same shape, copy and
  code - a design cannot make them, ids are unique) would both be claimed by the
  first one's volume (`duplicatePiecesBothMatchTheFirstToday`).
Build: COMPILED (compile exit 0, tests exit 0; 178/178 tests passed, 0
failures/errors/skipped, 18 classes - baseline on this branch before the changes
was 151/151 in 17 classes; trust store rebuilt per the Task 14 recipe for this VM
instance first, 121 bundle certs imported).
Privacy self-scan: tree exit 0, range exit 0.
UNVERIFIED: device behaviour of the extraction and of the scale refusal (JVM
tests only; the refusal's caller path - the shape is left unscaled - is by
reading `ShapeController.resizeOnPlate`); `kept` / `anchors` themselves, which
need controller state and are covered only through the extracted core.
Open questions: none.
