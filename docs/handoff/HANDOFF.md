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
- Android main = 566dc0c (PR #88 = batch 5 + push/pull fixes, merged 2026-11-15, user-approved; r564 phone-checked for push/pull). Before: 15da260 (PR #87 = batch 4: T-040..T-044; merged 2026-11-14 on the user's "/next-merge"; Claude-verified only, CI green). Before it: PR #86 batch 3, #85 batch 2, #84 r548 (batch 1). Nothing unmerged on the branch but handoff docs. User-untested in main: `SIGNOFF-CHECKLIST.md`.
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
- Batches 1-4 merged (PRs #84-#87, main 15da260); per-task results: MASTER-PLAN ticks + archive. Fable gates are a standalone user launch (memory no-fable-dispatch).
- Open for the user: `gh variable set RELEASE_CERT_SHA256` (T-006); blank `Verdict (user)` line in memory cheaper-subagents; move docs/handoff to a private repo before T-038 (backlog).
- **Batch 5 (T-050..T-055) done; gate closed 2026-11-14** (MASTER-PLAN ticks, BASELINE "After batch 5", archive "Trimmed from handoff (batch 5 state lines)" + "T-055 close-out on r561"). Commits up to e574e89 are on the branch, not on main.
- **Visual round 3 answered (ANSWERS 32).** Hot: G (pen = context menu + submenus on hover, not necessarily radial; same nesting for an E-style edge panel, like Samsung Edge), L (pin what you use). Warm: A (static, not turning with the plate), C, D, E (swipe-out bar + up/down scrub, size = static slider), H, I. Show first: B, F, I. Cold: J, K. Bigger point: the flow feels OFF, the floating window covers the work, the left column of round badges must go (fewer tools, nested). **Round 4 sent 2026-11-15: artifact J1GKjZ8xupCgfXJcFiabaC (local `docs/audit/mockups/visual-round4.html`; script parse-checked only, not driven in a browser).** No tool column; edge handle + hold menu share one nested tree (Add / Edit model / Arrange / Object / View / Measure; in Reshape: Brush / Mirror / Size / View / Undo / Done); hold an entry = pin; size starts as a pinned vertical slider (my deviation); brushes switch A strip / B ring / F arc. **Spec `docs/design/EDGE-MENU.md` written 2026-11-15 (Opus, source-checked, nothing built): tree, edge panel, hold menu (slide-through via the unused `onLongPressTouch` hook), shortcuts dock, brush strip, setting `edge_menu` (Experimental, off), Batch 8. **User yes 2026-11-15 (ANSWERS 38): Batch 8 T-080..T-086 in MASTER-PLAN; build them in order (coordinator rule, line 37).** **T-080 done (ef66622); T-081 (ad9d0eb), T-082 (afbfe7a), T-083 (0358364) done, device-checked WSA VD (archive); next = T-084 (opus, high).** Edge panel + Hold menu rated warm (ANSWERS 37). **ANSWERS 36: pins don't work in the mockup; A brush strip liked, swipe L/R scrubs options and fades the others, swipe up = size; edge panel / hold menu unrated.** Old: waiting on hot / cold by part name (Edge panel, Hold menu, Pins, A, B, F); log in ANSWERS (33); hot parts -> one Fable spec (standalone launch). Gemini brief-2 prototype (2-finger undo fixed): `docs/audit/gemini-proto-2.html`, artifact U8vDFguusH4S11kauYo8qj; not rated yet. Round 3 artifact: U67j65rpZCmVX66gLWjGmk.
- Plate shrink on panel drag fixed (SCALE_FLOOR 0.6, `PlateRenderer.onSurfaceChanged`); untested: pinch / pan, ortho, landscape, `fit()` with the floor. Double-tap frame with the panel up: backlog.
- Devices 2026-11-14 (user, later the same day): phone is home and ready; the user tries builds on the phone.
- **r564 phone retry 2026-11-15: push / pull FIXED (ANSWERS 35, user-checked).** Older: **r561 phone trial: NOT FINE (ANSWERS 31, 2026-11-14); merge blocked.** User saw: added object sometimes missing, a stray cube beside the object on Push / pull, no selection outline. Repro on the WSA VD (agent): (1) import onto a project goes to a new plate off screen, camera never followed (old behaviour, Viewport.kt framing dropped in aafa5f5); (2) the builder's saved draft rendered 150 ms after `ScadController.open()` and was placed as a stray object when a big mesh export was slow (old race, now hit by every Edit shape / wrap). (3) no outline: NOT reproduced (may follow from 2). Fix **902b56b** (local, not pushed, untested on a phone): compile + JVM tests + smoke 8/8 + privacy scan clean. r562 (run 43681083601, from 902b56b) **phone retry 2026-11-15 (ANSWERS 33, 3DBenchy): import + slice fine; still NOT FINE, merge blocked.** **Fixes coded 2026-11-15: 27681f4 (local, not pushed; compileReleaseKotlin + JVM tests pass; NOT yet run on a device):** (a) `ScadController.holdBuild`: `ShapeController.addSketch` holds plate renders while the new sketch's drawing is open; `setSketch` / `cancelNewSketch` release it, and the plate catches up once only if the object's design differs (Cancel = no render). Done keeps the sketch `beside()` the object (same as every added shape; my call, not asked). (b) `PushPullCard` (ShapeBuilder.kt) replaces the Add palette while `faceMode` is on; its Shapes button goes back to the Shapes tool. Room toolbar shows a LinearProgressIndicator while `vm.roomRouting`. **WSA VD run 2026-11-15 (debug build of 27681f4, 225k-tri box made by a script, files/smoke/densebox.stl in the debug app; entry = toolbar More > Edit shape):** device-checked: Sketch -> Cancel leaves one box, no stray cube; Sketch -> Done keeps Sketch 1 beside the box; the Push / pull card replaces the Add palette. Not seen: busy bar (the wrap took ~3 s, shots lag ~6 s). **2026-11-15 late: 3D-tap "failure" was a test artifact, not a bug** (device-checked, WSA VD, fresh MESH densebox2.stl via the debug `orca.model` extra): mesh edit tap in the room selects tris; Shapes tab wraps (tool none, design_get loads), box tap -> `selected [1]`; Push / pull tab -> face tap shows outline + arrow, arrow drag pushed +21.5 mm. Cause of the old miss: the 3D view widens after the wrap (181 -> 275 px, box x 85 -> 132), so taps measured before the wrap missed; also the blue height handle sits on the top face (tap beside it). 10 mm lead disproven. Room on a mesh: re-read `view_state` after every tab switch before tapping. **r563 (run 43691732192, installed on the phone) NOT FINE again (ANSWERS 34): sketch with an arc -> Done -> Push / pull did nothing. Cause: faceAt / pushFace only handled MESH shapes. Fix 4e3cd19 (box-side push for non-MESH shapes; device-checked WSA VD: arc sketch, plain sketch, Cancel path; JVM 362 + smoke 8/8). Unchecked: arc face tap, turned shapes, cylinders, holes, mesh path re-run, phone.** r564 = run 43697848319. Optional: busy bar check (830k `weirdThingImadeSculpting.stl` in /sdcard/Download for a slow wrap). Untested: editObject race, .scad open, Add menu entries after the change, `editMesh` export failure leaves an empty editor. Unfixed: Edit shape on an 830k-tri mesh hung >2 min while a slice ran (roomBegin waits for the engine); redo/undo of delete can land an object on an off-view plate. WSA test plates hold leftover throwaway objects.
- **/design-sync done 2026-11-14:** tokens-only Claude Design project "OrcaStudio Android" uploaded (details and re-sync recipe in `.design-sync/NOTES.md`). Re-sync only when the Compose theme changes.
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
