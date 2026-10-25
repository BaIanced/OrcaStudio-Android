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
- Android main = cb11452 (PR #36, branch up to c026d15 = cut batch, merged 2026-10-22; main APK run 39735501711). Untested in
  main: Check mesh feel, cut Flip paint fix, 3MF export in desktop Orca, pie menu (lab), pen 7d3a263, fan hold 298ee9f, Extrude
  F150 e13bebb, Quest stick zoom (r324). Unmerged on the branch: paint shop + paint depth r367, Build workspace 1-5 + 6a (user feel untested).
- Signing secrets live in the `release` environment (main + `claude/*` only; PR #35, 2026-10-22). Repo-level copies: user deletes.
- Latest build r397 (run 40168360935, UX P1-P5 + Task 12 fixes; installed on WSA/SHIELD/LAN phone); P1-P5 NOT yet looked at on a device. Chromebook/Note offline. Note r389, phone r376, Chromebook r374 (offline).
- **src-orca stays unmodified: native changes ship as `android/patches/src-orca/*.patch`, applied by a CI step (Patch OrcaSlicer); no local native build.**
- **Test device = WSA.** Note = S Pen tests only (locks; user says when awake); Quest = pseudo pen. Reference "Note".
- Devices: phone/Chromebook/Quest are `ip:port` serials: use `target.sh phone|chromebook` (reference "Phone").
  WSA `127.0.0.1:58526`; **USB serial = the user's SHIELD TV** (rooted; installs only). No printer tests (user away).
- **Cheapest interface first** (MCP `mcpao.sh` ~150 tok > `phone-ui.py layout` cut by regex > cropped shot only for GL looks).
- Session backups: phone `/data/local/tmp/phone-session-backup.{3mf,json}`, WSA `/data/local/tmp/session-backup.{3mf,json}`. `scadzip-test.zip` stays in `/sdcard/Download` (phone, Chromebook). Never write the LAN access code anywhere.
## Next task
- **One small step at a time** (user 2026-10-21). Auto-next (user 2026-10-24): if context is small and the next model = running model, start the next step without cueing; else cue `/clear` + `/next`.
- **User 2026-10-21: design deviations pre-approved** ("deviate if you find a more logical, intuitive way; I trust your judgement"); say what changed.
- Muse (2026-10-22): tasks in `MUSE-TASKS.md` on `muse/work` (edit via a `claude/*` PR, self-merge), then `TASK <n> READY`
  on #46; Muse answers with one draft PR per task. Claude verifies, merges into muse/work, cherry-picks code onto the work
  branch. Task 1 finding 4: fix the patch comment when regenerating 0001. **Each session start, run exactly:**
  `gh pr list --state open --json number,title,headRefName; gh issue view 46 --json comments --jq '.comments[-2:][]|.createdAt+" "+(.body|.[0:300])'` (no `--comments`).
  Tasks 1-18 merged (18 = PR #64, 2026-10-23). Sync muse/work android/ before each Kotlin task (#58).
  **Muse KEPT (user, 2026-10-23)** for audits + larger JVM-testable Kotlin only (Claude compiles each PR locally before
  merging), no native/patch work, no small fixes. Log Claude's effort per task in the archive; if a task costs more than
  doing it directly, tell the user plainly and stop. Merge commands START with `gh pr merge`. No stacking.
- User feel untested (backlog): paint plan (r373), Build workspace 1-6, Filament materials tiers 1+2, Realistic render 1+2 (r390).
- Local debug test on WSA: recipe in the reference ("Local APK").
- Open (backlog): app 3MF in desktop Orca (fix applied; user must open a fresh export), printer/user-only checks, pen input, plate switcher pick.
- Task 12: 5/8/9 in r392 (9 WSA-checked, archive). 1-4 closed (static re-check, not device-triggerable). 10/12/13 fixed 6303398, r395 WSA smoke OK (add text 52 chars, SVG); 11 note only.
- Crawler + monkey done (no crash/ANR; archive); UX inventory + proposals P1-P7 in `docs/UX-INVENTORY.md` (user hasn't picked proposals).
  P1 (c486aed), P2 (friendly FileNotFound/Security text), P3 (raw key -> small "Setting:" line in help popups) are in r396; P4 (Changed-only chip count; search already existed) and P5 (picker shows "Flow Rate & Vol Calibrated" as small second line, Widgets.kt) are in r397. WSA r397 look: P3 (Setting: line) and P5 (second line) OK; P1/P2 error text not triggered (zip entry opened silently), P4 chip not seen (needs a changed setting). r397 is on all 3 attached devices.
- P6 (paneTitle on tab content) + About text without AI line: build run 40170895834 dispatched (abed57b); not yet installed/looked at. P7 crawler fixed in ~/orca-android-tools/crawl.py (Quit->Yes = clean exit, no Back at root); Edit-button repro still untested.
- r398 (run 40170895834) installed on WSA/SHIELD/LAN phone; WSA window did not draw (no UI dump), so About text and pane titles are not yet looked at.
- Next step: look at About text + a tab screen on a device (phone VD or WSA retry) -> Sonnet 5.5 (medium)

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
