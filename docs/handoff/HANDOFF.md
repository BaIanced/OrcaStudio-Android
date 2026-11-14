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
- Android main = df0a903 (PR #85 = batch 2: UiState split, LayoutLab retired, package cycles; merged 2026-11-13 on the user's "/next-merge"; Claude-verified only, CI green). Before it: PR #84 = r548 (batch 1). Nothing unmerged on the branch but handoff docs. User-untested in main: `SIGNOFF-CHECKLIST.md`.
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
- Batch 1 merged (PR #84, archive "T-019" / "PR #84"). **Batch 2 open (user said yes to refactor first, answer 14): T-020, T-021 done (test baseline 45; plan `docs/audit/plan/uistate-split.md`, 8 moves); T-022..T-026c (all 8 moves) done; T-027 done (60a49b3; shot check: no regression, archive 2026-11-13); T-028 done (97c5995); T-029 checks passed, see below. PR #84's `scan` check failed on old commit cf75a81 (2026-11-04, a LAN address once in HANDOFF.md, gone from the tree); user told, their call (backlog).
- Open for the user: `gh variable set RELEASE_CERT_SHA256` (T-006); blank `Verdict (user)` line in memory cheaper-subagents; move docs/handoff to a private repo before T-038 (backlog).
- **T-029 gate passed; T-027 follow-up done 771ad11 (2026-11-13, archive "T-027 follow-up": 13 lab fields back as settings + migration, plate + on the left, cube/pie in `android/reference/`), device-checked on WSA (smoke 8/8, migration, + position).** Nothing installed on real devices. WSA has the 771ad11 debug build. Not device-checked: each new row's effect, tool-row long-press/drag.
- CI run 43406954976 (36106a0) green, not installed. **Batch 2 merged 2026-11-13 (PR #85), merge offer cleared.** Unverified on the branch: nothing.
- T-030 done (77cee24, `docs/design/DESIGN-SYSTEM.md`, source-checked only).
- T-031 done 18faa06 (device-checked on WSA VD: open target [128,128,12.5] inside the box; Close [128,128,0] on the box base, in range; `+ > Design` path not checked; a stray throwaway smoke.stl is on WSA plate 1).
- T-032 done 54e0ecd (device-checked WSA VD: smoke 8/8; Editing:/Done nodes in builder, Reshape, Push / pull, Cut, Paint; contact sheet docs/audit/T-032-rooms-contact-sheet.png; not checked: U-18 coach line, narrow width, German strings; smoke.sh now taps Done).
- T-033 done 459c5df (device-checked WSA VD: label 5.9 mm vs mesh 5.905 mm; smoke 8/8; label display-only, no tap-to-type; scaled instances and log radius slider not driven).
- T-034 done 162bb71 (CK compile/test rc 0, 313 tests; VD numbers from the subagent, orchestrator did not re-drive the device; Shrink native op untested until a CI build, axis colours not seen on screen).
- T-035 done (316 tests; WSA VD 3.00 -> 3.40 -> Undo 3.00, subagent-measured; no face-normal test, backlog candidate: native change to send face normals).
- T-036 done e0f54e8 (compile + tests rc 0; VD unlabeled nodes 1/1/2/1/1 -> 0 on 5 screens, subagent-measured; Open top / Repeat / Cut checkboxes code-only, not on device; helper scripts in orca-android-tools/t036).
- T-037 done a8ca8e8 (compile+tests rc 0; VD: no address outside Details, Already arranged/oriented, Undone; Redo notice and empty-stack Undo not device-checked).
- T-038 done eaf6682 (on WSA at phone geometry, user 2026-11-13; pictures in docs/media/, orchestrator-viewed; current name kept; details in the MASTER-PLAN tick). Phone was unreachable.
- T-039 done (2026-11-13): checks green on 52459ad (319 tests, smoke 8/8), CI r553 run 43525944893 green, installed WSA only; B-23a..c written; archive line. **PR #86 (batch 3) is open and green (scan + unit-tests); the user said yes to the offer: /next-merge merges it.** r553 installed on WSA and phone (2026-11-13, `adb connect` to the mdns-listed phone address; install only, not launched); Note still unreachable (`adb mdns services` showed no Note; `.114` is unauthorized, unknown device). PR #86 re-checked green (unit-tests + scan) 2026-11-13. **PR #86 merged 2026-11-13 (5f9a77b).** Batch 4 running.
- T-040 done 2c2aff1 (not pushed; compile+tests rc 0; WSA VD hole wall +0.399 mm at 0.2; slider dialog/link not seen on screen). T-041 done 41d7e15 (324 tests; WSA VD 20->25 and undo; toast/10-degree gate not on device). T-042 done (fit chips in builder Hole panel; heat-set sizes unconfirmed, see MASTER-PLAN tick). T-043 done (plate Shape/Text make editable designs; see MASTER-PLAN tick). **T-044 gate done 2026-11-14 (archive "T-044"): 2 T-041 findings fixed, 426 tests, smoke 8/8 on WSA debug; CI run 43569742443 green on 9a97023, not installed on any device; no PR opened yet; batch 4 merge offer shown 2026-11-14, waits for the user's yes.** Not done: B-23 row in BASELINE.md (count is in the MASTER-PLAN tick), device run of the review fix. Next after the merge: batch 5, T-050 (Fable spec, standalone launch). Fable gates are a standalone user launch (memory no-fable-dispatch).
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
