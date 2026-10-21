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
- Android main = f1c4a17 (PR #34, r341, merged 2026-10-20). Untested in main: pen 7d3a263, fan hold 298ee9f, Extrude F150 e13bebb,
  Quest stick zoom (r324). Nothing unmerged on the branch.
- Installed: run 39517474174 (paint batch 2, 2026-10-20; was r343 run 39490672782) on all devices 2026-10-20. Tablet (wireless adb, unrooted; user's): only the debug app + a copy of the phone's data (CI release app removed at the user's request); no root = no data copy into a release app.
- **Test device = WSA** (user 2026-10-20: `/target` skill removed for good). `target.sh` stays only as the serial helper (`target.sh serial|phone|chromebook`); shot.sh/mcpao.sh use it.
- Devices: phone/Chromebook/Quest are `ip:port` serials: use `target.sh phone|chromebook` (reference "Phone").
  WSA `127.0.0.1:58526`; **USB serial = the user's SHIELD TV** (rooted; installs only).
- User away from the printer: no printer tests. SHIELD installs only. **User 2026-10-19 (later): keep using WSA, not the phone;
  cheapest interface first** (MCP `mcpao.sh` ~150 tok > `phone-ui.py layout` cut by regex > cropped shot only for GL looks).
- User sessions backed up: phone `/data/local/tmp/phone-session-backup.{3mf,json}` (8 plates), WSA `/data/local/tmp/session-backup.{3mf,json}`.
  Test zip `scadzip-test.zip` stays in `/sdcard/Download` on phone + Chromebook. LAN access code was given in chat: never write it anywhere.
## Next task
- **User 2026-10-21: one small step per session (last round went over 250k).** Cue `/clear` + `/next` after EACH step:
  1. View cube DONE (WSA OK, CI pending; backlog). Next: 2. Rotate gizmo rings + drag + angle label. 3. Rotate tick snap +
     touch tuning. 4. Z rail for shapes in the CAD section (backlog "Move in Z"). 5. print3d_toolbox checks (thin wall/
     intersections). 6. Pie menu / tool-bar collapse (discuss first).
- **Paint tools** (survey `NOTES-paint-survey.md`): 4 picks done, WSA OK (Claude), run 39557578074 installed all devices
  2026-10-21; user feel pending (all paint batches).
- Layer-line repro tools `abshot.sh` / `abtop.sh` (reference).
  C: tight; old build dirs move to `D:\orca-builds-archive`.
- First print: fixed by printer calibration (user, 2026-10-20; archive). **Open issue: app 3MF export fails in desktop Orca** (fix `match_filament_indices` in run 39529670166, installed on all devices; untested on a 4-filament export: the user exports from the phone and opens it in desktop Orca).
- Need the printer, and user-only checks (bead look, shadows, light sliders, r276/r283/r284/r280/r316/r305): backlog.
- Pen input (super low priority) + plate switcher pick: backlog.

## User decisions (2026-10-13)
- (2026-10-14) Each newly imported file gets its own plate (active if empty, else first empty, else new; `FileController.useEmptyPlate`).
- Plate activation must not move the camera when zoomed out (sometimes moving is fine).
- Plate switcher: **keep the lab until the user says otherwise; don't ask again** (mockups https://claude.ai/artifact/E3MtY8rQ1dMGHhibY2oNCX).
  Once picked: delete the losers and the lab (keep the wide panel tuck/width).
- Modeling goal: model **without writing code**; OpenSCAD code is only a backstop. Roadmap 1-3 done (archive).

## Safety gotchas
- **Any test print needs the user's OK.** User's AMS lite: A1 yellow, A2 matte black, A3 white, A4 PETG.
- 3D Filament Profiles (3dfilamentprofiles.com) is built in (Models tab site + "Log print"): **never press Subtract** in tests.
- **User 2026-10-15: all build plates/objects on the devices are currently throwaway test objects with no real-life significance (until the user says otherwise). Standing permission to move, reassign, delete or rearrange anything; no need to avoid or restore them.** Still verify the selection before deleting by list position. (Prints still need the user's OK.)
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
