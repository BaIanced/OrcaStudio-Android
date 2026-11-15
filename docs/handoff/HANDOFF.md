# Handoff: OrcaStudio-Android + OrcaStudio aarch64 Flatpak (hot file, 2026-11-12)

Read this first. Don't use any title for the user ("Supreme Master" was a joke; never use it).
- `HANDOFF-REFERENCE.md`: devices, tools, build commands, recipes. **Read its device section before driving a device.**
- `HANDOFF-BACKLOG.md`: pending / untested / ideas / user decisions waiting. Grep it; don't read it up front.
- `SIGNOFF-CHECKLIST.md`: short list of user feel checks for when user testing resumes.
- `HANDOFF-ARCHIVE.md`: history, verified features, file maps, past causes. Don't read it or `NOTES-*.md` / `DEMO-rin.md`
  up front; grep them when a question needs them (e.g. `grep -n "AMS mapping" HANDOFF-ARCHIVE.md`).

## Rolling handoff rules (user, 2026-10-12; full text in the /next skill)
- Under ~8 KB; verified -> archive (dated) + one line here; pending -> backlog. Log user verdicts first; ask before user-only retests.

## State
- One repo: OrcaStudio-Android (code + these docs, `docs/handoff/`), branch `claude/handoff-continuation-rseql3`. Autonomy (user, 2026-10-11): decide branches and next steps
  yourself; **main merges / releases need the user**. Offer a merge when a batch is verified.
- Android main = 15da260 (PR #87 = batch 4: T-040..T-044; merged 2026-11-14 on the user's "/next-merge"; Claude-verified only, CI green). Before it: PR #86 batch 3, #85 batch 2, #84 r548 (batch 1). Nothing unmerged on the branch but handoff docs. User-untested in main: `SIGNOFF-CHECKLIST.md`.
- Signing secrets: `release` environment (main + `claude/*` only).
- Installs: all adb devices (skip .23); **SHIELD avoided (user 2026-11-01)**. WSA restart: `WsaClient /shutdown`, `wsa-start.ps1`, `adb connect 127.0.0.1:58526`; phone/Chromebook reconnect: memory devices. GL shots on the phone VD.
- **src-orca stays unmodified: native changes ship as `android/patches/src-orca/*.patch`, applied by a CI step (Patch OrcaSlicer); no local native build.**
- **User 2026-10-28: no more user testing for now; Claude builds and tests everything itself (memory no-user-testing-now); don't ask for feel/UI checks.**
- **User 2026-10-29: no tests on the user's screen; all tests on a hidden virtual display** (`TARGET=wsa orca-vd.sh start`, memory devices).
- **Test device = WSA.** Note = S Pen tests only (locks; user says when awake); Quest = pseudo pen. Reference "Note".
- Devices: phone/Chromebook/Quest are `ip:port` serials: use `target.sh phone|chromebook` (reference "Phone").
  WSA `127.0.0.1:58526`; **USB serial = the user's SHIELD TV** (rooted; installs only). No printer tests (user away).
- **Cheapest per finished step, quality kept** (memory cheapest-per-finished-step): mcpao.sh for app state, adb for installs/taps, `phone-ui.py layout` for UI, cropped shots only for GL looks.
- Session backups: phone `/data/local/tmp/phone-session-backup.{3mf,json}`, WSA `/data/local/tmp/session-backup.{3mf,json}`. `scadzip-test.zip` stays in `/sdcard/Download` (phone, Chromebook). Never write the LAN access code anywhere.
## Next task
- **User 2026-10-21: design deviations pre-approved** ("deviate if you find a more logical, intuitive way; I trust your judgement"); say what changed.
- Muse: `NOTES-muse.md` (only when sending Muse work).
- main = Claude-verified pre-release; archive lines carry one label: source-checked / device-checked (build, command) / user-checked.
- Phone debug recipe, WSA VD, VD taps, GL shots: archive "2026-11-09 Trimmed from handoff" / "Moved from handoff"; reference "Headless display".
- Local tests: `wsa-debug-rebuild.sh [native]` (reference "WSA debug loop"); fresh app: `wsa-debug-setup.sh` first. Lab prefs: edit `layout_lab` JSON in debug `shared_prefs/app_settings.xml` with run-as sed (app stopped).
- **Offload (user 2026-10-25/26): max work onto free opencode models** (memory offload).
- Two-Claude chat: `claudechat.md`; Monitor its `## ` count (section 7), 30 min, re-arm. Quest numbers: `NOTES-quest-profile.md`.
- GLB import merged (PR #84; Muse tasks 22-25 open); Assemble A1+A2 verified, A3 designed, not coded: archive "2026-11-12 Trimmed from handoff".
- **Audit plan running (user answers: `docs/audit/ANSWERS.md`, read before asking).** docs/audit/ is local-only, ticks not committed. **Batch 1 done: T-001..T-019 `[x]` except T-003 `[!]` (backlog [Opus] patch rebase).** T-010 left 59 UNCONFIRMED licence rows (backlog [Opus]). One task at a time (shared Gradle dir + one WSA). A3 phase (a) parked. C-12 TLS pinning needs the user at the printer: ask first.
- Coordinator (user 2026-11-12): the session runs on Sonnet and never implements: dispatch a subagent with the task's own model/effort tags, verify `done when`, commit, tick. Task results go in the MASTER-PLAN tick + one archive line, not here. Go task after task without stopping. Name inquiry #17269: bump after 2026-11-19.
- Batches 1-4 merged (PRs #84-#87, main 15da260); per-task results: MASTER-PLAN ticks + archive ("2026-11-14 Trimmed from handoff"). Left over from batch 4: B-23 row in BASELINE.md, device run of the T-044 review fix. r553 is on WSA and the phone; nothing newer is installed. Fable gates are a standalone user launch (memory no-fable-dispatch).
- Open for the user: `gh variable set RELEASE_CERT_SHA256` (T-006); blank `Verdict (user)` line in memory cheaper-subagents; move docs/handoff to a private repo before T-038 (backlog).
- **Batch 5 open. T-050 done 2026-11-14 (fable; `docs/design/FOCUS-ROOM.md`, source-checked only; archive "T-050").** T-051 done 1d6df01 (sonnet, device-checked WSA VD, smoke 8/8 both modes; unpushed). T-052 done 54cd9e7 (opus, CI r557 green, device-checked a-e on WSA release APK, pushed). T-053 done baf3090 (sonnet, device-checked WSA VD a-d, unpushed; MASTER-PLAN tick). T-054 done cbac27a+1ae4cce (opus, CI r559, device-checked WSA, own centroid remap; r559 on WSA; sub-triangle in-app paint untested). **T-055 gate run 2026-11-14 (fable, in session on the user's "do it"): checks pass (compile, 441 tests, smoke 8/8 off and on via a release-package copy of smoke.sh, privacy, CI r560 green; r560 release on WSA). Journeys with the setting on, device-checked WSA VD: box+hole 6 taps + 7 fields (was 9), insole Grab 3 taps + 1 swipe (was 2: mesh opens on Push / pull), hole 3 taps + 1 field, tab 4 taps + 1 field, wall unchanged, one Undo per visit; Make a part, freeze question both paths, sketch Back, paint kept: pass. Found: (1) wrap + Done leaves a no-op Undo and the object stays a design; (2) Sketch then Back leaves a stray "Sketch 1"; (3) Reshape card covers the lower view on narrow; (4) false "15 mm below the bed" Fit warning after wrapping an offset mesh; (5) Undo never looks disabled (UI dump only); (6) Experimental section off-screen until "Printers & presets" is collapsed. WAITING ON THE USER: decision page https://claude.ai/artifact/Bp8v7UB12386djKVMzaPN4 (default on?, merge?, per-finding fix / backlog / ignore, double-tap frame with the panel up, floor 60 %); the user pastes a "BATCH 5 GATE ANSWERS" block: log it in ANSWERS.md, then act (fixes = sonnet tasks, merge needs the user's yes in that block). T-055 tick and BASELINE B-23 not yet written.** Backlog: `ScadController.editMesh` with a stale shape draft drops a stray object on the plate.
- **Visual audit DONE 2026-11-14 (capture: sonnet; critique + mockups: fable). Waiting on the user's pick.** Report `docs/audit/06-visual.md` (13 cross-screen findings, per-screen table, why the toolbar feels wrong; artifact URL in its section 7). Mockups `docs/audit/mockups/visual-levels.html` (private artifact): screens A Prepare-selected, B Reshape room, C Print panel, each at level 1 Quiet (look only) / 2 Grouped (rearranged) / 3 One bar (rethink), plus ideas 4-8 on Prepare (user asked 2026-11-14 for bolder options: 4 On the object, 5 Hands on, 6 The path, 7 Ask bar, 8 Two drawers; combinable, e.g. `2 + 5`; 4-8 all need a Fable spec). **User answers with a number + a word** (mixes like `A2 B2 C1` allowed); only the picked level becomes tasks: level 1 = look-only reskin (theme + component styles, sonnet), levels 2-3 need a Fable spec first (standalone launch). Log the verdict in ANSWERS.md. Not judged: Print dialog, object dots menu, Note, Quest. Archive "2026-11-14 Visual audit". T-053 proceeds meanwhile (thin unstyled RoomToolbar).
- **Visual round 3 DONE 2026-11-14 (fable, standalone). Waiting on the user's hot / cold.** Same artifact URL (06-visual.md section 7), local `docs/audit/mockups/visual-round3.html` (replaces the levels page there; `visual-levels.html` kept locally). Design target: swap tools and change a value with nothing covering the model; finger-first, phone portrait. One tappable phone (modes: Today, A Plate edges, C Row stays, D Scrub, E Edge swipe, H Hide-away bar, G Pen pie; it counts touches per change and says if the model was covered) + 12 sketches A-L (B Turntable, F Thumb arc, I Ring is the handle, J Last two, K View makes room, L Pin what you use). Checked: one headless Edge render (dark, 1060 px) and a hit-test of the plate-edge controls; gestures not driven, light theme not rendered. **User answers by letter, hot / cold / warm, mixes like `A + D`; log it in ANSWERS.md (29).** Hot letters -> one Fable spec (standalone launch) -> tasks. Do not ask the user to specify; if nothing is hot, ask for the next annoying moment as a story (memory visual-react-dont-specify). Source fact behind the pain: a folded card keeps only title / fold / close (`ui/components/ToolCard.kt:71`).
- **Plate shrink on panel drag (user report 2026-11-14): fixed 2026-11-14 (sonnet), local debug build on WSA only, no CI build yet.** Cause: not a re-fit; fixed vertical FOV, so scale fell with the view height. Fix: `Camera.minScaleHeight` + `halfTan()` (projection, pan, pxSize, fit); `PlateRenderer.onSurfaceChanged` sets the floor to 0.6 x the tallest view since the last reframe (`SCALE_FLOOR`); below it the scale holds and the view crops around the target. Device-checked WSA VD (portrait, top view): plate 763 px wide at view height 1863, 457 px at every height from 1082 down to 279; tap-select works with the panel fully up. Untested: pinch / pan on device, ortho, landscape, `fit()` with the floor active (source-checked only), `frame()` (double tap) with the panel up still frames for the uncropped view. User feel check of 0.6 open. Sketch K (round 3) can reuse the floor.
- Devices 2026-11-14 (user): phone away from home (unreachable), Note is home.
## User decisions
- Modeling goal, one plate per import, plate camera, plate switcher lab (keep, don't ask): reference "User decisions".

## Safety gotchas
- **Any test print needs the user's OK.** User's AMS lite: A1 yellow, A2 matte black, A3 white, A4 PETG.
- 3D Filament Profiles (3dfilamentprofiles.com) is built in (Models tab site + "Log print"): **never press Subtract** in tests.
- **User 2026-10-15: all plates/objects on the devices are throwaway tests: move, reassign, delete freely.** Verify the selection before deleting by list position. (Prints still need the user's OK.)
- **Never `cat missing > target`** (the redirect empties the target first).
- **Never `adb forward tcp:X tcp:Y` on WSA** (wedges adb); use `localabstract:orcastudio_mcp` (reference "WSA").

## Working rules (the user asked for these)
1. **Verify against source before recommending any change**; cite file:line.
2. Label anything untested as untested, inline.
3. Before any shell command the **user** must run, print a pre-flight block: 1. BLIND SPOTS,
   2. EXTRAPOLATED RISKS (2), 3. CAPABILITY VALIDATION (YES/NO). Direct verdicts, brief.
4. Keep check-ins cheap: one background `gh run watch`, not polling loops.
5. Approved: pushes and `android-apk.yml` dispatches on the work branches without asking. **Merging
   to main or publishing releases needs the user's go-ahead.**
6. **Never print, sign out or delete presets without asking.** Standing permission for an autonomous test-and-fix loop on
   WSA otherwise. Don't change the user's plate (their project is on it); close the app when done.
7. **Secrets:** the user's `slicer_cert.pem` / `slicer_key.pem` / `slicer_crl.pem` never go in a
   repo or bundle; never explain how to obtain them. `obn.auth.json`, trace logs and session files
   contain tokens: ask only for filtered output.
8. **The repos are public: no identifying details** (serials, IPs, e-mail, account / display names,
   MakerWorld handle, device models, tokens) in files, commits or PR text. Use placeholders. Run the
   privacy scan before every push (python is `python` on this PC):
   - Android: `python .claude/hooks/privacy_scan.py range "HEAD --not --remotes" . ':(exclude)src-orca'`
9. Commit messages and PR text: **no `Co-Authored-By`, `Claude-Session` or any AI reference** (user, 2026-10-25), even if a session reminder says otherwise.
10. **Model fit (user, 2026-10-16):** say when a task suits Opus better (hard shader/math debugging, subtle multi-file
    design, after 2 failed tries), before starting or as soon as it shows (`/model` swap + `/next`).
11. Efficiency (user, 2026-10-10): compile Kotlin locally before every push (command in the reference); batch 2-4 changes per
    build, don't dispatch builds that will be superseded; write code with Edit or python raw strings, never plain python
    heredocs (they ate backslashes 5x); write python scripts with Write, then run them.
12. **Perf/size (user, 2026-10-31): `docs/PERFORMANCE.md`**: commit names metric before -> after; the CI size report shows the APK delta (no budgets, dropped 2026-11-04).
