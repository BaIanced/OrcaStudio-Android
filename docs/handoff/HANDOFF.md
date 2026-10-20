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
- Android main = b98e6ad (PR #33, r335, merged 2026-10-20). Untested in main: pen 7d3a263, fan hold 298ee9f, Extrude F150 e13bebb,
  Quest stick zoom (r324); paint card fix r336 verified, unmerged; still broken: layer lines at framing zoom (below).
- Installed: r336 (run 39379527770, paint card fix) on all devices 2026-10-20 (phone, Chromebook, SHIELD, Quest, WSA).
- **Active device = `/target`** (p/w/c|t, default WSA): `sh ~/orca-android-tools/target.sh serial`; shot.sh/mcpao.sh follow it.
- Devices: phone/Chromebook/Quest are `ip:port` serials: use `target.sh phone|chromebook` (reference "Phone").
  WSA `127.0.0.1:58526`; **USB serial = the user's SHIELD TV** (rooted; installs only).
- User away from the printer: no printer tests. SHIELD installs only. **User 2026-10-19 (later): keep using WSA, not the phone;
  cheapest interface first** (MCP `mcpao.sh` ~150 tok > `phone-ui.py layout` cut by regex > cropped shot only for GL looks).
- User sessions backed up: phone `/data/local/tmp/phone-session-backup.{3mf,json}` (8 plates), WSA `/data/local/tmp/session-backup.{3mf,json}`.
  Test zip `scadzip-test.zip` stays in `/sdcard/Download` on phone + Chromebook. LAN access code was given in chat: never write it anywhere.
## Next task
- Fix next:
  (1) preview layer lines (backlog "Moire"). WIP 6c1002e (WSA debug only, no CI): band fix (PATH_FS wave level = beadMean,
  wall sides join the far mean only as slivers), `Supersampler.kt` 2x + tent downsample when still, cached still frame,
  Settings Off/When still/Always (MCP `view_set {"supersample":..}`), regimes by view px (`uPxScale`). Lying cylinder
  (object 5, yaw -60 pitch 25) keeps faint arcs. **Next (user-approved 2026-10-20, Gemini-reviewed): replace the 2x
  buffer with temporal accumulation when still**: per frame a sub-pixel projection jitter, tent-distributed over +-1 px
  (sum of two Halton uniforms), drawn into a 1x RGBA8+depth FBO, mixed 1/n into an RGBA16F buffer (check
  EXT_color_buffer_half_float / ES 3.2 at start, else fall back to the 2x path), 32 frames; any key change restarts.
  Lower the PATH_FS fade thresholds while accumulating (tune on the cylinder). Moving: 4x MSAA on the default
  framebuffer via a custom EGLConfigChooser, falling back to no MSAA. Then CI build + user check.
- WSA shader loop: `wsa-cycle.sh` / `wsa-preview2.sh`, black shots = display asleep (reference "Build and tools").
- Need the printer (backlog): fan slider jumps (r295 hold), Retract/Extrude F150 (e13bebb), extrude/jog, speed chips.
- User-only checks pending (backlog): bead look, part shadows, light sliders, r276 tool row, r283/r284 top bar, r280 pinch lock,
  r316 double tap, r305 resize gating.
- Pen input (super low priority): backlog. Waiting on the user: plate switcher pick.

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
