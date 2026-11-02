# Performance and size framework

Goal: the most speed per byte and per line of code, done the cleanest way. Every change that claims a
speed or size win names a metric, measures it before and after on the same device or APK, and lands
only if the number moves. Budgets only go down (ratchet).

## Review rule

- A change made for speed or size says in its commit message: metric, device or APK, before -> after.
- A change that grows a budgeted group past its budget says why in the commit message and raises the
  budget in `android/size-budget.json` in the same commit. A cut lowers the budget in the same commit.
- No speculative optimisation: profile first (recipes below), fix the top item, measure again.
- New dependencies, assets or native libraries: state their APK cost from the size report.

## Metrics and budgets

| Metric | Tool | Budget / baseline |
|---|---|---|
| APK size per group (native libs, dex, vendor profiles, i18n, Python, ...) | `android/scripts/size_report.py`, CI job summary | `android/size-budget.json` (r492: 124.1 MB total) |
| Compose recomposition: restartable composables that are not skippable | Compose compiler reports | 0 (2026-10-31: 242 of 242 skippable) |
| 3D view frame timing (fps, frame gap median / p95 / max) | MCP `perf` tool (`render/FrameStats.kt`, last 240 frames) | baseline not recorded yet |
| Cold start | `adb shell am start -W -n <pkg>/.ui.MainActivity` (TotalTime) | r493 phone, 3 runs: 222 / 149 / 159 ms (median 159; activity launch only) |
| Slice time of a fixed model | MCP slice on the same model and profile | baseline not recorded yet |

Record a missing baseline the first time a change touches that area (same device, three runs, median).

## Recipes

- **APK size**: `python android/scripts/size_report.py <apk> [--baseline old.json] [--json new.json]`.
  CI runs it after every APK build: table + delta to the previous build of the branch in the job
  summary, a `::warning::` per group over budget, `size-report.json` in the APK artifact.
- **Native size**: the shipped `.so` files are stripped. For the biggest symbols use the unstripped WSL
  build: `llvm-nm --size-sort -C -S ~/orca-build/core/liborca_jni.so | tail -40`; sections with
  `llvm-size -A` (r492 liborca_jni: .text 36 MB, .eh_frame 3.9 MB, .gcc_except_table 3.0 MB).
- **Compose stability**: `./gradlew :app:compileReleaseKotlin --rerun -Porca.composeReports=true`,
  then `<build>/gradle/app/compose-reports/app-composables.txt` (grep `restartable scheme` without
  `skippable`) and `app-classes.txt`. Strong skipping is on (Kotlin 2.x), so unstable classes only
  matter where a composable is not skippable.
- **Compose UI jank**: `adb shell dumpsys gfxinfo <pkg> reset`, do the interaction, then
  `adb shell dumpsys gfxinfo <pkg> framestats` (janky frames, percentiles). Covers the Compose UI,
  not the GL thread.
- **3D view**: MCP `perf` before and after a camera orbit or edit (same plate, same zoom).
- **Native CPU hotspots**: NDK `simpleperf` (`simpleperf record -g -p <pid> --duration 10`, then
  `simpleperf report`) on the rooted phone; symbolise with the unstripped WSL lib.
- **Dead Kotlin code / resources**: `./gradlew :app:lintRelease` (UnusedResources, unused
  declarations); R8 `-printusage` lists code R8 removed, which usually means it can go from source.

## Done

- 2026-10-31 vendor profiles: 67 deflated zips -> stored zips inside xz (`pack_resources.py`, `org.tukaani:xz`),
  assets/vendors 28.3 -> 17.25 MB in the APK. One solid archive would be only 0.4 MB smaller but must be decoded
  whole per vendor install. BBL + Creality + filament library (5,305 files) extract in ~2 s on WSA.
  resources.zip under xz: only -0.35 MB, not done. CI run 41274366209 (r493) installed on all 4 adb devices;
  APK 118.6 MB (was 124.1 budget at r492). CI size summary absent: the size step is still on local branch `local/ci-size-step`.

## Experiment queue (measured potential, not done)

0. **Big-ticket map (2026-11-01, r502 APK 118.5 MB; native libs stored uncompressed = 78 MB).** liborca_jni symbol bytes by
   library (`orca-android-tools/wsl/nmgroups.sh`, unstripped WSL build, 39.3 MB of symbols): OCCT (STEP import) ~15 MB (10.3
   matched + most of the 8.6 "other": AdvApp2Var, AppCont, ChFi3d...), Slic3r 9.8, OpenVDB 5.15, std templates 2.6, boost 0.8,
   png/z/curl/ssl 0.7, CGAL 0.7. Candidates, zero feature loss first (unmeasured beyond this):
   a. OpenVDB: DONE 2026-11-01 (partial, zero loss). OpenVDB is NOT droppable: our smooth blend (`jni/OrcaVdbBlend.cpp`) and FDM
      hollow (`jni/OrcaHollow.cpp` via SLA/Hollowing) use it. What kept most of it alive was `openvdb::initialize()`
      (registers every grid type for file I/O: bool/double/int64/vec3/string/point-data trees). Dropped from our blend +
      `OpenVDBUtils.cpp` (patch 0004; upstream's own comment says it works without): stripped liborca_jni -3.62 MB
      (-O2 WSL build 49,064,968 -> 45,440,752 B, only those 2 objects rebuilt). WSA debug test: hollow (9,100 -> 32,018 tris,
      29.7 s) and blend r 2 (11 shells -> 37,594 faces, size kept) both work. The rest (~1.6 MB float/bool/int32 tools) is used.
   b. OCCT ~15 MB, STEP import only: own `.so`, downloaded on the first STEP import (GitHub release asset + hash).
   c. libopenscad 12.5 MB vs liborca_jni: likely duplicate CGAL/boost/GMP/manifold/Clipper copies; measure, then share.
   d. Product decisions: non-English i18n 6.1 MB; Python runtime ~15 MB (libpython 6.7, libcrypto 3.7, stdlib 4.35) on demand at the
      first plugin install; non-Bambu vendor profiles ~14 MB on demand.
   e. Compressing the native libs (now stored) cuts the download ~78 -> ~35 MB but raises the installed size (APK + extracted
      copy): not worth it for a sideloaded app.
   Speed: profile a slice with simpleperf before any flag work; PGO is the one flag-level change with a typical 10-20% gain.

1. **Native link flags** for liborca_jni (48.4 MB): `-Wl,--icf=safe` measured 2026-10-31: no gain (stripped 50,754,576 vs
   50,753,744 B, relink only), rejected. 2026-11-01 local WSL builds (orca-android-tools `wsl/perfbuild.sh`, separate dirs, no ccache,
   ~9 min each on 16 threads), stripped: -O3 base 50,754,856 B; `-O2` 49,064,968 (-1.69 MB); ThinLTO (-O3) 49,804,432 (-0.95 MB).
   Slice time, phone, debug app, same plate (`slicebench.sh <variant>`, 3 runs after a warm-up): base 16.2 s cool, 18.9 s after
   the other runs (thermal drift ~15%, bigger than the differences); -O2 18.8; ThinLTO 18.4 (ran between -O2 and the warm base).
   Interleaved (cooldown <=32 C, app stopped, no VD; `slicebench-interleave.sh`): -O2+ThinLTO 47,953,296 B (-2.80 MB) slices 18.6/18.8/18.7 s
   vs base 18.8/18.9/18.8: no slowdown. SHIPPED 2026-11-01 in android/core/CMakeLists.txt (orca_jni budget 50.0 -> 47.1, total 117 -> 114.5);
   CI size of the shipped lib not checked yet.
2. **Python stdlib**: measured 2026-10-31, rejected. Whole stdlib is 14.4 MB raw / 4.26 MB in the APK (lib-dynload 1.81,
   top-level .py 1.16, encodings 0.32, asyncio 0.12); safe removals (unittest, lib2to3, doctest, pydoc) are ~0.3-0.5 MB
   (0.4% of the APK) and user plugins may import any of them. Biggest single items: unicodedata .so 0.4 MB,
   libcrypto_python 3.7 MB raw, libpython 6.7 MB raw (needed).
3. **Non-English i18n assets** (6.1 MB in APK, 20 MB raw): product decision for the user, not a
   cleanup.
