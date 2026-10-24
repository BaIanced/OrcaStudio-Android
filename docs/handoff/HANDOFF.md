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
- Latest build r391 (run 40075524166, Layer lines): WSA, SHIELD (2026-10-24). Note r389, phone r376, Chromebook r374 (offline).
- **src-orca stays unmodified: native changes ship as `android/patches/src-orca/*.patch`, applied by a CI step (Patch OrcaSlicer); no local native build.**
- **Test device = WSA.** Note = S Pen tests only (locks; user says when awake); Quest = pseudo pen. Reference "Note".
- Devices: phone/Chromebook/Quest are `ip:port` serials: use `target.sh phone|chromebook` (reference "Phone").
  WSA `127.0.0.1:58526`; **USB serial = the user's SHIELD TV** (rooted; installs only). No printer tests (user away).
- **Cheapest interface first** (MCP `mcpao.sh` ~150 tok > `phone-ui.py layout` cut by regex > cropped shot only for GL looks).
- Session backups: phone `/data/local/tmp/phone-session-backup.{3mf,json}`, WSA `/data/local/tmp/session-backup.{3mf,json}`. `scadzip-test.zip` stays in `/sdcard/Download` (phone, Chromebook). Never write the LAN access code anywhere.
## Next task
- **One small step at a time** (user 2026-10-21). Auto-next (user 2026-10-24): if context is small and the next model = running model, start the next step without cueing; else cue `/clear` + `/next`.
- **User 2026-10-21: design deviations pre-approved** ("deviate if you find a more logical, intuitive way; I trust your judgement"); say what changed.
- Paint plan complete (r373); user feel untested (backlog "Paint panel UI", "Paint penetration").
- Muse (2026-10-22): tasks in `MUSE-TASKS.md` on `muse/work` (edit via a `claude/*` PR, self-merge), then `TASK <n> READY`
  on #46; Muse answers with one draft PR per task. Claude verifies, merges into muse/work, cherry-picks code onto the work
  branch. Task 1 finding 4: fix the patch comment when regenerating 0001. **Each session start, run exactly:**
  `gh pr list --state open --json number,title,headRefName; gh issue view 46 --json comments --jq '.comments[-2:][]|.createdAt+" "+(.body|.[0:300])'` (no `--comments`).
  Tasks 1-18 merged (18 = PR #64, 2026-10-23) (Task 12 JNI audit fixes in backlog). Sync muse/work android/ before each Kotlin task (#58).
  **Muse KEPT (user, 2026-10-23)** for audits + larger JVM-testable Kotlin only (Claude compiles each PR locally before
  merging), no native/patch work, no small fixes. Log Claude's effort per task in the archive; if a task costs more than
  doing it directly, tell the user plainly and stop. Merge commands START with `gh pr merge`. No stacking.
- Build workspace 1-6 (6df309d), Filament materials tier 1 (b978e15) + step 2 Surface look (r389) + tier 2 Layer lines (View menu,
  4b7d432, r391; Claude WSA-checked): user feel untested.
- Local debug test on WSA: recipe in the reference ("Local APK").
- Open (backlog): app 3MF in desktop Orca (fix applied; user must open a fresh export), printer/user-only checks, pen input, plate switcher pick.
- Realistic render (View menu, default off) builds 1+2 in r390: Claude WSA-checked, user feel untested (backlog).
- Task 12 findings 5/8/9 fixed (9661c20, aecb380, 04bb9ad) in r392 (run 40083179826), installed on WSA, phone, SHIELD 2026-10-24 (Chromebook, Note offline). WSA-checked r392 (support-paint Fill): hold-fill stays, slide-off cancel keeps the earlier fill, one Undo removes it. Findings 5/8 not exercised.
- Plate surface (View menu) bdb9cf4, WSA-checked; r393 (run 40127410048) installed on WSA, phone, SHIELD 2026-10-24 (Chromebook, Note offline); user look pending.
- Local debug app (older native libs, r391) is still on WSA + phone with release data; remove when done. Next step: pick the next backlog item (grep "[Opus]" / user-only checks) -> Sonnet 5.5 (medium)

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
9. Commit trailer: `Co-Authored-By` + `Claude-Session` lines as given in the session.
10. **Model fit (user, 2026-10-16):** say when a task suits Opus better (hard shader/math debugging, subtle multi-file
    design, after 2 failed tries), before starting or as soon as it shows (`/model` swap + `/next`).
11. Efficiency (user, 2026-10-10): compile Kotlin locally before every push (command in the reference); batch 2-4 changes per
    build, don't dispatch builds that will be superseded; write code with Edit or python raw strings, never plain python
    heredocs (they ate backslashes 5x); write python scripts with Write, then run them.
