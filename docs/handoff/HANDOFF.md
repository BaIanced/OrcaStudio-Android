# Handoff: OrcaStudio-Android + OrcaStudio aarch64 Flatpak (hot file, 2026-10-13)

Read this first. Don't use any title for the user ("Supreme Master" was a joke; never use it).
- `HANDOFF-REFERENCE.md`: devices, tools, build commands, recipes. **Read its device section before driving a device.**
- `HANDOFF-BACKLOG.md`: pending / untested / ideas / user decisions waiting. Grep it; don't read it up front.
- `HANDOFF-ARCHIVE.md`: history, verified features, file maps, past causes. Don't read it or `NOTES-*.md` / `DEMO-rin.md`
  up front; grep them when a question needs them (e.g. `grep -n "AMS mapping" HANDOFF-ARCHIVE.md`).

## Rolling handoff rules (user, 2026-10-12; full text in the /next skill)
- Under ~8 KB; verified -> archive (dated) + one line here; pending -> backlog. Log user verdicts first; ask before user-only retests.

## State
- One repo: OrcaStudio-Android (code + these docs, `docs/handoff/`), branch `claude/handoff-continuation-rseql3`. Autonomy (user, 2026-10-11): decide branches and next steps
  yourself; **main merges / releases need the user**. Offer a merge when a batch is verified.
- Android main = 0170950 (PR #65, branch at r398, merged 2026-10-25). Untested by the user in main: Build workspace, paint shop/depth, Filament materials, Realistic render, UX P1-P6, Check mesh feel, 3MF export in desktop Orca, pen, fan hold, Extrude F150.
- Signing secrets live in the `release` environment (main + `claude/*` only; PR #35, 2026-10-22). Repo-level copies: user deletes.
- Latest CI build r425 (run 40290219929) on WSA + SHIELD (release app); WSA also has the local debug app (newer blend voxel). Phone offline.
- **src-orca stays unmodified: native changes ship as `android/patches/src-orca/*.patch`, applied by a CI step (Patch OrcaSlicer); no local native build.**
- **Test device = WSA.** Note = S Pen tests only (locks; user says when awake); Quest = pseudo pen. Reference "Note".
- Devices: phone/Chromebook/Quest are `ip:port` serials: use `target.sh phone|chromebook` (reference "Phone").
  WSA `127.0.0.1:58526`; **USB serial = the user's SHIELD TV** (rooted; installs only). No printer tests (user away).
- **Cheapest interface first** (MCP `mcpao.sh` ~150 tok > `phone-ui.py layout` cut by regex > cropped shot only for GL looks).
- Session backups: phone `/data/local/tmp/phone-session-backup.{3mf,json}`, WSA `/data/local/tmp/session-backup.{3mf,json}`. `scadzip-test.zip` stays in `/sdcard/Download` (phone, Chromebook). Never write the LAN access code anywhere.
## Next task
- Auto-next (user 2026-10-25): keep going while context is small and the model fits; one small step each.
- **User 2026-10-21: design deviations pre-approved** ("deviate if you find a more logical, intuitive way; I trust your judgement"); say what changed.
- Muse (full discretion, user 2026-10-25; memories muse-*): tasks in `MUSE-TASKS.md` on `muse/work`, `TASK <n> READY` on #46, one draft PR each;
  Claude compiles, merges, cherry-picks. Audits + JVM-testable Kotlin only.
  Tasks 1-18 merged. **Session start, run exactly:** `gh pr list --state open --json number,title,headRefName; gh issue view 46 --json comments --jq '.comments[-2:][]|.createdAt+" "+(.body|.[0:300])'`
- User feel/checks open (backlog): paint plan, Build workspace, Filament materials, Realistic render, 3MF in desktop Orca (+ bed type), pen, plate switcher pick, band fix, measure step 1, push/pull fold.
- Local native build works (2026-10-26; deps refresh + patches steps in reference "Native change on the debug app").
- Local debug test: reference "Local APK" + "WSA debug loop" (parked window: taps via `tapf.sh`).
- **Offload (user 2026-10-25/26): max token use onto free opencode models, growing leeway by track record** (Claude specs + verifies).
  DeepSeek V4.1 Flash = default; self-checking runner `octauto.sh` (`D:\oct-backup\jobs\README.md`), ledger `trust-profile.md`.
- CAD plan accepted (user 2026-10-25), steps in `NOTES-cad-reuse.md`. Steps 0 + 1a Claude-verified WSA r419 (archive);
  1a user feel untested ("Blend joins (mm)"). 1b (tap an edge) parked.
- CAD 2a-2c (r421) + step 3 deformers (r423, twist refinement fix by DeepSeek) Claude-verified WSA 2026-10-26 (archive); user feel open.
  Latest build r423 (run 40286374371) on WSA + SHIELD; phone offline. Big Pickle deform2: same result as DeepSeek, 1.5x slower (ledger).
- CAD step 4 Smooth blend (`OrcaVdbBlend.cpp`, More menu, MCP `object_smooth_blend`): WSA-tested 2026-10-26 (L shape 360 -> 27k faces, bbox kept; lid box 52k, 6 s; 147k mesh 5 s). **Open bug: the 176k-face gear stalled forever at voxel 0.4 mm (diag/300) in OpenVDB closing, fine at 0.74-0.81 mm; cause unknown (geometry-dependent), so voxel = diag/200 now. No cancel.** Stage logs "smooth blend:" in logcat. User feel untested.
- CAD step 5a sculpt brush core (`MeshSculpt.hpp`, `OrcaSculpt.cpp`, MCP `object_sculpt`) built locally, WSA-verified 2026-10-26 (archive); debug app, no CI build yet. No UI.
- Next step: CAD step 5b sculpt UI (brush strip, drag = dab with camera locked, fast partial mesh update so 100k+ faces stay live) -> Opus 5.5 (high)

## User decisions (2026-10-13)
- Modeling goal: model without writing code (OpenSCAD only a backstop).
- (2026-10-14) Each newly imported file gets its own plate (active if empty, else first empty, else new; `FileController.useEmptyPlate`).
- Plate activation must not move the camera when zoomed out (sometimes moving is fine).
- Plate switcher: **keep the lab until the user says otherwise; don't ask again** (mockups https://claude.ai/artifact/E3MtY8rQ1dMGHhibY2oNCX).
  Once picked: delete the losers and the lab.

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
