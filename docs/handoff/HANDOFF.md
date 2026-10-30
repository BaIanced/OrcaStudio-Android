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
- Android main = 3defffa (PR #66, branch at r461/89944b9, merged 2026-10-28 on the user's go-ahead, before sessions B-D; user overrode the user-tested-only rule for this merge). Older: 0170950 (PR #65, r398). User-untested in main: list in `NOTES-merge-batch.md`.
- Signing secrets: `release` environment (main + `claude/*` only).
- Latest CI build: r470 (run 40988995980, hole ghost alpha 0.25) on all 5 adb devices, 2026-10-29. User feel untested. Install builds on all adb devices (skip .23).
- **src-orca stays unmodified: native changes ship as `android/patches/src-orca/*.patch`, applied by a CI step (Patch OrcaSlicer); no local native build.**
- **User 2026-10-28: no more user testing for now; Claude builds and tests everything itself (memory no-user-testing-now); don't ask for feel/UI checks.**
- **User 2026-10-29: no tests on the user's screen; all tests on a hidden virtual display** (`TARGET=wsa orca-vd.sh start`, memory tests-on-virtual-display).
- **Test device = WSA.** Note = S Pen tests only (locks; user says when awake); Quest = pseudo pen. Reference "Note".
- Devices: phone/Chromebook/Quest are `ip:port` serials: use `target.sh phone|chromebook` (reference "Phone").
  WSA `127.0.0.1:58526`; **USB serial = the user's SHIELD TV** (rooted; installs only). No printer tests (user away).
- **Cheapest per finished step, quality kept** (memory cheapest-per-finished-step): mcpao.sh for app state, adb for installs/taps, `phone-ui.py layout` for UI, cropped shots only for GL looks.
- Session backups: phone `/data/local/tmp/phone-session-backup.{3mf,json}`, WSA `/data/local/tmp/session-backup.{3mf,json}`. `scadzip-test.zip` stays in `/sdcard/Download` (phone, Chromebook). Never write the LAN access code anywhere.
## Next task
- **User 2026-10-21: design deviations pre-approved** ("deviate if you find a more logical, intuitive way; I trust your judgement"); say what changed.
- Muse (full discretion; tasks 1-18 merged): `NOTES-muse.md`, read only when sending Muse work or a Muse PR shows up.
- **Merge batch (user 2026-10-28: main = user-tested): `NOTES-merge-batch.md`** (cuts agreed, printer set release-gated, sessions A-D).
- Local tests: `wsa-debug-rebuild.sh [native]` (reference "WSA debug loop"); fresh app: `wsa-debug-setup.sh` first. Lab prefs: edit `layout_lab` JSON in debug `shared_prefs/app_settings.xml` with run-as sed (app stopped).
- **Offload (user 2026-10-25/26): max token use onto free opencode models, growing leeway by track record** (Claude specs + verifies).
  DeepSeek V4.1 Flash = default; runner `octauto.sh` (`D:\oct-backup\jobs\README.md`; opencode v2: `model#variant`), ledger `trust-profile.md`.
- CAD plan (`NOTES-cad-reuse.md`): 0-5b Claude-verified, 1b parked. MCP efficiency (`NOTES-mcp-token-efficiency.md`): build rule waits for user OK.
- Gate: user sessions B-D paused 2026-10-28; release gated on the printer set.
- WSA: `svc power stayon true` while testing; `stage-from-apk.sh <apk>`.
- WSA VD (reference "Headless display"): Kotlin-only changes = assembleDebug + `wsa-debug-setup.sh` (installs, no launch) + `TARGET=wsa PKG=...debug orca-vd.sh start`; shots via MCP `view_screenshot`; uninstall debug after. `wsa-full.ps1` only when the user asks to watch.
- Build continuity 1-3 done (one Design entry + CAD badge, Edit in builder, ghosts, paint kept on open: code check 2026-10-29; archive). Hole ghost alpha r470 verified.
- Repeated-group test fixture: `~/orca-android-tools/rep-groups.json` via MCP `design_set` (archive 2026-10-29 "repeated groups").
  WSA tap helpers: `~/orca-android-tools/tx.sh <label>` fails on Compose nodes with 0 bounds; use `phone-ui.py layout` with `. ./.vd-wsa.state; export VD=$LOGICAL,$SFID` + `orca-vd.sh tap`. Package id `io.github.baianced.orcastudio_android.debug`.
- Repeated group turn ring + body drag verified 2026-10-29 (archive). Claude-only leftovers: smart-guides Build middles built (uncommitted, WSA VD check open); CAD step 7 asset library (not started, `NOTES-cad-reuse.md`).
- Next step: commit + push the Build smart-guide middles, WSA VD check (drag a shape toward another one's middle), then CAD step 7 asset library -> Sonnet 5.5 (medium)
## User decisions (2026-10-13)
- Modeling goal: model without writing code (OpenSCAD only a backstop).
- (2026-10-14) Each newly imported file gets its own plate (active if empty, else first empty, else new; `FileController.useEmptyPlate`).
- Plate activation must not move the camera when zoomed out (sometimes moving is fine).
- Plate switcher: **keep the lab until the user says otherwise; don't ask again** (mockups: archive "Plate switcher"). Once picked: delete losers + lab.

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
