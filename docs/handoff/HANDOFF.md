# Handoff: OrcaStudio-Android + OrcaStudio aarch64 Flatpak (hot file, 2026-10-13)

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
- Android main = 62ada99 (PR #73 = r521, multi-plate Preview, merged 2026-11-04; Claude-verified only; PR #72 = r519 before it). Nothing unverified on the branch; no merge offer pending. User-untested in main: list in `NOTES-merge-batch.md`.
- Signing secrets: `release` environment (main + `claude/*` only).
- Installs: all adb devices (skip .23); **SHIELD avoided (user 2026-11-01)**. WSA restart: `WsaClient /shutdown`, `wsa-start.ps1`, `adb connect 127.0.0.1:58526`; phone/Chromebook reconnect: memory device-ips. GL shots on the phone VD.
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
- Muse (tasks 1-18 merged): `NOTES-muse.md`, only when sending Muse work.
- **Merge batch (user 2026-10-28: main = user-tested): `NOTES-merge-batch.md`** (cuts agreed, printer set release-gated, sessions A-D).
- Local tests: `wsa-debug-rebuild.sh [native]` (reference "WSA debug loop"); fresh app: `wsa-debug-setup.sh` first. Lab prefs: edit `layout_lab` JSON in debug `shared_prefs/app_settings.xml` with run-as sed (app stopped).
- **Offload (user 2026-10-25/26): max work onto free opencode models** (DeepSeek V4.1 Flash, `octauto.sh`, `D:\oct-backup\jobs\README.md`, ledger `trust-profile.md`).
- WSA VD: reference "Headless display" (Kotlin-only: assembleDebug + `wsa-debug-setup.sh` + `orca-vd.sh start`; uninstall debug after).
- VD taps: archive "VD tap tip". No MCP undo: VD tap 27,78. WSA: `svc power stayon true` while testing.
- Open tests in backlog.
- Name inquiry #17269: wait 1-2 weeks from 2026-11-05, then one bump. Upstream-PR idea (patches 0002-0004 vs 2.5.0 alpha): no go yet.
- Two-Claude chat (user 2026-11-06): other account's VR-port agent talks via `claudechat.md` (sections 1-7; SendMessage can't cross accounts). Start a Monitor on its `## ` count (command in section 7), timeout 30 min, re-arm. Quest profile numbers land in `NOTES-quest-profile.md`.
- r537 on phone + WSA; WSA VD has no GL shots: use the phone VD. Quest profile blocked on a wearer (claudechat.md s8).
- A/B recipe: archive "ab-crease.sh". Battery audit 2026-11-06: idle redrew at 165 fps (`orca-android-tools/batt-orbit.sh`).
- mobilecad review 2026-11-06 (archive end): same WHEN_DIRTY design; XR ideas for the VR-port agent (backlog [idea]).
- Render-on-demand 84ffb8e (run 42335308493) on phone/WSA/Quest. WSA VD part 1 PASS (archive end): idle 0 fps, orbit, view presets, screenshot, transform, slice preview draw and settle. Preview tab isn't counted by `perf` "3D view".
- Remaining test plan: fling coast, pinch (phone VD, `su input touchscreen`), plate switch, selection, loupe, build drags, STILL supersample refine finishes (vdgrab before/after); then idle-vs-orbit battery A/B (`batt-orbit.sh`, phone); then GL state/upload/draw-call audit in `render/`.
- Next step: Render-on-demand test part 2 (fling/pinch/plate switch/selection/refine) on WSA+phone VD -> Opus 5.5 (medium)
- VD keyboard/tap fix (orca-vd.sh, archive end) awaits the user's verdict (keyboard no longer vanishes?).
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
