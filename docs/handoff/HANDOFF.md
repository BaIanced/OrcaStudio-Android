# Handoff: OrcaStudio-Android + OrcaStudio aarch64 Flatpak (hot file, 2026-10-13)

Read this first. Don't use any title for the user ("Supreme Master" was a joke; never use it).
- `HANDOFF-REFERENCE.md`: devices, tools, build commands, recipes. **Read its device section before driving a device.**
- `HANDOFF-BACKLOG.md`: pending / untested / ideas / user decisions waiting. Grep it; don't read it up front.
- `HANDOFF-ARCHIVE.md`: history, verified features, file maps, past causes. Don't read it or `NOTES-*.md` / `DEMO-rin.md`
  up front; grep them when a question needs them (e.g. `grep -n "AMS mapping" HANDOFF-ARCHIVE.md`).

## Rolling handoff rules (user, 2026-10-12; full text in the /next skill)
- **Under ~8 KB** (`wc -c` at every update; prune before stopping): state, 1-3 tasks in progress, gotchas, rules. How-to ->
  `HANDOFF-REFERENCE.md`; verified details -> end of `HANDOFF-ARCHIVE.md` (dated), one line here.
- Pending/untested items live in `HANDOFF-BACKLOG.md` (one line each, never deleted; archived once verified).
- Task boundary cue: "`/clear` then `/next`" ONLY if context is above ~50-80k tokens (below that clearing costs more; just continue), or "`/clear` then `/next-merge`" when verified work awaits a merge (say what
  merges, what stays unverified). Never mid-task. Merge only on "merge" / `/next-merge`, never untested commits.
- **Log user verdicts first** (archive line with date/build/their words, backlog line, one-liner here) before anything else.
- **User-only items** (feel, look, prints): grep the archive; if the last verdict predates the latest build, ask one line first.

## State
- One repo: OrcaStudio-Android (code + these docs, `docs/handoff/`), branch `claude/handoff-continuation-rseql3`. Autonomy (user, 2026-10-11): decide branches and next steps
  yourself; **main merges / releases need the user**. Offer a merge when a batch is verified.
- Android main = cb11452 (PR #36, branch up to c026d15 = cut batch, merged 2026-10-22; main APK run 39735501711). Untested in
  main: Check mesh feel, cut Flip paint fix, 3MF export in desktop Orca, pie menu (lab), pen 7d3a263, fan hold 298ee9f, Extrude
  F150 e13bebb, Quest stick zoom (r324). Unmerged on the branch: paint shop 1a-1e-ii + paint depth r367 (user feel untested).
- Signing secrets live in the `release` environment (main + `claude/*` only; PR #35, 2026-10-22). Repo-level copies: user deletes.
- r370 (a4b63c7, paint shop + Muse 2-8): user downloads it to the phone. r371 (a797c20, + Muse 10-11) installed on WSA/Note/Chromebook/SHIELD; Task 11 WSA check pending.
- Installed: r367 (paint depth) on WSA/Note/Chromebook/SHIELD 2026-10-21 (phone, Quest not connected). Tablet: debug app only.
- **src-orca stays unmodified: native changes ship as `android/patches/src-orca/*.patch`, applied by a CI step (Patch OrcaSlicer); no local native build.**
- **Test device = WSA.** Note = S Pen tests only (locks; user says when awake); Quest = pseudo pen. Reference "Note".
- Devices: phone/Chromebook/Quest are `ip:port` serials: use `target.sh phone|chromebook` (reference "Phone").
  WSA `127.0.0.1:58526`; **USB serial = the user's SHIELD TV** (rooted; installs only). No printer tests (user away).
- **Cheapest interface first** (MCP `mcpao.sh` ~150 tok > `phone-ui.py layout` cut by regex > cropped shot only for GL looks).
- User sessions backed up: phone `/data/local/tmp/phone-session-backup.{3mf,json}` (8 plates), WSA `/data/local/tmp/session-backup.{3mf,json}`.
  Test zip `scadzip-test.zip` stays in `/sdcard/Download` on phone + Chromebook. LAN access code was given in chat: never write it anywhere.
## Next task
- **User 2026-10-21: one small step per session.** Cue `/clear` + `/next` after EACH step. Pie menu disliked -> backlog.
- **User 2026-10-21: design deviations pre-approved** ("deviate if you find a more logical, intuitive way; I trust your judgement"); say what changed.
- Paint plan (user ACCEPTED 2026-10-21): steps (1)-(2) done, user feel untested (backlog "Paint panel UI", "Paint penetration").
  **Next: step (3) Colour change + waste counter**: post-slice half done (Muse Task 11, 1740dcf, untested on device; counts
  prime tower only, NOT the A1's flush-to-chute waste: add flush volumes). Claude's half = the pre-slice estimate in the
  paint shop (`core/ColourChangeEstimate.kt` needs per-layer filament ids from the painted mesh, native side; flush volumes
  from the profile).
- Muse replaces Grok (2026-10-22): `MUSE-TASKS.md` on branch `muse/work`, one draft PR per task into muse/work; Claude
  verifies, merges, cherry-picks code (not MUSE-TASKS.md) onto the work branch. Muse never merges. Tasks 1-11 done (Task 1
  finding 4: fix the patch comment when regenerating 0001). **Comms (user-approved 2026-10-22): GitHub is the mailbox, no relaying.** Tasks in MUSE-TASKS.md on
  muse/work, then comment `TASK <n> READY` on issue #46; Muse replies via PR + log entry, pings #46 (`PR #<n> UPDATED`).
  **Every session start: check open `muse/*` PRs and #46.** Review on the PR. LIVE 2026-10-22 (PR #51, 8e0430b; `TASK 13 READY` on #46:
  loopback test, before Task 12). Self-merge works when the command STARTS with `gh pr merge` (no `cd &&` prefix). Muse role verdict waits on Task 13 (compiles -> Kotlin tasks OK again; else
  audits/research/pure tested logic only). No stacking.
- C: tight; old build dirs -> `D:\orca-builds-archive`.
- Open (backlog): app 3MF fails in desktop Orca (fix untested), printer/user-only checks, pen input, plate switcher pick.

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
