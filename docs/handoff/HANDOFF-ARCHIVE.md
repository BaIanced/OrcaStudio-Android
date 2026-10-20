# Handoff archive (frozen 2026-10-10)

Full history up to 2026-10-10, moved out of HANDOFF.md so sessions don't load it.
Reference only: read it when a question needs old build results, past causes or decisions.
Facts here may be stale; HANDOFF.md wins where they differ.

---

# Handoff: OrcaStudio aarch64 Flatpak + OrcaStudio-Android (state as of 2026-10-10 10:30 UTC)

Read this first, then `arm-build/README.md` and the `NOTES-*.md` files next to it. No title for the user
(the old one was a joke).

## Start here: open items, in order

**Two streams (user, 2026-10-10):** implement on a feature branch while the test branch builds;
merge the feature branch into the test branch once both build clean and pass on WSA.
- `claude/orca-cloud-login` was merged into `claude/handoff-continuation-rseql3` (`842977d`,
  run 38044939151 = r51, installed on WSA). **The user verified Orca Cloud on r51:** Google sign-in
  (browser round trip works on WSA), "Subscribed plugins" lists their 4 plugins, still signed in
  after an app restart. Their account has no password (Google only); GitHub not tried.
- Test branch: back fix (run 38067755767): Back on another tab returns to Prepare, on Prepare it
  drops tool/selection like Esc; a **mouse back button** with nothing left to go back to is eaten
  (before, Android put the app in the background, "acts like minimise" - user report).
- Feature branch `claude/orca-cloud-sync` (run 38067880608): More > "Sync presets from Orca
  Cloud" = full `GET /api/v1/sync/pull` -> engine `loadCloudPresets` (same path as the Bambu
  sync). Pull only; cannot delete presets (`remove_users_preset` only touches presets whose
  user_id equals the engine's empty `preset_folder`). Push (`/sync/push`, OCC with
  `original_updated_time`, setting_id = UUIDv5(ns f47ac10b-..., "<user_id>/<name>")) writes to
  the user's cloud: **ask before enabling it.**
- Test branch, later on 2026-10-10: Models pinch + Ctrl+wheel zoom; MakerWorld "Full site" chip
  (no BBL-Slicer UA: header/sidebar/Collections, but no slicer import), and in slicer mode a native
  links row (search, following, MakerLab, contests, history, collections, profile; the handle is
  `name` from `/api/v1/user-service/my/profile`, fetched by the page) plus MW_TRIM_JS (the fixed
  `.global_new` category bar sits at top:60px with no header; moved to 0). Model pages in slicer
  mode have no blank strip, so nothing may be overlaid there. WebViews follow the app theme
  (`themedWebView`: ContextThemeWrapper isLightTheme + algorithmic darkening, API 33+); a theme
  change recreates the Models browser. Run 38070361403 builds all of it.
- Sync branch: preset upload (user approved 2026-10-10): engine `cloudUploads(userId)` /
  `markUploaded`, More > "Upload presets to Orca Cloud". Creates use UUIDv5 ids, updates send
  original_updated_time; 409 = reported, never forced; 413 = will_not_sync; no deletes; presets
  with another account's user_id (Bambu) skipped. Run 38070574697.
- **Orca plugins, research (2026-10-10):** upstream `src/slic3r/plugin` is ~14k lines: CPython
  3.12 embedded through pybind11 (`PythonInterpreter.cpp`), capability types PrinterConnection,
  Pages, Analysis, Importer, Exporter, Visualization, Script, SlicingPipeline. SlicingPipeline
  hooks into libslic3r (`Print::set_slicing_pipeline_hook_fn`, so the engine already has the seam);
  host APIs in `plugin/host/*` (geometry, model, presets, slicing are GUI-free; ui/app/pages use wx).
  wx is also in PluginManager/PluginConfig/PluginResolver/PluginAuditManager/PythonInterpreter.
  Android plan: CPython for Android (python.org ships Android builds from 3.13; plugins targeting
  3.12 should mostly run on 3.13) linked into the engine via the NDK + pybind11, a wx-free port of
  loader/manager, Script + SlicingPipeline first, cloud install via `/api/v1/plugins/...`.
  Native-code wheels in plugins cannot run on Android; pure-Python only.
- **The user skipped OpenSCAD for now (2026-10-10).** Order: Orca Cloud login (done, verified),
  preset sync (pull in progress), then Orca plugins.
**Close the app when done** (`adb -s 127.0.0.1:58526 shell am force-stop io.github.baianced.orcastudio_android`):
the test scripts keep its window in front of the user's desktop. Python on this PC is `python`, not `python3`.

**WebView debugging (use it, it found every Models bug):** `adb shell setprop log.tag.OrcaWeb DEBUG`,
restart the app, then `adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof
io.github.baianced.orcastudio_android)`. Helpers (session scratchpad, recreate if gone): `cdp.mjs`
(node, Runtime.evaluate with awaitPromise) and `cdplog.mjs` (console, exceptions, failed requests,
`Page.frameRequestedNavigation` for custom-scheme links). Applies to the Models WebView only (the
switch is read when ModelBrowser creates it).

**Build 44-50 results (WSA, 2026-10-10):**
- Verified on 44: selection banner buttons stay put; VLH panel at the right edge above Slice; undo
  after starting a temperature tower ends the calibration and restores plate and project name;
  pickers list user presets first, vendor presets nested.
- Models fixes, all verified on 49/50 (run 38043169042 = r50):
  - MakerWorld got the phone site (Handy prompt) -> desktop Chrome UA + BBL-Slicer suffix.
  - Home cards only add `?modelid=` (a pop-up that never shows) -> `doUpdateVisitedHistory` loads
    `<lang>/models/<id>` like the desktop (`WebViewPanel::get_model_mall_detail_url`).
  - "Open in Bambu Studio" -> Import fires `bambustudio://open?file=<urlencoded url incl. %26name%3D>`
    (anchorClick). The download landed in cache/models and `importFile` copied it onto itself (0
    bytes). Downloads now go to cache/downloads; `importFile` skips a self-copy. Verified: a
    250x240 pegboard 3MF imported.
  - AndroidView's default WRAP_CONTENT made WebView lay out with height 0 (CSS `100vh` = 0):
    Printables' CookieYes dialog had max-height 0, so only its backdrop showed and blocked taps.
    All WebViews get MATCH_PARENT layout params.
  - Printables "Download" saved `x.bin` (WebView guesses from octet-stream) -> the first name with a
    model / zip extension, else sniffed from content. Verified: an STL imported.
  - File names keep non-Latin letters (`\p{L}`).
- Orca Cloud build (r46) on WSA: the sign-in page loads, the `wx` bridge handshake works (a fake
  loopback callback failed with "state mismatch", not "expired"), the loopback listens on
  127.0.0.1 and ::1:41172. **On WSA, 127.0.0.1 refuses connections from the shell; ::1 works**
  (untested whether the browser's localhost reaches it). The real sign-in needs the user.
- Tooling: the system file picker's Back goes up folders; close it with
  `adb shell am force-stop com.android.documentsui` (cancels, nothing saved). Kotlin string
  regexes need `\d` (written via python heredocs, `\d` lost one backslash twice: compile errors).

**Orca Cloud sign-in (branch `claude/orca-cloud-login`, `5cd80e6`):**
- Port of upstream `OrcaCloudServiceAgent` (src-orca `src/slic3r/Utils/OrcaCloudServiceAgent.cpp`)
  and `WebUserLoginDialog.cpp` / `HttpServer::auth_handle_request`, in Kotlin: `net/OrcaCloud.kt`,
  `net/OrcaCloudLoopback.kt`, `ui/settings/OrcaCloudSection.kt` (More > Orca Cloud).
- Page `https://cloud.orcaslicer.com/orcaslicer-login`: it detects the slicer by `window.wx` /
  `webkit.messageHandlers.wx` (checked in its JS bundle), so the bridge is registered as `wx`.
  `get_login_cmd` -> reply `window.postMessage(login_config)` (PKCE S256, state, redirect
  `http://localhost:<41172..41174>/callback`, apikey). E-mail sign-in: `user_login` with flat
  tokens. Google / GitHub: `thirdparty_login` opens the browser; GoTrue redirects to the loopback
  with `code` + `orca_state`; exchange `POST auth.orcaslicer.com/auth/v1/token?grant_type=pkce`
  `{auth_code, code_verifier}` with the `apikey` header.
- Refresh `grant_type=refresh_token`, 15 min skew; 400/401/403 signs out, other failures keep the
  session. Stored: refresh token + user, AES-GCM with an AndroidKeyStore key in
  `noBackupFilesDir/orca_cloud/session.sec` (not in the encrypted backup yet).
- API `https://api.orcaslicer.com` (upstream's default has no scheme; http 301s, https works).
  "Subscribed plugins" = `GET /api/v1/plugins/subscriptions` (Bearer + apikey).
- To test: e-mail sign-in, Google sign-in round trip (WSA may pause the app while the browser is
  in front), app restart keeps the sign-in, sign out, plugin list.
- Next: preset sync (`/api/v1/sync/pull|push`), then the plugin runtime (embedded Python).

0. **Handoff to the local session (2026-10-09, user's request: cloud usage).** The cloud session
   `session_01Dycp4Bu9GrptBgymXRxiss` has stopped. The user's local Windows session
   (`session_01Dyz5iJaQCQ1WuH3ktn4KU9`, "orcastudio-d5", gh logged in, root adb on WSA) is now the
   **only writer**: it commits/pushes `claude/handoff-continuation-rseql3` in both repos, dispatches
   `android-apk.yml` with `gh workflow run android-apk.yml --ref claude/handoff-continuation-rseql3 -R
   BaIanced/OrcaStudio-Android`, and keeps this file current. Run the privacy scan before every push
   (rule 7).
   - The user approved (2026-10-09) pushes and `android-apk.yml` dispatches on this branch without
     asking each time. A merge to main still needs their go-ahead.
   - **Builds 19-22 (local session, 2026-10-09):**
     - Build 18 verified on WSA: cloud MQTT connects (status and temperatures arrive), and cloud
       filament sync reads all 4 slots with the right colours.
     - `2fd9f15` (build 20, verified): Bambu spools fell back to Generic PLA because upstream
       re-keyed the Bambu bundle's `filament_id` (ec207e67a, e.g. `OFoiVqVM`), while printers still
       report `GFA00`. The sync translates a reported `GF..` id through `setting_id` (`GFSA00_04`).
       WSA now syncs Bambu PLA Basic / Matte / Matte / iSanMate PETG HF.
     - `b361bb6` (build 20, verified): the Filament brands filter lists `filament_vendor` brands,
       with search and show/hide all.
     - `913870c` (build 21): filament picker with user presets first, then collapsible
       brand > material sections.
     - `c9914d0` (build 22): dialog number fields commit while typing (a typed shape size was lost
       on Add); Slice on an empty plate shows a message.
     - The user gave standing permission for an autonomous test-and-fix loop over every button and
       setting on WSA. Never print, sign out or delete presets without asking.
     - **Build 25** (`55df52c`, verified on WSA):
       - Fixes: the tool column stays below the top bar; a stopped print shows "Stopped", not
         "Error"; More > Compare presets opens the picker; Choose printers opens on a vendor with
         chosen printers; About shows the CI build ("0.1.2-r25").
       - Device panel for Bambu: job, temperatures, AMS slots, messages.
       - Desktop mouse controls: right/middle-drag pans, right click opens a context menu.
       - Long press opens the same context menu: object actions on an object, add / import /
         arrange on the empty bed.
     - **Build 28** (`a00c119`, verified on WSA). Adds:
       - the preset editor's "Only changed" filter;
       - a Flushing volumes matrix dialog (the button used to silently recalculate the matrix);
       - the view follows a newly added plate;
       - X/Y/Z units in the row label;
       - **crash fix:** painting crashed with SIGBUS in glBufferData. `FloatData` memory-mapped
         `paint_mesh.bin`, which the engine rewrites on every stroke; files are now read, not
         mapped.
     - **Build 31** (`b9eb502`, verified on WSA):
       - mesh and paint files are written to .tmp and renamed (atomic);
       - Prepare shows the prime tower on plates that use more than one filament (type 99 box,
         clamped to the bed);
       - **fix:** reloading a project (the autosave) gave filament slots 2+ false overrides,
         because `diff_options` compared slot i with preset variant i (e.g.
         `filament_dev_ams_drying_ams_limitations`). It now uses the preset's first entry.
     - **Build 32** (`288f71a`, verified on WSA 2026-10-09): first release-signed build; installed
       with `reinstall-with-data.ps1` (data kept, cloud status works). Share G-code uses the Save
       name. Also verified: save G-code, save .gcode.3mf (md5 matches), lay on face, cut, check for
       profile updates. Cut drops colour paint, like desktop with `keep_painting` off (default).
     - **Build 33** (`94f14e1`, run 37952962925, verified on WSA): the .gcode.3mf's slice_info had
       empty prediction / weight / printer_model_id and no `<filament>` entries. slice() now keeps
       the stats per G-code; the export adds them, mapping filament ids to Bambu's via
       `resources/printers/bambu_filament_ids.json`. WSA export: prediction 6456, weight 18.19,
       `printer_model_id` N2S, filaments `GFA00` / `GFA01` with colours and use. New androidTest
       `f02b` (CI doesn't run androidTests).
       - Also verified on build 32/33: plate add / delete, Add > Text / SVG / Sample models
         (a non-SVG file gives "no closed shapes"), object settings editor + undo, pauses & colour
         changes dialog, temperature tower (G-code steps 230 to 190 °C, 50 layers each).
     - **Build 34** (`9664bab`, cancelled; included in 35): the 3D view drew every part orange;
       parts now take their filament's colour (volume extruder, else object, else 1), like the
       thumbnails (`filament_of` moved to Thumbnails.hpp; mesh type 100 + filament).
     - **Build 35** (`a8afcb7`, run 37960398849, verified on WSA): **multi-select**, asked for by the
       user ("five cubes, duplicate two at once").
       - Ctrl/Shift+click adds a copy. On touch, "Select multiple" (tool column, long-press menu,
         or long press on an Objects row) turns on select mode: taps add / remove, a banner shows
         the count, Select all (active plate) and Done.
       - With 2+ copies: Duplicate, Delete, drag and the filament picker (Objects tab card) act on
         all of them, each one undo step. Engine: `duplicate` takes `items` [[obj, inst]],
         `deleteItems`, `moveItems`, `setObjectSetting` with `objects`. androidTest `c05`.
       - The scene and paint meshes tag copies as obj * 4096 + instance (`INSTANCE_ID_STRIDE`,
         OrcaEngine.hpp / PlateRenderer.kt); the shader highlights up to 64 selected copies.
       - Verified: five cubes, select two, duplicate (both 2x), filament 2 on both, drag both,
         long-press menu shows only Duplicate / Delete, delete two copies, one undo restores them,
         Select all = 7. Filament colours verified (yellow cube).
     - **Build 36** (`caa4734`, run 37964003695, verified on WSA): select mode keeps Objects rows
       compact (the first pick expanded its details and moved the rows under the next tap); very
       dark filaments get a minimum shade (black was a flat silhouette).
     - Fixed in build 43: the selection banner's buttons moved when its text changed length.
     - **Merged to main (2026-10-09, user's go-ahead):** PR #3 (builds 15-36); main build
       38003237247 published pre-release `android-v0.1.2-r37`.
     - **Builds 37-39: desktop mouse / keyboard controls** (user's request: "mimic Bambu Studio /
       Orca desktop", defaults from `src-orca/src/slic3r/GUI/Shortcuts.cpp`).
       - Mouse: Shift+drag rectangle select (adds), Alt+drag removes, Alt+click selects a part,
         Ctrl+wheel brush size while painting. Zoom stays centred (desktop `zoom_to_mouse` = false).
       - Ctrl keys, only while the 3D view has focus (it takes focus on touch): A / Shift+A select
         all (plate / all plates), C / V copy-paste (paste = duplicate), K clone, D delete all,
         0-7 camera views (iso, top, bottom, front, rear, left, right, plate). Ctrl+Z/Y/R/N global.
       - Single keys: Tab Prepare/Preview, I/O zoom, A / Shift+A arrange all / plate, Q orient,
         F lay on face, C cut, L/P/H/N paint, U measure, arrows 10 mm (Shift 1 mm), PgUp/PgDn
         rotate 45 deg, +/- copies, 1-9 filament; Preview: arrows layer / moves, Home/End.
       - Behaviour changed to match the desktop: Ctrl+A was arrange, Ctrl+D duplicate, Ctrl+1-3
         the tabs, O orient.
       - Build 37 failed to compile (`newProject` joined `deleteAll`); 38 fixed it. On 38, Ctrl
         shortcuts verified on WSA (Ctrl+3 front, Ctrl+A, Ctrl+K, Ctrl+C/V, Ctrl+D + Ctrl+Z), but
         arrows / Tab went to Compose's focus navigation (scrolled the side panel). Build 39
         (run 38005883012, verified on WSA): PlateView forwards its keys to the handler first. Arrows (10 mm), PgUp (45 deg), 2 (filament), Tab, Preview Down arrow all work; four Ctrl+Z restore.
       - Not testable over adb: Shift/Alt mouse drags (adb input has no modifier state).
     - **Build 40 (user report 2026-10-09):**
       - Mouse wheel orbited instead of zooming on WSA. Cause: WSA turns the mouse into emulated
         touches (wheel = swipe, Shift = horizontal) unless the app declares
         `android.hardware.type.pc` (Amazon WSA compatibility guide). Manifest now declares it, so the
         existing wheel zoom / middle-drag pan / right-click code gets real mouse events. Reproduce
         with a real Windows wheel event: `orca-android-tools/wheel.ps1` (-Notches, -Middle -DragX) (SetForegroundWindow needs the Alt
         key trick); adb `input` cannot.
       - A user printer preset (inherits "Bambu Lab A1 0.4 nozzle") lost the connection, the loaded
         filaments and the sync button: they were keyed by preset name. Now kept under the base
         system preset (`AppSettings.setPrinterBase`, engine printer list has `base` and the
         resolved vendor).
       - Settings showed per-variant vectors raw ("190,190,190,190,190"): equal entries are now
         shown / edited as one value. Not reproduced with the A1 presets on WSA (all single values);
         H2D-style user presets have 2-7 entries.
       - Bambu mode: for a Bambu printer the editor hides the desktop's non-BBL options plus
         Klipper / Marlin / pellet / toolchanger / print-host options (`option_states`); the
         connection dialog offers only the Bambu types.
       - Encrypted backup (More > Backup): settings, connections, user presets, obn credentials
         (login, slicer cert / key, printer certs); PBKDF2-SHA256 310k + AES-256-GCM, passphrase
         min 8. Restore reinstalls the printers' vendors and restarts the app.
     - **Build 40 verified on WSA (run 38017581298, APK r41, 2026-10-10):** the wheel zooms (no
       orbit), left drag orbits, a right click opens the context menu. Right / middle **drag** did
       nothing: the logcat shows `InputDispatcher: Asynchronous input event injection failed` twice
       per drag, so WSA's events for a held right / middle button don't arrive as touch moves.
       - **Build 41** (`2bb7e71`, run 38034948519, verified on WSA): the `OrcaInput` log confirmed
         WSA sends ACTION_DOWN + BUTTON_PRESS, then `ACTION_HOVER_MOVE buttons=4` (2 = right) for
         the drag. PlateView now pans on those hover moves; middle and right drag pan, a right drag
         opens no menu. Mouse event log: `adb shell setprop log.tag.OrcaInput DEBUG`.
     - **Build 43** (`13b183d`) failed to compile (ModelsScreen: `vm.toast` missing, progress setter);
       **build 44** (`264cc54`, run 38037031544) fixes it and is what to install. Contents, all untested:
       - fixes: the selection banner fills the width (buttons stay put); Undo back before a
         calibration started ends the calibration and restores the project name (engine
         `m_calib_undo_depth`, scene `calibration_active`); on wide layouts the VLH panel sits at
         the right edge above Slice.
       - preset pickers (user's request): user presets first in one open group, then one
         collapsed "Vendor presets" group (vendor; filaments brand > material). `PickerDialog`
         nests to any depth (`PickerItem.detail`), `presetItems()` in PreparePanel.kt.
       - **Models tab** (wishlist item 1): `ui/models/ModelsScreen.kt` (`ModelBrowser`, kept by
         AppScaffold across tab switches) + `net/ModelDownloads.kt`. MakerWorld loads through
         `makerworld.com/api/sign-in/ticket?to=...&ticket=` with obn `request_bind_ticket`
         (`abi_bind.cpp:110`, new JNI `ObnNative.webTicket`, **19 exports**). Handles
         `bambustudio://open?file=<url>&name=` links (Downloader.cpp / `import_model_id`),
         `makerworld_model_open`, MakerLab base64 messages, plain downloads and zips (Printables,
         Thingiverse chips). Files go through `FileController.openDownloaded` -> `openModels`
         (project 3MF on an empty plate opens as project, else adds the geometry).
       - To test: MakerWorld signed in, "Open in Bambu Studio" on a model, a Printables download,
         Back button in the page, tab switch keeps the page.
       - Test helper: `orca-android-tools/drag.ps1 -Middle -Down <flag> -Up <flag> -DragX 150`
         (`wheel.ps1` with selectable mouse_event flags: 2/4 left, 8/16 right, 0x20/0x40 middle).
     - **Wishlist (user, 2026-10-09; not started):**
       - Model sites in their own tab, "the more the merrier", MakerWorld as the proof of concept
         (then Printables, Thingiverse, ...): browse in-app, download straight into the plate.
       - Parametric 3D modelling inside the app: embed OpenSCAD (user's idea). Options: native
         headless build for arm64 (GPLv2+, deps mostly in Orca's: CGAL, GMP/MPFR, Boost, Eigen,
         maybe Manifold; adds a parser plus FreeType/HarfBuzz for text()) giving meshes straight
         into the plate; or openscad-wasm in a hidden WebView as a quick PoC. UI idea: code editor
         plus Customizer comments shown as sliders, .scad open / share intent, later MakerWorld
         parametric models (MakerWorld's Parametric Model Maker is OpenSCAD-based).
       - Orca Cloud login: upstream `OrcaCloudServiceAgent` (default `ORCA_CLOUD_PROVIDER` in
         GUI_App) for preset sync and the plugin store.
       - Orca plugins: upstream `src/slic3r/plugin/` = embedded Python (pybind11) with host bindings
         (model, mesh, presets, slicing, UI); types pages / printerAgent / script / slicingPipeline;
         `CloudPluginService` subscribes / downloads through the Orca Cloud account. Android: embed
         CPython (official Android support since 3.13) or Chaquopy; the UI / pages bindings are wx
         and need Compose counterparts. Order: cloud login, then script + slicingPipeline plugins,
         then printerAgent, then pages.
       - Not wanted: an iOS port (user, 2026-10-09: "I'll never want to do ios port").
     - **Wishlist (later, user: "can wait"): filament colour mixing.** Investigated: upstream OrcaSlicer in
       `src-orca` already has mixed filaments (`FilamentMixer.cpp`, `filament_is_mixed`,
       `filament_mixed_components` / `_sublayer_ratios` / `_gradient*`, process
       `enable_mixed_color_sublayer`, `ToolOrdering::resolve_mixed_filaments`). A mixed slot is a
       virtual filament (preset copied from component 1, blended colour) resolved per layer or
       sub-layer into its physical components. Android needs: an add/edit dialog, mixed rows in the
       filament list, the per-slot keys in `selection_config` (size the vectors to the slot count
       first), project save/load, and leaving virtual slots out of AMS mapping. Caveat for the A1:
       at least one filament swap per layer that uses a mixed slot.
     - Test loop findings: variable layer height and flow-rate calibration (9 tiles with per-object
       settings) work; the VLH panel covered the selected object on wide layouts (moved in build 43). WSA lost
       DNS once (cloud showed obn -2 / "Lookup error"); `wsaclient /shutdown` + relaunch fixed it.
     - **Fixed in build 43 (untested):** after a calibration, Undo back to the old plate keeps the calibration's
       project name ("Temperature tower") for Save / export names. The desktop has no undo across
       a calibration start (it makes a new project).
     - **Tooling:** `install-build.ps1` now defaults `-Device` to WSA (with the Shield attached,
       a bare `adb install` failed). Don't use `step.ps1` while a system file picker is open: its
       relaunch closes the picker; tap with `adb shell input tap` instead.
     - **Verified working:** measure, object settings and info dialogs, variable layer height,
       seam / colour painting, two-colour slicing with prime tower, share G-code.
     - **Left to test:** lay on face, save G-code / .gcode.3mf, Send (uploads to the printer),
       cloud print (needs the user at the printer).
     - Not a bug: a new modifier sits inside the opaque object, so it is not visible.
     - **Still to test:** text / SVG / samples dialogs, paint, cut, measure, plate add / delete,
       flushing volumes, prime tower position, the object settings editor, share / save G-code.
     - **WSA caveat:** WSA stops drawing the app while its window is not in the foreground. Bring it
       forward with `wsaclient /launch wsa://io.github.baianced.orcastudio_android` before each
       step, and use taps, not key events (a key event without focus gives an ANR kill).
   - **Build 18** ([run 37904663855](https://github.com/BaIanced/OrcaStudio-Android/actions/runs/37904663855),
     commit `3676dad`, dispatched by the local session) adds obn patch 0003: MQTT TLS without a CA
     file reads `SSL_CERT_FILE` first.
     - Build 17 on WSA: the cloud switch saved `block_cloud = 0`. LAN failed as expected, then
       `connect_server -> 0`, but the cloud MQTT never connected: `cloud mqtt disconnect rc=14`.
     - Cause: outside Windows, obn v2.2.0 gives the cloud broker no CA file and only probes Linux
       trust paths (`mqtt_client.cpp`), none of which exist on Android.
     - The app already exports Android's CA store to `cacert.pem` and sets `SSL_CERT_FILE`
       (`ObnCredentials.prepareTls`).
     - Test plan below is unchanged; run it on build 18.
     - The 4 slots per the user: Bambu PLA Matte Bone White, iSanMate PETG HF Black, Bambu PLA
       Sunflower Yellow, Bambu PLA Matte Black. The earlier "2 wrong colours" report was a
       misread, so just compare the sync against this list.
     - Printer for a test print: 0.4 hardened steel nozzle, SuperTack plate.
   - **Build 17** (commit `7383c7c`, green; WSA result above) adds:
     - **Opt-in Bambu cloud** (2 switches in the connection dialog of the signed type, stored in
       `obn.conf`):
       - `block_cloud = 0`: ObnHost falls back to obn's cloud MQTT when the LAN fails
         (`cloudConnect` JNI), and commands go through obn's `send_message`.
       - `cloud_print = try_lan_first`: obn `start_print` (patch 0002) prints over the LAN when it
         can, else through Bambu's cloud. That path previously returned 403 for this account.
       - JNI exports are now 18.
     - The settings editor shows single string-list values without quotes.
   - **Test on WSA:**
     - turn the cloud switch on, then Test ("connected through the Bambu cloud");
     - status, Sync filaments (check the 2 wrong colours);
     - optionally a cloud print;
     - the editor's Vendor value has no quotes.
   - **Next after that:** mouse support and the plate context menu (task #5 in the cloud session);
     the permanent signing-key secrets.
0. **Run 16 verified on WSA by the local session (2026-10-09):**
   - The filament picker crash is fixed (groups render, picking a synced preset works).
   - "Sync presets from Bambu account" loads 199 presets without a crash.
   - The settings editor opens.
   - Still open:
     - **Display bug:** the editor shows string options serialized, e.g. Vendor `"Bambu Lab"`
       with quotes, Default color `""`. `preset_values` returns `opt_serialize`.
       Pre-existing, not yet fixed.
     - **LAN filament sync fails on WSA:** `rc=14 No route to host`. WSA's bridged network can't
       reach the printer while the Windows host can, so this is not app code.
     - The wrong-colour check waits for cloud AMS sync or a device on the LAN.
     - **CI debug signing key differs between builds:** `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
       on WSA. The fix is the 4 `ANDROID_KEY*` repo secrets, queued after the primary goal.
   - The local session is now `session_01Dyz5iJaQCQ1WuH3ktn4KU9` ("orcastudio-d5", after a
     restart). It has `~\orca-android-tools\install-build.ps1 -Run <id>` and root on WSA; send it
     run ids to test.
0. **Android test build run 16** ([run](https://github.com/BaIanced/OrcaStudio-Android/actions/runs/37871184670),
   commit `93b468a`) is **green** (2026-10-09 02:08Z). Run 15 results from the local session's
   device test:
   - Cloud preset sync no longer crashes.
   - Tapping a filament slot crashed with `Key "g:Bambulab" was already used`: PickerDialog emitted
     one header per run of a group, and synced user presets interleave with system ones. Fixed in
     `93b468a` (one header per group, de-duplicated keys). Re-test pending.
   - "Sync filaments from printer" needs LAN. The user wants cloud AMS sync instead (task below).
     The 2 wrong slot colours are still unchecked.
   - **Cloud AMS sync plan (waiting for the user's go-ahead):**
     - obn blocks cloud MQTT while `block_cloud = 1` (`abi_cloud.cpp:18`, `:66`; `send_message`
       fallback in `agent.cpp`).
     - An opt-in setting would write `block_cloud = 0`. obn re-reads `obn.conf` when
       `set_config_dir` is called again (`agent.cpp:1826-1842`, documented as idempotent).
     - Then `connect_server` + `add_subscribe([serial])`, a pushall through
       `bambu_network_send_message`, and the cloud reports go into `BambuReport`.
     - Printing stays LAN (`cloud_print = lan_only`).
0. **Android test build run 15** ([run](https://github.com/BaIanced/OrcaStudio-Android/actions/runs/37753117522),
   commit `7b7ceed`) is **green** (2026-10-08 09:22Z). User report on run 14 (2026-10-08): app
   installs, sign-in and PEM import work, filament sync works, but:
   - 2 synced slots show the wrong colour (details not reported yet);
   - tapping a filament slot afterwards (to change it or open its settings) closes the app with
     no message;
   - "Sync presets from Bambu account" closes the app the same way.
   The cause is **not determined**. Run 15:
   - adds `CrashReport.kt` (the report is shown with a Copy button on the next start);
   - restores the bundle state that `sync_ams_list` / `load_user_presets` change;
   - makes filament sync wait for a fresh AMS report.
   **Coordination:** the user's local Windows session (Remote Control,
   `session_01K9rgDzNwS1Ys37uHwxSUAE`, since 2026-10-09 `session_01Dyz5iJaQCQ1WuH3ktn4KU9`) does device debugging with adb and does not push. This
   cloud session is the only one that commits, dispatches CI and edits this file.
   Queued next: mouse support (left/right/middle-pan/back) and a plate context menu on right-click /
   long-press (e.g. add shape), modelled on OrcaSlicer desktop.
1. **Android test build run 14** ([run](https://github.com/BaIanced/OrcaStudio-Android/actions/runs/37577813513),
   commit `1139297` on Android branch `claude/handoff-continuation-rseql3`, which was restarted from
   `main` after PR #2 merged) is **green** (finished 06:11Z, so it compiles; APK artifact expires
   2026-10-14). The user asked for three features, all **untested on a device**:
   - **Printer messages with prompt buttons.**
     - `net/BambuReport.kt` parses `print_error` and `hms` from reports.
     - `net/HmsCatalog.kt` loads the texts and buttons per model like the desktop's HMSQuery
       (`e.bambulab.com/query.php` and `/hms/GetActionImage.php`, cached for a week). The
       session proxy blocks that host, so the response format was taken from the bundled files
       that HMSQuery saves unchanged.
     - The Device tab shows a message line and a prompt with the desktop's buttons
       (`DeviceErrorDialog::on_button_click` commands, checked identical at OrcaSlicer `f8dd560`).
     - The print notification shows the text and posts a separate notification for a new prompt.
     - Commands now wait 5 s for the printer's reply and surface `"result": "fail"`.
   - **Filament sync**: "Sync filaments from printer" under the filament slots. It feeds AMS /
     AMS lite / external-spool trays to `PresetBundle::sync_ams_list` (direct sync) via the new
     engine call `syncFilaments` (`core/jni/OrcaProject.cpp`).
   - **Cloud preset sync**: More > "Sync presets from Bambu account".
     - The new JNI `ObnNative.cloudPresets` runs obn's `get_setting_list` + `get_user_presets`.
     - Then `loadCloudPresets` runs `load_user_presets` + `save_user_presets`. App-only presets
       have no `setting_id`, so the sync never removes them.
     - The workflow's JNI export count is now 16.
   - Also: `ObnHost` instances share obn's single LAN session (reference counted), so the device
     tab's polls no longer drop the print notification's session.
   - The user's prompt that motivated this: `07FF-8007` "Please observe the nozzle ..." (A1, start
     of print). Handy offers "Filament Extruded, Continue" / "Not Extruded Yet, Retry"
     (`ams_control` `done` / `resume`). The earlier in-app cancel during that prompt did nothing
     visible. Why was **not** determined (no reply checking at the time).
2. Done 2026-10-07:
   - Run 12 printed on the device.
   - PR #2 merged (`8871376`), and the merge's run 13 published pre-release
     [android-v0.1.2-r13](https://github.com/BaIanced/OrcaStudio-Android/releases/tag/android-v0.1.2-r13).
   - Cause of the run 11 Print failure, verified against obn v2.2.0:
     - obn's default `mqtt_keep_connection = 1` (`config.hpp:62`) defers the disconnect.
     - The deferred teardown (`agent.cpp:117-129`) keeps the `app_cert_install_sent_` latch,
       and a clean disconnect reports `ConnectStatusOk` (`lan_session.cpp:130-133`).
     - So `install_device_cert` returned early (`agent.cpp:2062-2066`).
     - Fixed with `mqtt_keep_connection = 0`.
   - A possible upstream obn report: the deferred path should erase the latch like
     `disconnect_printer` does (`agent.cpp:330`).
   - Ask before merging the work branch to `main` again: that publishes a release.
3. **Not yet reported by the user:** whether the MakerLab tab works in Flatpak release
   **r9** (`arm64-v02.08.01.55-p6-obn-v2.2.0-r9`, published 2026-10-06 19:33Z). Untested
   areas:
   - ticket sign-in;
   - the 3MF/STL round trip;
   - whether MakerWorld shows its "open in slicer" actions.
4. Weekly upstream tracking (Android):
   - The first run built `upstream/orca-main` = main + OrcaSlicer `78f74a6` (2026-10-06).
     [Run 37549726219](https://github.com/BaIanced/OrcaStudio-Android/actions/runs/37549726219)
     is **green**.
   - The bump flagged `src/slic3r/GUI/Tab.cpp` for review. **Reviewed 2026-10-07: no impact.**
     In `f8dd5605..78f74a62` (40 commits), only `2d90345d` touches it, changing one wiki anchor
     (2nd argument of `append_single_option_line("infill_complete_top", ...)`). The extractor's
     `OPTION_RE` (`android/scripts/extract_settings_layout.py:27`) reads only the option key.
     None of the other port-dependent files in `android/UPSTREAM.md` step 2 changed in that range.
   - Merging it into main is the user's call.
5. Older optional items:
   - Find the real cloud 403 cause (see the Flatpak section).
   - Report obn patch 0001 upstream. The user doesn't want to learn PR mechanics, so prepare it
     and give them a link. Needs their OK.
   - Confirm the old `LD_LIBRARY_PATH` flatpak override was reset.

## Working rules (the user asked for these)

1. **Verify against source before recommending any change.** Read the obn / OrcaSlicer code
   path that decides the outcome and cite file:line. Earlier sessions went wrong when they
   skipped this:
   - `try_lan_first` was suggested without checking `start_print`.
   - "Sign out and back in" was suggested, but logout keeps the cloud MQTT session.
   - frames were counted at `debug` level, which never logs them.
2. Label anything untested as untested, inline.
3. **The user's output rules:**
   - Before any shell command the user must run, print a pre-flight block: 1. BLIND SPOTS,
     2. EXTRAPOLATED RISKS (2), 3. CAPABILITY VALIDATION (YES/NO).
   - Show calculations.
   - Give a direct verdict, brief, with nothing left out.
   - Web references as inline hyperlinks.
4. Keep check-ins cheap: single scheduled checks, not live monitors.
   - Builds run on GitHub. They're free for these public repos, ARM included: 0 billable ms.
5. Don't merge to main or publish releases without the user's go-ahead.
6. **Secrets:**
   - The user's `slicer_cert.pem` / `slicer_key.pem` / `slicer_crl.pem` must never go in a repo
     or bundle. Never explain how to obtain them.
   - `obn.auth.json` and trace logs contain tokens. Ask only for filtered output, e.g.
     `grep -o 'mqtt msg topic=[^ ]* bytes=[0-9]*'`.
7. **Both repos are public. Never put identifying details in them**: not in files, commit
   messages, PR text or GitHub comments. That covers printer serials, IP addresses, the user's
   e-mail address, account or display names, device models and tokens. Use placeholders such as
   `<serial>` and `<printer-ip>`.
   - The topic in the `grep` above contains the serial: ask the user to replace it with
     `<serial>` before pasting.
   - `.claude/hooks/privacy_scan.py` (in both repos) blocks this when the session runs inside
     the repo (Claude Code hook), and the `privacy scan` workflow checks every push.
   - In a multi-repo session started from the parent directory, the hook does **not** load. Run
     it yourself before every push:
     `python3 .claude/hooks/privacy_scan.py range "HEAD --not --remotes" <pathspecs>`
     (pathspecs: OrcaStudio `arm-build '.github/workflows/arm-*' .claude`; Android
     `. ':(exclude)src-orca'`).

## Repos

| Repo | Purpose |
|---|---|
| `BaIanced/OrcaStudio` | Public fork of jarczakpawel/OrcaStudio. Everything we own is in `arm-build/` and `.github/workflows/arm-*.yml`. `main` = `c3304375` (PR #1 merged: MakerLab patch + `publish` switch). Work branch `claude/handoff-continuation-rseql3`. |
| `BaIanced/OrcaStudio-Android` | cl1x/Orca-Android history plus signed Bambu printing via obn. `src-orca` submodule = OrcaSlicer. `main` = `8871376` (PR #2 merge; latest release `android-v0.1.2-r13`). Work branch `claude/handoff-continuation-rseql3` = main + `1139297` (printer messages, filament sync, cloud preset sync; **unmerged**). Bot branch `upstream/orca-main` (never commit to it). |
| `ClusterM/open-bamboo-networking` (obn) | Network plugin, pinned **v2.2.0** (`5e6a359c71a0c07476f5d372bf7ad0f2b43efe94`), with local patches. Read its source with WebFetch on `raw.githubusercontent.com/ClusterM/open-bamboo-networking/v2.2.0/src/<file>`. Main files: `agent.cpp`, `lan_session.cpp`, `lan_tls.cpp`, `abi_*.cpp`, `config.cpp`; `include/obn/config.hpp` has the defaults. |

In a new cloud session, add the Android repo with `add_repo` (BaIanced/OrcaStudio-Android) and
clone it next to OrcaStudio. A shallow single-branch clone needs a fetch refspec plus
`git push -u` before the stop hook stops complaining about "no remote branch".

## Flatpak (BaIanced/OrcaStudio)

- **`arm-flatpak.yml`** builds on `ubuntu-24.04-arm`:
  - Deps are cached under a `make-manifest.py --print-deps-key` key.
  - Then the app plus obn, the bundle, a smoke check (`ldd` in the sandbox), and a release
    `arm64-<os_tag>-obn-<obn_tag>-r<N>`.
  - Input `publish` (default true) gates the release job. Use `publish=false` for test builds.
  - Times: deps ≈ 26 min, app ≈ 41 min cold, ~5 min warm.
- **`arm-upstream-watch.yml`** runs daily: it bumps `arm-build/versions.json` on new upstream
  tags and calls the build.
- **`make-manifest.py` transformations:**
  - a) FFmpeg prefetch
  - b) obn module
  - c) launcher
  - d) BUILD_INFO
  - e) orca_deps as a reproducible tar
  - f) `shared/` added to the app module
  - g) wx `CMAKE_INSTALL_LIBDIR=lib`
  - h) `SSL_CERT_FILE`
  - i) feature patches from `arm-build/orcastudio-patches/*.patch`, applied in sorted order to
    the app module. These don't change the deps key.
- **obn patches** (`arm-build/obn-patches/`, mirrored in the Android repo's `android/obn/`):
  - **0001-cloud-callbacks-read-fresh** (r6, verified):
    - v2.2.0 snapshots `on_message_` in `connect_cloud()`.
    - A saved sign-in connects before the slicer registers callbacks, so every push_status was
      dropped.
  - **0002-start-print-try-lan-first** (r7, verified: the cube printed):
    - `start_print` always took the cloud path.
    - `POST /v1/user-service/my/task` returns **HTTP 403** on this account (cause unknown).
    - With `cloud_print = try_lan_first` plus dev_ip plus access code, the patch prints over
      LAN first: FTPS upload, then a signed `project_file`.
- **Feature patch `orcastudio-patches/0001-makerlab-tab.patch`** (in r9):
  - New `MakerLabPanel.{hpp,cpp}`: a WebView on `<model_http_url>makerlab?from=bambustudio`.
  - Signed in via `request_bind_ticket` + `api/sign-in/ticket`.
  - Allowlisted script messages:
    - `homepage_makerlab_open_3mf_binary`
    - `homepage_makerlab_stl_download`
    - `common_openurl`
    - `makerworld_model_open`
    - `homepage_login_or_register`
  - `MainFrame` adds the tab last (editor only). `show_device()` inserts Calibration before it.
- **Dark mode on Linux:**
  - The Preferences toggle exists only on Windows (`Preferences.cpp:1624-1627`). On Linux the
    app follows `wxSystemAppearance::IsDark()` (`GUI_App.cpp:3291-3294`).
  - Fix, confirmed by the user:
    `flatpak override --user --env=GTK_THEME=Adwaita:dark com.orcaslicer.OrcaStudio`.
  - Upstream bug: a theme change takes two launches (`:3227-3229` runs before `:3293`).
- **403 investigation (optional):**
  - Studio sends `start_print` directly when `cloud_print_only`, the access code is empty, or
    there's no SD card (`PrintJob.cpp:569-633`).
  - Find the cause at `log_level = debug`: `cloud_print.cpp` logs the `X-BBL-Client-Name` /
    `X-BBL-OS-Type` headers.
- **Logs:**
  - Studio log: `<data_dir>/log/debug_*.log.0`. Grep it with `grep -a`.
  - Job-log API returns only the last 5000 lines. The log zip host is blocked by the session
    proxy.

## OrcaStudio-Android (BaIanced/OrcaStudio-Android)

- **Base decision:** stay OrcaSlicer-based. OrcaStudio's engine lacks 53 libslic3r files and
  ~3289 profile files compared with OrcaSlicer. Its extras are desktop GUI/network glue, which
  Android doesn't build. The only portable candidate is the BMCU retry logic, and only if the
  user asks.
- **Upstream tracking:**
  - `.github/workflows/orca-upstream-watch.yml` runs weekly (Mondays 11:23 UTC), plus
    `workflow_dispatch` with a `force` input.
  - If OrcaSlicer main moved, it force-pushes bot branch `upstream/orca-main` (main + src-orca
    bump via `android/scripts/bump_upstream.py`, which updates `android/UPSTREAM.md` and lists
    port-dependent files) and dispatches `android-apk.yml` there.
  - Releases come only from `main`. The deps cache key hashes src-orca `deps/` +
    `cmake/modules`.
- **`android-apk.yml`:**
  - Triggers: `workflow_dispatch`, or a push to `main` touching `android/**`. Pushes to other
    branches don't build; dispatch them.
  - Build ≈ 18-26 min warm.
  - It checks for exactly **16** `ObnNative` JNI exports. Adding a JNI function means updating
    that number.
  - Artifacts are debug-signed. Release-signing secrets aren't set up.
- **obn on Android:**
  - Built as a static lib inside `liborca_jni.so` (`android/obn/obn.cmake`,
    `obn-android.patch`, patches 0001 + 0002).
  - Bridge: `android/core/jni/ObnBridge.cpp`.
  - Config dir: `noBackupFilesDir/obn`, holding `obn.conf`, the PEMs, `cacert.pem`,
    `printer.cer`, `obn.log` and `obn.auth.json`.
  - `writeDefaultConf` writes `cloud_print = lan_only`, `log_to_file = 1` and never overwrites
    an existing file. `block_cloud` stays at its default 1, which still allows sign-in and the
    printer list.
  - **Cloud HTTPS CA:** obn's vendored curl had no CA source.
    - `ObnCredentials.prepareTls` exports `AndroidCAStore` to `obn/cacert.pem`.
    - curl is compiled with `CURL_CA_BUNDLE=/data/data/io.github.baianced.orcastudio_android/no_backup/obn/cacert.pem`
      and `CURL_CA_FALLBACK` (`c23d34f`).
    - The user confirmed sign-in works with it.
- **Printer types:**
  - "Bambu Lab (LAN)": `BambuHost`, its own MQTT/FTPS client. Upload works. Print fails on MQTT,
    probably because the firmware rejects unsigned commands (not verified).
  - "Bambu Lab (signed, no LAN mode)": `ObnHost`, which prints with `start_local_print` over
    LAN, signed with the user's PEMs.
- **Account sign-in** (`net/BambuAccount.kt`, `ui/device/BambuAccountSection.kt`):
  - It mirrors `WebUserLoginDialog.cpp`: a WebView on `<host>/en/sign-in` with UA suffix
    `BBL-Slicer/v<ver> (dark) BBL-Language/en`.
  - A JS bridge maps `window.wx` / `webkit.messageHandlers.wx` / `chrome.webview` to
    `orcaNative`. Only bambulab.com/.cn may post.
  - The flow: the page posts `user_ticket_login` → `get_my_token` → `get_my_profile` → a
    `user_login` JSON → `change_user`.
  - "Use printer from account" (`get_user_print_info`) fills in the serial and access code.
  - Google sign-in in the WebView does nothing (not investigated).
- **Devices:** the user tests on several arm64 Android devices (phones and a TV box).

## User's environment

- **Chromebook:** Crostini, Debian 13, aarch64. They can also teleport the session with
  `claude --teleport <session id>`.
- **Flatpak:**
  - Installed `com.orcaslicer.OrcaStudio` r9 (MakerLab) via the updater
    `~/.local/bin/orcastudio-update.sh` (systemd user timer, daily). The updater skips the
    install while the app is running.
  - Data dir: `~/.var/app/com.orcaslicer.OrcaStudio/config/BambuStudio_OrcaSlicer/`.
  - The PEMs are there, with a backup in `~/obn-keys/`.
  - `obn.conf`: `block_cloud = 0`, `client_name = BambuStudio`, `cloud_print = try_lan_first`,
    `log_to_file = 1`, `log_level = info`.
- **A1:**
  - Its serial and LAN IP are in the user's app settings; never write them down (rule 7).
  - Cloud-bound, Developer Mode OFF, new authorization-control firmware.
  - The certificate exchange works. Bambu Handy works.
- **Android app:** signed in to the user's Bambu account, PEMs imported, printer configured as above.

## Useful facts

- GitHub access from the session is through the GitHub MCP tools: `actions_run_trigger`
  (dispatch), `actions_list` (runs), `get_job_logs`. They may fail to connect at session start;
  load them via ToolSearch.
- Tag pushes are blocked from the session. Actions create tags and releases.
- Auto mode denied cloning obn and probing the proxy. Read obn's source with WebFetch instead.
- obn logs per-frame MQTT only at `log_level = trace` (`mqtt_client.cpp`). Never ask for raw
  trace logs.

## Moved from HANDOFF.md on 2026-10-12 (verbatim; state as of r98)

### Now

Both repos work on branch `claude/handoff-continuation-rseql3` (the "test branch").
**Autonomy (user, 2026-10-11):** decide and act on whatever makes the most sense; don't stop to ask
about branch handling (there was no "two streams" rule). Main merges / releases still need the user.

- Run 38072645925 (test branch, Orca Cloud sync merge + CI cache) green, ~20 min; not installed.
- **r59 = run 38074555972 (test branch) installed on WSA:** OOM fix (user crash: a big slice's 325 MB
  `extrusions.bin` was copied into a direct ByteBuffer twice, `Viewport.kt:161` and
  `PreviewPanel.kt:268`, over the 512 MB heap). Now `Buffers.kt` memory-maps, and `Preview.cpp`
  FloatWriter writes `.tmp` + rename like the scene/paint meshes. WSA: the user's plate (97 MB
  extrusions.bin) slices and previews, Java heap 5 MB. The 325 MB slice itself: user to retest.
- **Orca plugin runtime (general capability, kept for future plugins).** User, 2026-10-11: uses no
  Orca plugins today; wants the ability kept. **FilamentHub is dropped for good (user dislikes its
  ToS); never suggest, test with, or mention it again.** The user's real filament site is
  3dfilamentprofiles.com (built in, below). Verified on WSA with a test plugin 2026-10-10/11: install
  from Orca Cloud, load, Pages page renders + messages, process option "Slicing Pipeline Plugin"
  picker (`SettingsEditor.kt` PluginPicker; engine `selection_config()` calls
  `update_plugin_manifest()`), post-process runs on Share (logcat tag OrcaPlugins), capability
  settings (r71, below). Pieces: embedded CPython 3.12.12 (Chaquopy build,
  `android/scripts/stage_python.py`) + upstream plugin host compiled unchanged
  (`android/core/plugins.cmake`); Android replacements in `android/core/plugin/` (PluginManager,
  PluginAuditManager = no sandbox hook yet, orca.host app/ui); `PluginConfig.cpp` extracted by
  `android/scripts/extract_plugin_config.py`. JNI `jni/OrcaPlugins.cpp` <-> `plugins/PluginNative.kt` /
  `Plugins.kt`; pages `ui/settings/PluginPage.kt`. Installs to
  `files/data/orca_plugins/_subscribed/<user>/<uuid>`. Not done: audit prompts, create_window,
  per-preset config overrides, printerAgent capability. Pure-Python plugins only. Uninstall (r80):
  subscribed list has Remove (`PluginManager::delete_plugin` in `AndroidPluginManager.cpp`);
  Install over a loaded plugin now replaces it (was "restart to update"). Verified on WSA with Orca
  Inspector (install, reinstall, remove; it was not installed before, so removed again).
  WSA wedged once (no app window): stop WsaClient, `Start-Service WsaService`, `Start-Process wsa://<package>`.
  "Plugins Used" restore fix (1fcfb56) verified. G-code save picker remembers a folder in the user's
  own project dir: test saves via Share + logcat.
- **3D Filament Profiles (3dfilamentprofiles.com) = r67, run 38108434060, verified on WSA
  2026-10-10.** The utility the user actually uses (all features wanted). No public API (issue #134),
  Vercel bot checkpoint (scripted fetches 429; WebView passes). Built in, not a plugin:
  - Models tab 4th site (`ModelSite.FILAMENT_PROFILES`); the user is signed in there (email/GitHub/
    Discord; Google likely blocks WebView sign-in).
  - Preview > More > "Log print to 3D Filament Profiles": `net/FilamentProfiles.kt` builds
    `/my/print/log?v=1&f=&p=&d=<base64url G-code comment lines>` like the site's own
    `tools/slicer-print-log/3dfp-print-log.py`; opens in the Models tab (`UiState.modelsUrl`). Verified:
    page lists printer, time, 66.70 g per filament + spool picker. Never press Subtract in tests.
  - Spool profile export (My Spools row > Actions > Export slicer profile > OrcaSlicer/A1/0.4) is a
    blob: JSON revoked on click: BRIDGE_JS keeps blobs 60 s (`__orcaBlobs`), the download listener
    reads them back; `.json`/`.orca_*`/zips of JSON go to the preset import (`FileController.openDownloaded`).
    Imported "Overture PETG White (3DFP T41GBvwn4) @A1" (user approved exporting any spool).
  - CDP on its page: `cdp.mjs` / `pick.mjs` (Radix selects need pointer events) in the scratchpad.
- Branches: `claude/orca-plugins` was fast-forwarded into the test branch; work on the test branch.
- **Session gotcha:** the app restores `files/session/session.3mf` on start and saves it only in
  `onStop` (41 MB, takes a while). `am force-stop` skips the save, so test edits reset before a
  force-stop come back. WSA: HOME / another app did not trigger onStop.
- **AMS mapping (2026-10-11, b361cad, r70 = run 38119747043, installed on WSA).** Verified (FTPS :990
  blocked by iptables during the test so nothing could print): dialog maps "1 · PLA" (#FEC600) to
  A1 PLA Basic (yellow), dropdown lists A1-A4; Cancel; export slice_info now has prediction, weight,
  `<filament id="1" type="PLA" color="#FEC600" .../>`. Real print (user OK'd): 10 mm cube on a temp
  Plate 2, dialog -> A1, "Print started", obn log `use_ams=1` (all earlier prints 0). **User confirmed
  2026-10-11: printed yellow from A1 = AMS mapping verified.** Temp plate deleted, app closed via window close. User: the r69
  LAN cube printed from AMS A4 (PETG) instead of yellow PLA and globbed the nozzle. Cause: obn print
  got `use_ams=false`, empty `ams_mapping` (`ObnHost.kt` print call; `BambuHost.kt` same). Fix:
  `net/AmsMapping.kt` (port of `DevMapping.cpp:122` ams_filament_mapping: same type, closest CIE76;
  serialization as `SelectMachine.cpp:1343`), Print on Bambu -> `ui/preview/AmsMappingDialog.kt`
  (pre-filled, pick per filament) -> `DeviceController.confirmPrint`; JNI `ObnNative.print` gained
  `amsMapping2`. Also fixed: `.gcode.3mf` slice_info had no filaments/time/weight since plugin
  post-process made exports a copy (lookup by path; `exportGcode3mf(..., sliced)`).
  User's AMS lite: A1 yellow, A2 matte black, A3 white, A4 PETG. **Test print needs the user's OK.**
  Safe dialog test: `iptables -I OUTPUT -d <ip> -p tcp --dport 990 -j REJECT` (root), Print, Cancel,
  then `iptables -D ...`. Closing the WSA window via `CloseMainWindow()` on the WsaClient
  "OrcaStudio-Android" window runs onStop (session save verified 2026-10-11), unlike force-stop.
- **Session handling (user, 2026-10-11):** keep this file current before stopping any task so the
  user can /clear at any point; cheaper (haiku/sonnet) subagents OK for builds/installs/searches.
  User wants a work loop that clears context between tasks and runs until everything is done
  (usage saving). Claude can't run /clear itself; `/next` (project skill in
  `OrcaProjectsGithub/.claude/skills/next`) = "read handoff, continue". A fully automatic loop
  would be a script running fresh `claude -p` sessions per task (not built; ask the user first).
- **Local native build in WSL** (25 min cold, seconds incremental): `~/orca-android-tools/wsl/corebuild.sh`
  (`MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/Users/Vince/orca-android-tools/wsl/corebuild.sh`) syncs `android/` from the
  Windows checkout (`syncsrc.py`, CRLF->LF, only changed files) into `~/src/OrcaStudio-Android`
  (branch clone, src-orca pinned) and builds in `~/orca-build` (deps from the CI artifact of run
  38078498928, `deps_artifact=true` dispatch input; NDK r28c, cmake 3.31 + pkgconf + ccache in
  `~/.local/bin`; `~/build` is root-owned, don't use). `undef.sh` lists undefined symbols / NEEDED.
- CI: ccache never hit (fresh NDK mtime) -> `CCACHE_COMPILERCHECK=string:$NDK_VERSION`; caches are
  per branch (a feature branch's first build is cold); prune is per branch now.
- Verified on WSA 2026-10-10:
  - Orca Cloud sign-in (user: Google round trip works, 4 subscribed plugins listed, sign-in
    survives a restart; the account has no password, GitHub not tried).
  - Mouse back (adb `input mouse keyevent 4`): More -> Prepare; on Prepare it is eaten instead
    of backgrounding the app (user report: "acts like minimise"). Real mouse button (user).
  - MakerWorld: dark web pages, native links row, blank strip trimmed, Collections opens the
    user's collections.
- Verified 2026-10-11 (r77): MakerWorld "Full site" chip (header, no BBL UA, back to slicer mode);
  theme switch recreates the Models browser (page prefers-color-scheme follows Light/Dark); CI
  cache works (builds ~6 min). Orca Cloud pull/upload: user tested on r72 (see below).
- **r79 = run 38155435432 (Models zoom fix), installed + verified on WSA 2026-10-11:** Ctrl+wheel / pinch did nothing on MakerWorld because
  its viewport meta has `maximum-scale=1.0, user-scalable=no`. `ZOOM_JS` in `ModelsScreen.kt` strips
  those (MutationObserver re-applies). Verified on r79 (CDP + real Ctrl+wheel): meta unlocked on load, scale 1 -> 1.21 -> 1.46 -> 1.
  (The first Ctrl+wheel right after a meta change is ignored; later ones work.) Pinch: user to test.
- **Section view (r80 = run 38157697528; r82 = 38158608656 panel fit + mirrored winding; r83 =
  38159448169 panel clears the tool column; r84 = 38159995534 two-row panel, installed on WSA, layout
  verified), verified on WSA r80/r82/r84:**
  port of GLCanvas3D's section view. `render/SectionView.kt` (ratio, normal = camera direction at
  first use, plane over the objects' bounding sphere as `_get_section_view_plane`), shaders discard
  the camera side (`uSection`), back faces through the cut drawn dark grey as cut faces (no
  MeshClipper). View menu > Section view toggles + opens the panel (`SectionViewPanel.kt`: slider,
  Set viewing angle, Reset, Close); Alt+wheel +/-0.01 (verified 0.50 -> 0.52 -> 0.48); Esc closes the
  panel. Mirrored copies: scene/paint export now keeps triangles facing out (`OrcaScene.cpp`
  transform(..., true), `OrcaTools.cpp` emit swaps corners); verified r82 by mirroring a copy on the
  user's plate, then force-stop (no session save) -> plate unchanged. Reset/Close verified r82.
  Not done: travels/markers aren't clipped, picking ignores the cut.
  WSA gotcha: screenshots go stale while the window isn't foreground; run focus.ps1 first.
- **Plugin capability settings (2026-10-11), r71 = run 38122146536 (38eb085), installed + verified
  on WSA:** More > Orca Cloud section: "<capability> settings" button per capability ->
  `ui/settings/PluginConfigDialog.kt` (custom page from get_config_ui with the plugin-config-ui.js
  bridge via JS interface `orcaConfigHost`, host theme vars; else JSON editor); native
  `config_open/save/restore` in `AndroidPluginRuntime.cpp` (global config layer only). Saves land in
  `files/data/orca_plugins/config.json` and reload correctly.
- **r77 = run 38128522536 (f80e7a0), installed + verified on WSA 2026-10-11:** Ctrl camera
  shortcuts fixed. Two causes: (1) Android offers Ctrl keys to `onKeyShortcut` only when normal
  dispatch leaves them unused, which never happened with the 3D view focused ->
  `MainActivity.dispatchKeyEvent` runs `onKeyShortcut` first for Ctrl key-downs while the view has
  focus; (2) the view only took focus on a click -> `PlateView.onGenericMotionEvent` takes focus on
  any hover/scroll unless a text field has it (desktop `GLCanvas3D.cpp:4472-4482`). Verified: View
  menu used, hover only, Ctrl+0 resets the view; `adb shell input keycombination 113 8` (Ctrl+1) ->
  Top view. **Windows-side Ctrl+1..9 (keys.ps1 keybd_event) never reach Android** (log shows only the
  Ctrl key; Ctrl+0 arrives): WSA or something on the PC drops them, not fixable in the app. Ask the
  user whether a real keyboard's Ctrl+1 works. OrcaInput DEBUG logs "focus" / "shortcut" / "key".
- **r74 = run 38126545229, installed on WSA 2026-10-11:** Quit dialog verified (centred title, note,
  equal No/Yes). Modifier input verified with modin.ps1 (synthetic real-OS input): Ctrl/Alt/Shift+wheel
  arrive as ACTION_SCROLL (meta CTRL 0x3000 / ALT 0x12 / SHIFT 0x41, Shift as h=-1) and zoom (screenshot
  diff); Ctrl/Alt/Shift+middle-drag pan (WSA sends touch DOWN + hover moves buttons=4, no touch MOVE/UP).
  The saved r72 log (`~/orca-android-tools/orcainput-r72.log`) has no modifier events in the new
  format, so the user's earlier "deliver nothing / plain drag" reports aren't reproduced; ask the
  user to retest on r75 if they still see it. Alt+wheel section view: done in r80 (below).
  modin drags shift the camera: restore with View (camera icon) > Fit plate.
- **r73 = run 38125719209 (built, superseded by r74):** undo limit setting (More > Misc, 1-5000, default 200; was a
  fixed 30, `OrcaScene.cpp` m_undo_limit / engine call setUndoLimit). Verified on r71 (temp plate,
  removed): Delete key (adb + real keyboard), real Ctrl+Z, arrows 10 mm / Shift+arrow 1 mm all work
  already. Scratch tools: `keys.ps1 -Keys <vk...>` (real keys), `click.ps1` (picks the wrong WsaClient
  window, unfinished).
- **r72 = run 38125329109 (installed on WSA 2026-10-11, OrcaInput DEBUG on):** Back on Prepare with nothing to clear asks
  "Quit?" Yes/No (Back closes it); More > Misc > "Press Back twice to quit" option (toast, 2 s).
  Shift+wheel in the 3D view used AXIS_HSCROLL (=0 -> always zoom out): fixed. OrcaInput log now
  has meta/axes/tool/source. User test results 2026-10-11: Orca Cloud pull no error (same presets),
  upload "0 uploaded" (fine); plain wheel zooms 3D view (= desktop). Ctrl/Alt+wheel and modifier+middle-drag:
  see r74 (work in synthetic tests). Capture tool: `scratchpad/modin.ps1 -Key 0x10|0x11|0x12 -Mode
  middle|wheel` + `setprop log.tag.OrcaInput DEBUG` (WSA input isn't visible to getevent/dumpsys).
- Order (user): Orca Cloud login (done), preset sync (built, testing), plugin runtime (done as a
  general capability; no plugin in active use). **Next (user, 2026-10-11): modeling in the app**
  - OpenSCAD, or any other way to use the slicer as 3D-printing modeling software. Not started;
  no code yet. Ideas to check first (all unverified): upstream src-orca may already have a
  "Design" tab (GLCanvas3D.hpp comment mentions "the Design tab's canvas" + studio lighting) -
  grep `src-orca/src/slic3r/GUI` for it; OpenSCAD's WebAssembly build (openscad-wasm /
  openscad-playground) could run in a WebView and hand the STL to the import path; upstream
  shapes + mesh boolean gizmo as a native option. Present options to the user before building.
  **Checked 2026-10-11 (recommended: native OpenSCAD core in `liborca_jni.so`, not WASM):**
  - WASM build forces `MANIFOLD_PAR OFF` (single-threaded) and NULLGL; native gets Manifold + TBB
    on all cores. OpenSCAD @c0ac828 has `HEADLESS`/`NULLGL` and a static `OpenSCADLibInternal`.
  - Shared with our Android deps prefix: Boost (has regex; lacks program_options, used only by
    `openscad.cc` main -> patch out), CGAL, GMP/MPFR, Eigen, TBB, FreeType, OpenSSL, zlib, expat.
    New: Manifold + Clipper2 (submodules; Orca's `deps_src/clipper2` is 2.0.1 -> watch duplicate
    symbols), double-conversion, libzip, libxml2, harfbuzz, fontconfig, flex/bison (host).
  - glib use outside `src/gui` is ~10 `g_utf8_*`/`gunichar` calls (`str_utf8_wrapper.h`,
    `UnicodeIdentifier.cc`, `Value.cc`, `FreetypeRenderer.cc`) -> small shim over utf8proc
    (minSdk 29, so NDK ICU (API 31) is out).
  - Upstream "Design tab" (`src/slic3r/GUI/CAD/*`, `libslic3r/CAD/*`, OCCT B-rep sketch/primitives)
    exists but is wx GUI; Android builds with `SLIC3R_CAD OFF` (`android/core/CMakeLists.txt:38`).
  - **Step 1 started (user said "start", 2026-10-11):** `android/openscad/` superbuild ->
    `libopenscad.so` (C API `bridge/scad_bridge.h`: `scad_render(src, file, params_json)` ->
    triangle soup + log, `scad_parameters()`), only `scad_*` exported (`--exclude-libs,ALL`).
    `shim/` = glib subset over utf8proc + gettext no-ops. `patch_openscad.cmake` drops
    `src/openscad.cc` + program_options and adds the bridge subdir. CI `android-openscad.yml`
    (push to `claude/**` touching android/openscad; restores the apk deps cache, own deps cache +
    ccache; artifact `libopenscad-arm64-v8a` incl. `scad_smoke`, fonts, MCAD). **Step 1 DONE: run 38168198443 green, verified on WSA 2026-10-11** (lib 12 MB stripped; scad_smoke:
    parameters JSON, CSG render 1792 tris, Customizer override works; `text()`/fonts/perf untested). Test:
    `gh run download <id> -n libopenscad-arm64-v8a`, `MSYS_NO_PATHCONV=1 adb push` lib+bin+resources to
    /data/local/tmp/scad, `LD_LIBRARY_PATH=. ./scad_smoke /data/local/tmp/scad/resources`. Fixed on the way:
    pipx meson >=1.6.1, expat.pc, libxml2 pinned (NDK host .so), target `scad_core` (name clash).
  - **Step 2 DONE (2026-10-11): r85 = run 38170713625, r86 = 38172403593, r87 = 38173875723 (installed
    on WSA, verified below); r88 = 38174598085 (gear example pitch fix only, not installed).**
    `bridge/scad_jni.cc` (JNI for `app.orcaandroid.scad.ScadNative`, writes binary STL, returns
    {ok,dimension,triangles,log}; OPENSCAD_FONT_PATH=/system/fonts, HOME/XDG_CACHE_HOME=cache).
    CI: android-openscad.yml caches `out/` as `android-openscad-out-<tree hash of android/openscad>`;
    android-apk.yml restores it and stages `$BUILD_ROOT/openscad-jniLibs` + `openscad-assets/openscad`
    (gradle adds both; miss = warning, APK without OpenSCAD -> "This build does not include OpenSCAD").
    App: `scad/ScadService.kt` (process `:scad`, Messenger, 256 MB-stack thread, unpacks assets to
    `files/openscad-resources` per lastUpdateTime; `OrcaApp.onCreate` returns early in `:scad`),
    `scad/ScadClient.kt` (cancel = kill the :scad process via runningAppProcesses),
    `scad/ScadModel.kt` (Customizer JSON parse/encode, vectors as "[a, b]" strings; 3 examples),
    `ui/ScadController.kt` (UiState.scad; draft in `files/openscad/draft.scad|json`; render -> loadModels,
    replaces the object it placed (by name) keeping offset xy/rotation/scale/mirror; auto-render on
    Customizer change once an object is on the plate), `ui/scad/ScadEditor.kt` (panel: Code tab with
    highlighting/line numbers/auto-indent/Tab, Customizer tab, console with clickable "line N",
    Render/Update/Add as new/Stop; F5/F6/Ctrl+Enter render, Ctrl+S save). Entry: Prepare + menu and
    bed context menu "OpenSCAD model"; opening .scad files (also in zips; main = file no other includes).
    Wide: panel replaces the 380 dp side panel (45% width, 420-640 dp); phones: over the view with
    "Show plate". **Verified on WSA (r85/r86):** editor look (highlighting, line numbers, tabs);
    box renders + places (414 tris, 0.4 s); Customizer slider auto re-renders in place; text tag
    (fonts) 3,506 tris; error log auto-opens console; Stop kills :scad and a fresh one serves the next
    request; 6000-sphere for() renders after the RLIMIT_STACK raise (was a false "Recursion detected");
    new objects go to a free spot (2nd box beside the 1st). Fixed on the way: `parser_init()` (library
    path), RLIMIT_STACK 240 MB in scad_init, client restarts :scad after a "Recursion detected" log
    (OpenSCAD's 1M tail-call limit left the process rejecting all calls), `handleDependencies()`.
    r87: MCAD gear renders (use<> works; the example's pitch was in old units -> 2.5 m gear, fixed in
    r88: MCAD spur gear takes mm per tooth, involute_gears.scad:324). Console "line N" tap switches
    to Code and scrolls to the line (r86). Untested: Save/Open .scad via the picker, phone layout,
    Ctrl+Enter/F6/Ctrl+S, Tab/auto-indent, opening .scad from a download/zip.
    Test helper: `~/orca-android-tools/tapf.sh x y` (focus + tap; unfocused WSA taps are lost).
    **WSA session gotcha (2026-10-11):** the session got saved mid-test (14:22) with test objects;
    `CloseMainWindow()` now kills the app without onStop (no save). Reliable save: open the system
    file picker from the app (Add > Import model), wait for session.3mf mtime, force-stop
    documentsui. Test on a temp Plate 2 and remove it after. When deleting objects by list
    position, verify the selection first (the list shifts; Pose08 got deleted once, undone).
  - **Step 3 DONE: r89 = run 38178385444 (commit "keep the design with the object in the 3MF"), installed +
    verified on WSA 2026-10-11:** box with a modifier, Customizer width 60->90 replaced the mesh in
    place, modifier kept; session.3mf held `module` = openscad JSON (source newlines intact); after
    force-stop + restart the restored box offered "Edit OpenSCAD design", which loaded the box (draft
    was the gear) and Update on plate replaced it (object count unchanged). Painting remap untested.
    Temp plate deleted, session saved clean (8 objects). Details: design kept in
    `ModelObject::module_name` = "openscad:" + compact JSON {name, source, values} (`ScadDesign` in
    `scad/ScadModel.kt`; 3MF writes it as `<metadata key="module">`, desktop Orca round-trips it;
    no submodule change). Scene JSON `module` -> `SceneObject.module`. Engine calls `replaceMesh`
    (port of `Plater.cpp:11602` replace_volume_with_stl: keeps settings/modifiers/instances, remaps
    painting) and `setObjectModule` (no undo step) in `OrcaScene.cpp`. Object context menu "Edit
    OpenSCAD design" -> `ScadController.editObject`. Native + Kotlin compiled locally. To test:
    render, paint/setting on it, Customizer change keeps both; save project, reload, Edit design.
    Not done: small in-editor preview; relative include<> files aren't embedded.
  - **r90 = run 38182983535 (commit "Modeling: Assemble ..."), CI green, installed on WSA;
    results below:**
    - Engine `assemble(items)` (port of `GUI_ObjectList.cpp:3045` merge: copies become parts of one
      "Assembly", world placement kept) and `mesh_boolean(object, src, tool, op, keepTool)` (port of
      `GLGizmoMeshBoolean.cpp:381-431` via `MeshBoolean::mcut::make_boolean`, painting remapped).
      Deviation: the gizmo sets the result's offset to the old part's offset (`:503`), which shifts a
      union with an off-centre part; we keep add_volume's centred offset. Added parts are named "Part N".
    - UI: multi-select toolbar + context menu "Assemble"; object More menu "Add part" (box at the
      object centre) and "Mesh boolean" (dialog `PrepareDialogs.kt` MeshBooleanDialog; context menu
      entry when the object has 2+ parts).
    - OpenSCAD: `ScadDeps.collect` embeds local include<>/use<> files (relative to the main file,
      2 MB cap, library files excluded) in the design JSON `files`; `ScadDesign.unpack` restores them
      under `files/openscad/designs/<hash>/` on Edit design. Draft meta now always keeps an opened
      file as the include base (was dropped once the text changed).
    - **r90 on WSA (temp Plate 2, 2026-10-11):** Assemble verified (cube + cylinder -> "Assembly",
      positions kept, 732 tris). Mesh boolean dialog verified; difference ran (cylinder didn't overlap,
      so result = cube). Found: "Add part" threw "Unsupported volume type" (code 0 missing) and parts
      couldn't be moved at all.
    - **r91 = run 38183994457:** fixes Add part (placed beside the object at +X like
      `GUI_ObjectList.cpp:2565-2578`), part/modifier Position-in-object / Rotation / Size fields under
      the selected part in the object card (engine `setTransform` takes `volume`; scene JSON volumes
      carry rotation + scale).
    - **r91 verified on WSA 2026-10-11 (temp Plate 2, deleted after; session saved clean, 8 objects):**
      Add part = 10 mm box beside the 20 mm cube; part X 15 -> 8 via the new field moved it into the
      corner; difference 12 -> 38 tris; undo -> 2 parts; intersection = 7x10x10 box in place (centre
      +6.5/-5 mm); union 46 tris, centre +1.5 mm (no shift). Painting through a boolean: untested.
      Label "(mm) (mm)" fixed after (commit "Part position label", not built yet - goes with the next build). OpenSCAD: a .scad zip with a local include, render, Edit design
      after restart.
  - **OpenSCAD beginner UX (2026-10-11), r93 = run 38186440697 installed + verified on WSA:**
    live preview pane (`render/MeshPreviewView.kt`, own GL view, `PreviewMesh.readStl`; controller
    `preview()` debounced: 1.2 s after edits, 0.3 s after Customizer; Customizer change + object on
    plate -> preview then `render()` reuses the preview STL via `previewKey`); size chip + "larger than
    the plate"/"taller" warning; F5/Ctrl+Enter preview, F6 add to plate. Template gallery
    (`scad/ScadTemplates.kt`: blank, box, lid box, spacer, hook, stand, tag, gear: all 8 render on
    WSA with the expected sizes). Insert chips + "Add a slider" (`scad/ScadSnippets.kt`), error tips
    (`ScadHints`, verified: syntax error -> "could not read line 3" + Go to line). Fixed on the way:
    `ScadClient` lost the reply of a request queued right behind another (pending cleared after
    resume ran the next request inline) -> preview hung on "Building" in r92.
    Commit 077e3ca (not built): Insert with no cursor placed appends at the end (was after line 1,
    above the parameters -> Customizer emptied); Add a slider stops at the first shape call.
    Untested: phone layout, pinch/orbit on a real touch screen, Insert wrapping a selection.
    Short panels (WSA 1080p) leave little room under the preview: collapsible, 30% of the height.
  - Plan: separate cached OpenSCAD deps stage (don't touch the Orca deps cache key), JNI
    `renderScad(src, params)` -> TriangleMesh straight into the plate, source + Customizer values
    stored in the 3MF so the object re-renders when edited; Compose code editor + Customizer panel.

### NEXT: modeling roadmap (user agreed 2026-10-11, in this order)
User goal: model **without writing code**; OpenSCAD code is only a backstop for debugging. Today
no-code = templates + Customizer sliders + native parts/boolean/assemble; new shapes still need code.
1. **Shape builder phase 1: r96 = run 38208190893 installed + verified on WSA 2026-10-11** (temp
   Plate 2, deleted after; user's plate untouched = Plate 1, 1 object Pose05.step): Add menu ->
   Shape builder opens; box + cylinder added side by side; Hole cut (12 -> 48 tris); X/W fields;
   preview overlay (blue selection box) + tap pick + drag moved the box to (26, 15) on 1 mm snap;
   toolbar Undo; long-press multi-select + Group (cut kept); ⋮ Make a slider on W -> Customizer
   slider 20 -> 30 changed the size; Add to plate; auto-save, force-stop, relaunch, long-press
   object > Edit OpenSCAD design reopened the Shapes tab at 30 mm. Bugs found -> fixed in
   **r97 = run 38209274086, installed + verified on WSA 2026-10-11 (temp Plate 2 deleted, session
   saved clean, app closed)**: new design starts empty; cylinder 312 tris (round); all 12 kinds
   render (box..text, ring/dome/text codegen OK); repeat row + circle (circle turns about the
   design origin 0,0 by default); group -> ungroup leaves every translate identical. Still
   untested: align, mirror, dims list edit/remove, keyboard shortcuts, phone layout. Small: the
   preview doesn't re-frame as the design grows (Frame button works).
   **r98 = run 38210814654 (commit 274162b): preview handles, NOT yet installed/tested.** A lone
   selected shape gets X/Y/Z size dots (red/green/blue, on the max faces; min side kept, 1 mm steps,
   `ShapeController.resize`, one undo step) when `ShapeController.handles()` says its box is its own
   size (leaf, no rot, no repeat, unturned groups), and an amber turn dot + arc below it (-Y) about
   the pivot (15°, Shift 1°; `ShapeController.turn`, rot.z kept in -180..180); drag shows a blue
   ghost (`PreviewOverlay`, `MeshPreviewView.overlay()`). Also: Customizer "Add a slider" in builder
   mode now adds a design dim (`ShapeController.addDim`, toast `shape_dim_added`). Test on r98:
   temp Plate 2, Shape builder, box: drag red dot -> W grows, min side fixed; blue dot -> H; amber
   dot -> rot.z 15° steps; Undo once each; group selected -> only the turn dot; Customizer Add a
   slider -> dim appears in Sliders. Then NEXT: phase 2 (sketch + extrude).
   Fixed in r97: new design showed the previous
   draft's preview (late preview result now dropped when the key changed, `runPreview`); round
   shapes had 7 sides (scaled 1 mm primitives + $fs -> generated `function sides(d)` sets $fn);
   palette FlowRow filled the short panel (now one scrolling row); slider names were
   "box_1_size_mm_w" (now "box_1_width"); placed objects were named after the preview STL
   ("4795...stl", also hit plain OpenSCAD designs; now a copy named after the design).
   To verify on r97: cylinder looks round, new design starts with an empty preview, object name.
   Untested: align, mirror, repeat, ungroup, dims section edit/remove, text/torus/tube/dome codegen,
   keyboard shortcuts (need focus on the Shapes tab), phone layout.
   Files: `scad/ShapeModel.kt` (Shape/Dim/Repeat/ShapeDesign: JSON, bounds, `toScad()` codegen;
   pos = bottom-centre of the box, rotate about box centre, holes cut solids of their own group
   AND at top level), `ui/ShapeController.kt` (add/update/setField/delete/duplicate/group/ungroup/
   align/drop/mirror/move/repeat/sliders, undo 200), `ui/scad/ShapeBuilder.kt` (Shapes tab:
   palette, toolbar, tree list (long-press = multi-select), properties, ⋮ per field row = make /
   follow / stop following a slider, Sliders section; keys Del, Ctrl+D/G/Shift+G/Z/Y/A, arrows
   1 mm (Shift 10), PgUp/PgDn z, H/S, Esc), `MeshPreviewView` PreviewBox overlay (selection blue,
   holes grey, drawn on top), tap pick (slab test), drag on a shape moves the selection (1 mm snap,
   plane = box bottom), empty drag still orbits. ScadController: `shapes`/`selected` in
   ScadEditorState, `newShapes()`, `setShapes()` (regenerates source; dims-only change pushes to
   the plate like the Customizer), `detachShapes()` ("Edit as code"), draft + ScadDesign carry
   `shapes`. Entries: Prepare Add menu + bed context menu "Shape builder", template gallery card.
   Code tab is read-only for shape designs. Customizer slider changes write the dim value.
   Known gaps: on-screen scale/rotate handles, Customizer "Add a slider" does nothing in builder
   mode, ungroup of a rotated group with rotated children is approximate, renaming a shape
   re-renders (name is a code comment). Test plan: open via Add menu, add box + cylinder, make
   cylinder a hole, group, preview shows the cut; drag in preview; slider on box width + Customizer;
   Add to plate, restart, Edit OpenSCAD design -> Shapes tab back. Use a temp Plate 2.
   Reuse check (user asked 2026-10-11 to reuse public projects where logical): no public
   touch shape-builder -> OpenSCAD exists (search 2026-10-11). Materializr (F-Droid, GPL-3,
   OpenCASCADE, Dear ImGui UI) is the only Android touch CAD found: UI not reusable in Compose;
   look at it (and FreeCAD's planegcs solver) for phase 2 sketch/extrude/revolve.
   Original brief: User: "replicate any of the best modeling
   software in a touchscreen format", with full keyboard + mouse support too. References: Tinkercad
   (drag shapes onto a workplane, Solid/Hole, Group, Align, Mirror, Ruler, Duplicate-repeat),
   Shapr3D / Onshape mobile (touch-first: sketch + extrude/push-pull, fillet/chamfer, snapping,
   numeric entry on tap), Fusion (timeline/feature history, parameters). Design:
   - Data = a CSG tree (JSON) kept in the object's `module` metadata like `ScadDesign`
     (`scad/ScadModel.kt`) -> "Edit design" reopens the tree. The tree **generates OpenSCAD code**
     (shown read-only-by-default in the Code tab) -> rendered by the existing native OpenSCAD
     (`ScadService`/`ScadClient`), live preview (`MeshPreviewView`), replace-in-place on the plate.
   - Phase 1: shapes (box, rounded box, cylinder, cone, sphere, torus, wedge/prism, text, polygon
     extrude), Solid/Hole, Group (union/difference), move/rotate/scale with on-screen handles +
     numeric fields, snap grid, align, mirror, duplicate, linear/circular repeat, named dimensions ->
     Customizer sliders, undo/redo, multi-select. Touch: tap select, drag move on the workplane,
     two-finger orbit/pinch zoom, long-press menu. KB/mouse: click/Shift-click, drag, wheel zoom,
     right-drag orbit, Delete, Ctrl+D/G/Z/Y, arrows nudge, H = hole, S = solid.
   - Phase 2: 2D sketch (lines/arcs/rects/circles, constraints light) + extrude/revolve, fillet/
     chamfer (minkowski/offset), shell/hollow, import STL as a shape (import()).
   - Then UI test on WSA (WSA has no touch: test touch paths with adb `input`; phone layout untested).
2. **AI tool layer (MCP server in the app)**, built on the shape-builder JSON. Research (not
   prototyped): the engine already exposes ~65 JSON calls (`android/core/jni/EngineCalls.cpp`:
   scene, addPrimitive, setTransform, meshBoolean, assemble, cut, arrange, presetValues,
   setSelection, savePreset, ...) -> wrap as MCP tools. Cheapest "vision", in order: (a) the CSG
   tree as text (AI reads/edits it directly), (b) scene summary JSON (objects, bbox, position,
   parts, volume, tris, fits plate; est. a few hundred tokens for 8 objects, unmeasured),
   (c) offscreen render of the model only, small PNG from a chosen angle (~350 tokens at 512x512,
   Claude image cost ~ w*h/750; full screenshot ~1.5k) for spot checks. Transport: Streamable
   HTTP MCP on the device, reached from Claude Code/Desktop on the PC via LAN or `adb forward`
   (unverified on WSA). Optional later: in-app chat panel with the user's own API key.
3. **Settings tools** (bonus): read printer/filament/process presets + propose overrides; saving or
   deleting presets and starting prints always need the user's on-screen confirm (rule 6).

### Orca Cloud (Kotlin port of upstream `OrcaCloudServiceAgent.cpp`)
- `net/OrcaCloud.kt`, `net/OrcaCloudLoopback.kt`, `ui/settings/OrcaCloudSection.kt`.
- Sign-in page `https://cloud.orcaslicer.com/orcaslicer-login`, JS bridge registered as `wx`;
  `get_login_cmd` -> `window.postMessage(login_config)` (PKCE S256, loopback
  `http://localhost:41172..41174/callback`). Code exchange `POST auth.orcaslicer.com/auth/v1/token
  ?grant_type=pkce`. Refresh with 15 min skew; 400/401/403 signs out. Session: AES-GCM with an
  AndroidKeyStore key in `noBackupFilesDir/orca_cloud/session.sec`; the app backup carries it
  decrypted inside its passphrase-encrypted zip (`AppBackup.kt`), plus `orca_plugins/config.json` (r80).
- API `https://api.orcaslicer.com`: plugins `GET /api/v1/plugins/subscriptions`; pull
  `GET /api/v1/sync/pull` (full, no cursor) -> engine `loadCloudPresets` (shared with the Bambu
  sync; cannot delete presets: `remove_users_preset` only touches presets whose user_id equals the
  engine's empty `preset_folder`).
- Upload (user approved 2026-10-10): engine `cloudUploads(userId)` (get_user_presets +
  get_differed_values_to_update) / `markUploaded` (set_sync_info_and_save); `POST /api/v1/sync/push`
  {id, name, content, original_updated_time}. New ids = UUIDv5(ns `f47ac10b-58cc-4372-a567-
  0e02b2c3d479`, "<user_id>/<name>"). 409 reported, never forced; 413 -> will_not_sync; deletes never
  uploaded; presets with another account's user_id (Bambu-synced) skipped.

### Models tab (`ui/models/ModelsScreen.kt`, `net/ModelDownloads.kt`)
- MakerWorld uses a desktop Chrome UA + `BBL-Slicer/v<ver> BBL-Language/en` ("slicer mode"):
  needed for "Open in Bambu Studio" (`bambustudio://open?file=` -> download -> import). In slicer
  mode MakerWorld renders no header; the app adds a links row (handle = `name` from
  `/api/v1/user-service/my/profile`, fetched by the page) and MW_TRIM_JS moves the fixed
  `.global_new` bar from top:60px to 0 (home only; model pages have no strip, don't overlay).
  "Full site" chip = no BBL suffix (header, sidebar, Collections; no slicer import).
- `?modelid=` changes load `<lang>/models/<id>`; downloads go to `cache/downloads`; names from
  page / header / URL, else sniffed (3MF/zip/STL/OBJ). All WebViews need MATCH_PARENT layout params
  (WRAP_CONTENT makes CSS 100vh = 0) and are made by `themedWebView` (app light/dark).
- Untested: MakerWorld sign-in ticket flow, Thingiverse downloads.

### Plugin host surface (what upstream plugins expect; implemented)
- orca.base, @orca.plugin, orca.register_capability, orca.ExecutionResult.{success, skipped},
  capability get_name/get_icon/has_config_ui/get_config_ui/get_default_config/get_config/on_load/
  on_unload/on_message/post_message/get_ui, orca.host.preset_bundle(), orca.host.plugin.storage(),
  orca.host.app_language(); `webbrowser.open` routed to the app. Page JS bridge = upstream
  ORCA_BRIDGE_JS (`src-orca/src/slic3r/GUI/Widgets/WebHosting.cpp`). SlicingPipeline runs only
  at `psGCodePostProcess` on Android (on the exported G-code, in place).
- Pull files from WSA with `adb exec-out "su -c 'cat ...'"` (plain `adb shell` mangles binaries).

### Orca plugins: research (2026-10-10)
- Upstream `src-orca/src/slic3r/plugin` (~14k lines): CPython 3.12 embedded via pybind11
  (`PythonInterpreter.cpp`); capability types PrinterConnection, Pages, Analysis, Importer,
  Exporter, Visualization, Script, SlicingPipeline. SlicingPipeline hooks libslic3r
  (`Print::set_slicing_pipeline_hook_fn`; the engine already has the seam). Host APIs in
  `plugin/host/*`: geometry, model, presets, slicing are GUI-free; ui / app / pages use wx. wx also
  in PluginManager, PluginConfig, PluginResolver, PluginAuditManager, PythonInterpreter.
- Plan: CPython for Android linked into the engine with the NDK + pybind11 (vendored in
  `src-orca/deps_src/pybind11`); Script + SlicingPipeline first. Plugins with native-code wheels
  can't run on Android (pure Python only); upstream installs PEP 723 deps with `uv` (none on Android).
- python.org ships Android tarballs only from **3.14** (latest 3.14.8; upstream bundles 3.12.13):
  `python-3.14.x-aarch64-linux-android.tar.gz` = `prefix/lib/libpython3.14.so` + stdlib (lib-dynload
  .so) + headers + a `testbed` app showing the embedding (stdlib in assets, extracted to files/,
  `PyConfig.home`; libpython*.so in jniLibs). 3.12 for Android only via Chaquopy builds.
- wx-free as-is: CloudPluginService, PluginFsUtils, PluginLoader, PluginHooks, PythonPluginBridge,
  host/{PluginHost,Geometry,Mesh,Model,Presets,Slicing}, pluginTypes/*. wx/GUI to replace in
  `android/core` (submodule stays unpatched): PluginManager (21 refs), PluginConfig (14),
  PluginResolver (9), PluginAuditManager (43, I18N), host/PluginHostUi (84), PluginHostApp (10),
  PluginPages (40), PythonInterpreter (1).

### Task list (user, 2026-10-11; not started unless noted)
- **Auto-save DONE: r94 = run 38202928758 (also carries 077e3ca + 93b776a), installed + verified on
  WSA 2026-10-11:** added temp Plate 2 -> session.3mf rewritten ~60 s later, force-stop + relaunch
  -> Plate 2 restored; deleted it -> next auto-save wrote the clean state (Plate 1, 8 objects).
  More > Misc shows the switch (on) + "60". Untested: switching off, changing the interval.
  More > Misc "Auto-save the plate" switch + seconds (10-3600, default 60; 0 = off; pref
  `auto_save_seconds`). `AppViewModel.autoSave()`: every N s, if scene/presets/overrides/project
  name/active plate changed since the last save and not busy/slicing -> `files.saveSession()`
  (now under a Mutex shared with the onStop save). Desktop equivalent: `MainFrame.cpp:755` (backup
  every 10 s, incremental; ours writes the full session.3mf, so a longer default). Test: edit on a
  temp Plate 2, wait > 60 s, check session.3mf mtime, force-stop, relaunch -> edit kept; then
  remove the temp plate and wait for the next auto-save.

### Other open items

## Moved from HANDOFF.md on 2026-10-12 (r98 + r99 verified, main merge)
- r98 = run 38210814654 verified on WSA 2026-10-12 (temp Plate 2, deleted; user's Plate 1 with 1
  object untouched; app closed via window close): size handles X (20->26, pos.x +3, min side kept)
  and Z (20->25, bottom kept); turn handle -> rot.z 90; builder Undo reverts the turn in one step,
  Redo; a turned shape shows only the turn dot; Customizer "Add a slider" adds dim `lid`
  (draft.json `dims`).
- r99 = run 38211644743 (commit 3283f1c) verified on WSA 2026-10-12: Add -> Shape builder, Box,
  Frame -> amber turn dot + arc fully inside the preview (r98 cut it off at the bottom edge);
  Customizer with no sliders shows `shape_dims_hint`. Closed the editor without adding; a leftover
  Plate 2 (empty, from the r98 session) was deleted via long-press tab -> Delete plate; Plate 1 with
  1 object untouched; app closed via window close (process gone).
- Main merges 2026-10-12 on the user's "merge": both repos merged `claude/handoff-continuation-rseql3`
  into main (Android main was r37 / PR #3). OrcaStudio: main's privacy commit 082b5d4 (handoff
  serial/IP removal) conflicted with the rewritten HANDOFF.md; kept the branch version (it holds
  no serial/IP; IP-like strings in NOTES-*.md are firmware versions).


## Shape builder phase 2a: sketch shapes (2026-10-12, r101, run 38213296936)
- Model `scad/Sketch.kt`: `P2`, `Loop` (corners + per-side bulge = tan(arc angle / 4), positive =
  right of the side's direction = outward on a CCW outline; circle = 2 corners bulge 1; `polygon()`
  tessellates arcs by $fs 0.4 / $fa 4), `Sketch` (loops; `toScad` = union of solid polygons minus
  hole polygons; `START` = 20 mm square). JSON `{"loops":[{"pts":[[x,y]..],"bulge":[..],"hole":true}]}`.
- `Shape.Kind.SKETCH` + `Shape.sketch`; extras `revolve` (0/1), `angle` (revolve, def 360), `top`
  (extrude scale, def 1). Codegen `ShapeDesign.sketch()`: extrude = scale(w/nw, d/nd, h)
  linear_extrude(1) centred drawing; revolve = rotate_extrude of the drawing (x = radius, clipped
  to x >= 0, y shifted to 0), scaled to the box.
- `ShapeController.setSketch/setRevolve/fitSketch`: an edit resets the box to 1:1 and moves pos so
  the drawing stays in place (pos shift turned by rot.z only). `add()` returns the new id.
- Editor `ui/scad/SketchEditor.kt`: full-screen Dialog, `SketchState` (own undo, view scale/origin,
  snap to corners / grid (step from zoom) / level-plumb, hit test corners > side squares > outlines),
  tools Select / Lines / Rectangle / Circle, PickBar (point X/Y, Bend, Straighten, Add a corner,
  Hole switch, Delete). Done = one builder undo step. Palette chip "Sketch" opens it at once.
- WSA test: screenshots via `adb exec-out screencap -p > file` from Bash (PowerShell redirection
  corrupts PNGs). Drags: `adb shell input swipe x1 y1 x2 y2 600`.

## Moved from HANDOFF.md on 2026-10-12 (env notes)
- **WSA -> LAN printer:** WSA shares the PC's address; only its virtual gateway answers ARP, so LAN
  hosts give "No route to host" (Local network access is on; no effect). Fix after each WSA start:
  `wsa-lan.ps1 <printer-ip>` (routes the host via the gateway in table 1002). Ping stays dead, TCP
  works. Verified 2026-10-11: cube printed over LAN (FTPS upload, queued), r69.
- Cloud-only print (no LAN) fails `-2060 upload poll timeout`: server stays "running" (> 2 min;
  obn uploads the full .gcode.3mf as the config file). Patch 0004 tried a longer poll; reverted.

## Shape builder phase 2 rest: edges, hollow, import STL (2026-10-12, r102, run 38214501908)
- Commit 45ac19d (+ clip fix f5840a4). Extras on `Shape`: `edge` (mm), `chamfer` (0/1), `shell`
  (wall mm), `open` (0/1); `Shape.EDGED` / `Shape.SHELLED` kinds. Codegen `ShapeDesign.body()`:
  3D edges = shrink by e + `minkowski` with a sphere (round) or an octahedron (chamfer, hull of two
  $fn=4 cones), translated up e; sketch corners = 2D `offset` chain in `sketch().mods()`; hollow =
  `difference()` with the cavity (sketch: outline `offset(delta = -wall)`), open top = cavity up past
  the top. `Kind.MESH` + `Shape.file` (absolute path) + extras `mx0..mz1` (`Stl.bounds`, binary or
  ASCII); `ShapeController.importMesh` copies the pick to `files/openscad/meshes` (same name and
  length = reuse, else `_2`), placed right of the design. UI: `EdgeEditor` / `ShellEditor` in
  `ShapeBuilder.kt`, palette chip "Import STL" (last chip).
- WSA verified on r102: sketch drawing clipped to its canvas (pan test, screenshot); box Round 3 ->
  668 tris, Chamfer -> 44 tris (14 + 2x3 = 20); Hollow wall 2 (cavity 16^3 at z 2), Open top;
  Import STL (20x10x30 box with min corner 5,5,2) -> centred, at its size, file in `meshes/`, temp
  pick deleted, W 40 -> scale 40/20; sketch Corners 4 + Hollow 2 + Open top; cylinder Round 2;
  sphere Hollow 2. Ctrl+Z (`input keycombination`) undid a delete.
- Found by math, fixed in feaec68 (not built yet): cavity used the outer edge size (walls 2.83 mm
  at edges for wall 2) -> inner edge e - wall (chamfer e - 0.586 wall); open top left an inward lip
  (cavity's rounded top) -> cavity reaches past the top by its own edge; revolved sketch's corner
  offset was applied before scaling to the box -> profile scaled to the box first (`scale([1, d/w, 1])`
  around the rotate_extrude).
- Test tips: chip row scrolls left with `input swipe`, but back right only with
  `input motionevent DOWN/MOVE/UP`; a Del key with no field focused deletes the selected shape.
  Scratch helper `t.ps1` (focus, tap, dump filtered).

## Shape builder phase 2b: sketch constraints + sketch sliders (2026-10-12, r103/r104)
0. **r104 = run 38220409243 (56337ec)**: fixes from the r103 test (r103 passed checks 1,2,4-9 incl.
   feaec68 hollow box). To retest on r104: rect with top fixed 30, right side Equal to… bottom ->
   30 x 30, no red warning; removing a "=1" chip re-solves at once; ⋮ "Fix the length" fixes the
   current value; slider "used N×" counts a sketch length; "Length = name" not clipped.
1. **(r103 details) Test phase 2b on WSA** (build run 38218657886 = 71600d7, includes feaec68). 2b = sketch
   constraints: `Con`/`Side` in `scad/Sketch.kt` (LENGTH value or `dim`, EQUAL, PARALLEL, LEVEL,
   PLUMB; `Sketch.solve` = iterative projection, `pinned` corners stay; `unmet()`; index remaps
   `withoutLoop/withoutPoint/withSplit`; `plus(rect=true)` adds H/V; `START` has them).
   `ShapeDesign.withDims` re-solves sketches whose lengths follow a changed slider (box kept to
   scale, `fitSketch(keepScale)` moved from the controller); used by setDim / withField /
   Customizer `setValue`; `pushToPlate` = `old.withDims(new.dims) == new`. Editor: picked side ->
   Length field (typing fixes), ⋮ menu (Free / Make a slider / Follow / Stop following), Level,
   Plumb, Equal to… / Parallel to… (then tap the other side), remove chips; tags on canvas, red =
   unmet. Solver math checked by a temp JVM JUnit run (6 tests OK; test kept in session scratchpad
   only; gradle's test worker crashes here, run JUnitCore by hand with `cygpath -w` classpath).
   **Test**: rect corner drag keeps a rect; Length 30 on a side; Equal/Parallel pairing; conflict
   warning; Make a slider then move it in the builder dims list and in Customizer (sketch + box
   follow, plate object follows); removeDim keeps the length; undo in editor restores dims.
   Also feaec68: `draft.scad` of a hollow rounded box (open top: cavity edge = edge - wall, cavity
   top = h + 0.01 + that edge) and a resized revolve with Corners.
   Untested in 2a: point X/Y fields, Bend field, Add a corner, Delete corner, Undo, Rectangle tool,
   pinch/wheel zoom, Esc/Enter/Del keys, Top scale, Turn angle, Edit sketch in Revolve mode (axis).
   Untested in r102: revolve Corners, chamfer on cone/pyramid/wedge, edge/wall as sliders.
   Known limit: a sketch length slider only works in the app (the .scad polygon is baked numbers).
- r103 WSA test (run 38218657886): checks 1, 2, 4-9 passed (rect drag stays rect, Length 30,
  conflict warning + undo, parallel on a drawn quad (slopes 0.2 both), Make a slider -> Customizer 40
  -> size [30,40,10] + polygon +-20, removeDim keeps value 40, editor undo removes the slider,
  feaec68 hollow rounded box: `rounded_box([16, 16, 18.01], 3)` at z 2). Failed: Equal with the
  kept side made a false conflict; chip removal didn't re-solve; typing the current length didn't fix it.
- r104 = run 38220409243 (56337ec) fixes: `constrain` falls back to a free solve when the pinned one
  leaves unmet constraints; `unconstrain` re-solves; ⋮ "Fix the length"; "used N×" counts sketch
  lengths; wider slider-name field. **Verified on WSA 2026-10-12**: Equal -> 30 x 30, no warning;
  chip removal + Free the length clear the warning at once; Fix the length; "Length = sketch_1_side"
  readable; "used 1×". Draft left empty, app closed.
- Notes: a sliders' `value` in a dim-bound LENGTH con stays stale (only used as fallback). The
  sketch canvas bottom is covered by the bottom bar when a side is picked (Fit helps). Plate 2 (empty)
  still exists in the session. Temp JUnit test of the solver: session scratchpad only.

## AI tool layer milestone 1 (2026-10-12, Android r106-r108, verified on WSA r108)
- Commits `c1f7c48`, `ae3a01b`, `7dd50e0`. Files: `ai/McpServer.kt` (hand-written HTTP/1.1 +
  JSON-RPC: `initialize` / `ping` / `tools/list` / `tools/call`; `Mcp-Session-Id` issued, unknown id
  -> 404; notifications -> 202; Origin -> 403; no / wrong bearer -> 401; GET -> 405),
  `ai/McpTools.kt` (`design_get`, `design_describe`, `design_set` with validation: kinds, unique ids,
  size > 0, dim names = identifiers, bind targets exist, no new mesh files, no control chars in
  names), `ShapeController.replace` (one undo step via `commit`), `AppSettings.aiTools / aiToolsLan /
  aiToken` (48 hex chars, SecureRandom), More > "AI tools (MCP)" (switch, LAN switch + addresses,
  token show/hide, Copy setup command, New token), `AppViewModel.setAiTools` (bind on IO; restored at
  startup after READY; stopped in `onCleared`). Debug: `setprop log.tag.OrcaMcp DEBUG` logs each request.
- **WSA cause:** WSA's ip rules (`from 127.0.0.1 iif lo lookup 127`, `iif lo lookup 128`) send
  Android loopback traffic to Windows, so `adb forward tcp:8765 tcp:8765` made adbd connect to
  adb's own Windows listener: 2,000+ looping sockets, adb wedged (kill adb.exe to recover). Inbound
  TCP from Windows to a WSA listener (127.0.0.1, loopback0 169.254.x, PC LAN IP) is not routed either.
  toybox `nc` inside WSA can't reach any loopback listener for the same reason.
- Fix: also listen on abstract unix socket `orcastudio_mcp`; `adb forward tcp:8765
  localabstract:orcastudio_mcp`. `LocalServerSocket.close()` doesn't wake `accept()`, so `stop()`
  connects once first (verified: both sockets gone after switching off).
- Verified r108: curl suite (401 / 403 / 405 / initialize 2025-06-18 + session / 202 / bad session
  404 / tools/list / describe + get with editor closed / invalid design_set lists 3 errors / valid
  design_set opened the editor on the draft, preview 40 x 30 x 20 mm, generated code right, toast
  "AI: changed the design (3 shapes)"); builder Undo -> empty, Redo -> 3 shapes (one step each);
  `claude mcp add --transport http` -> `claude mcp list` "Connected"; headless `claude -p` called
  `design_describe` ("3 shapes: Base, Peg, Hole"). Cleanup: temp `orca-test` MCP entry removed, draft
  undone to the original empty design, AI tools + LAN switched off, forward removed, app stopped.
- Untested: LAN mode from another device; restarting the server while a client is connected;
  request > 4 MB / chunked (refused by design); real USB device forward; Copy setup command paste.

## Moved from HANDOFF.md on 2026-10-12 (trim after MCP milestone 1)
- Shape-builder handles: `MeshPreviewView` (`handles()`, `overlay()` -> `PreviewOverlay` points/
  guide/ghost, `HANDLE_VS`), `ShapeController.resize/turn/handles/addDim`, `ScadPreview.kt` listener.
- Flatpak r9 MakerLab tab: user hasn't reported. Weekly upstream bump `upstream/orca-main`: merging
  is the user's call. Optional: obn patch 0001 upstream report (needs OK); cloud
  `POST /v1/user-service/my/task` 403 cause; obn deferred-teardown latch (`agent.cpp:117-129` vs `:330`).
- Sketch untested: point X/Y fields, Bend field, Add a corner, Delete corner, Rectangle tool,
  pinch/wheel zoom, Esc/Enter/Del keys, Top scale, Turn angle, Revolve-mode edit, revolve Corners,
  chamfer on cone/pyramid/wedge, edge/wall as sliders. Sketch sliders work in the app only.

## AI tool layer milestone 2 (2026-10-12, Android r109-r110, verified on WSA r110)
- Commits: milestone 2 + "read tools open the editor" (Android branch, after `7dd50e0`). 17 tools,
  tools/list = 10.3 KB. New in `ai/McpTools.kt`: `shape_add/update/delete/move/align/group/ungroup/
  duplicate`, `sketch_set` (parses `Sketch.of`, solves cons with dims, `fitSketch`, optional revolve),
  `dim_set/bind/remove`, `design_preview` (`ScadController.awaitIdle`, status + size + ERROR/WARNING
  log lines), `design_to_plate` (`UiState.aiConfirm` dialog in `AppScaffold`, `McpTools.answer` via
  `AppViewModel.answerAi`, 60 s = deny, then `render()` + awaitIdle). Each edit = one commit through
  `ShapeController` (`replace(next, sel)`, `withField` now public, new `beside()` shared with add /
  importMesh); "AI: <summary>" toast (`R.string.ai_edit`). Read tools now open the editor on the
  saved draft (a closed editor made haiku stop at "editor closed").
- Verified r109/r110 (script now `~/orca-android-tools/mcptest.sh`, `call <tool> <json>`):
  add (placed beside when no pos), update (pos + extra edge), add hole, group 3 -> group #5 with
  child frames, ungroup back to the same positions, duplicate (+10/+10), move, align x min; dim_set
  new, dim_bind size.x, dim_set 50 -> box 50 wide, shape_update size.x 60 on a bound field -> dim 60,
  dim_remove keeps 60; sketch add + sketch_set triangle with length con 25 -> side solved to 25;
  delete; 7 error cases with clear messages; design_preview "OK ... size 60 x 40 x 15.966 mm";
  design_to_plate Deny -> "Declined by the user", Allow -> placed on Plate 2 as "Shape design.stl"
  (removed again with the plate Undo; Objects back to 1); dialog shows the quoted name (screenshot;
  `ui.ps1` drops text lines containing quotes). Headless `claude -p --model haiku` with the tools:
  read the design, added "Cap" 10 x 10 x 3 on the Pin's top (#8 pos [0,0,16]), previewed 60 x 40 x 19.
- Cleanup: draft restored from `/data/local/tmp/mcpbak` (original empty shape design), AI tools off,
  forward removed, temp `orca-test` MCP entry removed, app stopped.
- Not built: Undo action in the toast, "AI connected" chip. Untested: confirm dialog timeout (60 s),
  `design_to_plate` replacing an existing object, `repeat` / `mirror` / `text` fields over MCP,
  revolve via sketch_set.


## AI tools milestone 3 (verified 2026-10-12 on the user's phone, r114)

- Commit 5b7ee35 (OrcaStudio-Android). `design_snapshot`: new engine call `snapshot` (`OrcaEngine::snapshot`,
  `OrcaProject.cpp`; dispatch key `snapshot` in `EngineCalls.cpp`) loads the preview STL into a temporary
  `Model`, renders with `render_thumbnail`, PNG via `GCodeThumbnails::compress_thumbnail` (flips the bottom-up
  rows upright), base64 via beast. Tool refreshes the preview, reads `ScadController.previewStlPath`, returns
  MCP `image` content + caption. Reuses the `OrcaNative.call` export (CI JNI-export count unchanged).
- `plate_describe`: printer / process / filaments, per-plate objects with on-plate size, slice estimate; reads
  `UiState`, no raw engine calls. `McpTools.Tool.run` may return a content `JSONArray` (image + text).
- Test: `mcptest.sh @phone-token.txt list platedesc describe snapshot` -> 19 tools; plate_describe showed the
  live printer / process / filament; snapshot of the box draft 384 px, 4 KB PNG, upright and shaded,
  caption "60 x 40 x 30 mm". `mcptest_print.py` prints text items and saves image items ($SNAP).


## Moved from HANDOFF.md on 2026-10-12 (evening): phone setup, user reports verified, MCP on a real phone

### Phone test setup
Phone notes: wireless adb, serial = the non-TV entry
in `adb devices -l` (port changes after re-pairing). **Rooted (Magisk), user granted root use**:
`su -c ...` works from adb shell. Plain `input tap` is ignored (ColorOS); tap as root.
`~/orca-android-tools/phone-ui.py <serial> dump|find <re>|tap <label>` (root taps, label dump).
MCP token: read from `shared_prefs/app_settings.xml` (`ai_token`) via `su` straight into
`~/orca-android-tools/phone-token.txt`, never printed; `mcptest.sh @phone-token.txt <steps>`.
r114 (CI 38291985223, all of today's work) is installed on the phone.

### User reports 2026-10-12, verified
   - AMS sync button gone on that detached preset: **verified fixed on the phone r114** (button back). Cause
     confirmed: `inherits` empty -> `base` empty (`OrcaEngine.cpp:202`); fallback to the system preset of the same
     model + nozzle (`PresetController.selectPrinterNow`).
   - Plate type: default was the enum's first value (Cool Plate) in the UI and in slicing; now
     `OrcaEngine::default_bed_type()` = user choice, else desktop `Preset::get_default_bed_type`. Verified on the
     phone: Prepare shows "High Temp Plate" for the A1. More > Plate types dialog **verified on WSA r114** (default radio
     shows in Prepare, hidden plate left out of the picker, slice G-code `curr_bed_type = Supertack Plate`).
   - Answered, no change: MCP token is already in the backup (all `app_settings` prefs, `AppBackup.kt:39,129`).

Done 2026-10-12: merges to main (Android PR #5 at r104, OrcaStudio PR #3); **AI tools milestones 1-2
verified on WSA r110**; **milestone 3 verified on the phone r114** (19 tools, `design_snapshot` PNG upright and
shaded, `plate_describe` live; details in the archive "AI tools milestone 3").

### MCP on a real phone
- **MCP on a real phone (verified 2026-10-12, the user's phone, r110):** the
  `localabstract:orcastudio_mcp` forward works unchanged over wireless adb; 17 tools listed,
  `design_describe` returned live state. Pair via Wireless debugging (`adb pair <ip>:<pairPort>
  <code>`, ports/code rotate per dialog), then `adb connect <ip>:<connectPort>`. The abstract socket
  only binds **after the app is (re)started** with AI tools on (toggling while running isn't enough).
  The phone is rooted: read the token with `su` into the token file (see Next task). Without root the
  user reads it off More > AI tools: the TWO shown parts joined (the "Hide" button sits between them),
  48 hex chars. A real phone doesn't route loopback to
  Windows, so the WSA `tcp:tcp` wedge doesn't apply there.

## AI tools milestone 4: plate edit tools (verified 2026-10-12 on WSA, r115, CI 38294661415)

- Commit "AI tools milestone 4: plate edit tools" (OrcaStudio-Android). `McpTools.kt` "Plate edits": object_transform
  (pos = footprint centre [x, y] on a plate, optional plate to move it there; rot absolute degrees; scale %; pos with
  rot / scale = two engine calls / undo steps), object_lay_on_face (world normal of the face to put down),
  object_auto_orient (one object or all), object_duplicate (1..100), plate_arrange (plate or all). Each calls the
  engine directly, applies the scene on main, toasts "AI: ...", returns plate_describe. plate_describe now lists
  plate size, 1-based object / copy numbers, footprint centre (plate frame = world - plate origin), size, rot, scale.
  More switch text: "...shape designs and the plate".
- Test: `OBJ=2 PL=2 mcptest.sh @wsa-token.txt list platedesc plateedit plateerrors` -> 24 tools; cube on temp Plate 2:
  transform to [60,60] rot 45 scale 150 % (footprint 35.355 both ways, right), lay on face [1,0,0], 2 copies,
  arrange (row at y 128), auto orient, move copy 3 to [30,200]; all 8 error cases rejected before any change; app
  Undo reverted the AI move and 7 more undos returned Plate 2 to empty. Plate 1 unchanged throughout.

## Settings tools (AI tools roadmap 3), verified 2026-10-12 (WSA r117 + r118)
- Android commits after b7d6de2: settings tools (r117) + output fixes (r118). `ai/PresetTools.kt`: preset_list,
  preset_get (diff to the system parent + unsaved edits; keys / all; a printer preset without parent is compared
  with the system preset of its model + nozzle), setting_find, settings_edit (unsaved overrides via
  `PresetController.editOverridesNow`, validated against `OptionDef`: range, enum, bool, int, percent), preset_select
  (process / filament only), preset_save + preset_delete (on-screen Allow via `McpTools.confirm`; delete refuses
  selected presets). `PresetController` gained suspend variants (`savePresetNow`, `selectPrintNow`,
  `setFilamentNow`, `setActiveFilamentNow`, `deleteUnusedPresetNow`).
- Test: `mcptest.sh @wsa-token.txt list presetread presetedit preseterrors` (31 tools), any tool as
  `name:'{json}'`. Save flow on filament slot 1 (edit 215 C, Deny -> declined, Allow -> saved + selected, diff
  shown), delete refused while selected, select back, delete dialog text checked, Allow -> gone. WSA left as found.
- Confirm dialog text isn't in the ui dump: screenshot with `adb exec-out screencap -p` (ui.ps1 -Shot from bash
  wrote UTF-16 text instead of a PNG).

## Shape builder phase 2 gaps: ungroup, rename, re-frame (verified 2026-10-12, WSA r119 + r120)
- Commits (Android): 8334d43 (r119, CI 38302752223), 80c8d0f (r120, CI 38303834136).
- Ungroup (`ShapeController.ungroup`): OpenSCAD chain T(gp + h/2) Rg Mg T(-h/2) T(cp + ch/2) Rc Mc T(-ch/2)
  = T(gp + h/2 + Rg Mg (cp + ch/2 - h/2)) (Rg Mg Rc Mg) (Mg Mc) T(-ch/2); `ShapeDesign.matrix/angles/flip`
  (XYZ angles back out, gimbal lock -> all in Z), mirror flags XOR. Python check 6.5e-13; WSA: turned group
  mirrored Y+Z with a turned child and a turned + mirrored wedge -> 372 preview vertices equal within 0.0008 mm.
- `placed()`: group / ungroup drop a pos / rot dim binding whose value changes (verified: pos.x dropped, size.x
  kept, round trip keeps world bounds). `ShapeDesign.height()` = turn/flip pivot; `bounds()` / `worldBounds()`
  honour a group's mirror; `handles()` gives no handles under a mirrored group (untested in UI).
- Rename: `ScadController.key()` ignores whole-line `//` comments -> no new preview STL on rename (verified).
- Re-frame: `MeshPreviewView.outgrown()` (content past the last frame by 5 %); `content()` = builder boxes, else
  mesh (r119 framed boxes + stale mesh -> too loose). Verified r120: move far -> tight frame; 3 shapes added
  beside -> all in view.
- Test method: MCP (`mcptest.sh @tok 'tool:{json}'`), preview STLs in `cache/openscad/preview` pulled with
  `su -c cat` and compared vertex sets; rename check = no new STL file.

## Shape builder UI tests: keys, align, mirror, sliders (verified 2026-10-12 evening, WSA r120)
- Fresh design (Add -> Shape builder replaced the old test draft): Box 1, Box 2, Sphere 1. Checked through
  `files/openscad/draft.json` / `draft.scad` (the draft is saved ~1-2 s after a change; read after 3 s).
- Keys (real Windows key presses via `keys.ps1`, focus = tap a shape row first): Left/Right/Up/Down = 1 mm,
  Shift+arrow = 10 mm, PageUp/PageDown = Z, H = hole, S = solid, Ctrl+D duplicate (+10/+10), Del, Ctrl+Z,
  Ctrl+Y, Ctrl+Shift+Z, Ctrl+A, Ctrl+G, Ctrl+Shift+G, Esc clears the selection (arrows then do nothing).
- Align menu (9 items, X/Y/Z low/centre/high) with all selected: Y low, X high correct (equal sizes only).
- Mirror menu (along X/Y/Z): toggles the flag per selected shape; codegen `mirror([..])` per axis.
- Sliders: size ⋮ -> "Make a slider" (`box_1_width`, 0..100 step 1), second box "Follow" it ("used 2×");
  Value / Min / Max / Step edits saved; value above max raises max; codegen uses the variable; Remove
  unbinds and keeps the value in the shapes; Ctrl+Z brings the slider and both binds back.
- Child of a mirrored group: outline only, no size/turn handles (un-mirrored: turn handle shows).
- Panel on WSA 1080p: the scroll area is only ~160 px tall (y 600-760); swipe inside it.
- Draft left as a test design (group of two boxes + sphere, slider `box_1_width` = 250).

## Logs and CDP (moved 2026-10-12)
- Logs: `adb shell setprop log.tag.OrcaInput DEBUG` (mouse events), `log.tag.OrcaWeb DEBUG` then
  restart = Models WebView debuggable: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`;
  helpers `cdp.mjs` / `cdplog.mjs` / `ua.mjs` in the session scratchpad (recreate if gone).
  A CDP UA override only lasts while that CDP connection is open.
- CI (`android-apk.yml`): Kotlin check, deps cache, then `liborca_jni.so` reused from cache when
  `android/core`, `android/obn`, `android/scripts`, the toolchain, src-orca and deps are unchanged
  (key `android-core-*`), else rebuilt with ccache (`android-ccache-*`); old caches pruned.

## Sketch editor + auto-save tests (2026-10-12 late, WSA r120, fixes verified r123)
4. **Sketch editor + auto-save tests (2026-10-12 late, WSA r120): passed** point X/Y fields, Undo button, Bend
   field, Straighten, Add a corner, Delete corner (trash), Rectangle tool, wheel zoom, Fit, Enter closes a Lines
   outline, Esc clears the pick, Del + Ctrl+Z (with the editor focused), Top scale (`linear_extrude scale`), Revolve
   + Turn angle 180, Revolve-mode editor (title + dashed axis), revolve Corners (edge 2 renders), cone chamfer
   (renders), edge and wall as sliders (Customizer 4 -> code), auto-save 10 s cadence, off = no save, on again saves.
   **Found + fixed, build run 38314883049 (bc021f4, r121?) to verify on WSA:**
   - 4f4697e: after typing in a number field and changing the pick, the editor had no focus: Del did nothing and
     **Esc closed the editor as Cancel (drawing lost)**. Now a canvas press and each pick change re-focus the
     editor root. Verify: pick a corner, type X, tap another corner, press Esc (pick clears, editor stays), Del.
     Same commit: Bend field 190 dp (label was clipped), pick-bar row scrolls; new edge/wall sliders
     `[0:0.1:10+]` (were `[0:1:100]`), Turn angle slider max 360.
   - bc021f4: auto-save switched off then on after leaving More came back at 60 s, not the set interval. Off is
     now stored as -interval. Verify: set 10, leave More, switch off, leave, switch on -> 10.
   Not testable on WSA: pinch zoom (no touch). Pyramid/wedge chamfer: same minkowski code as the cone
   (`ShapeModel.kt:367-373`), render not tried. Editor draft holds Sketch 1 (revolve) + Cone 1 (chamfer, wall
   slider): fine to replace.
- **r123 (run 38314883049, bc021f4) verified on WSA:** typed X then tapped another corner -> value committed, Esc
  cleared the pick and the editor stayed; Del removed a corner after a Y field edit, Ctrl+Z restored it; Bend label
  fits; new edge slider `[0:0.1:10]`; auto-save off -> pref -10, on again after leaving More -> 10 (reset to 60 after).

## Shape-builder handles: Y, Shift turn, groups, nested child (verified 2026-10-12 late, WSA r123)
- Fresh design via Add -> Shape builder (old test draft replaced); checked through `files/openscad/draft.json`.
- Y handle (adb swipe on the green dot): Box 1 size.y 20 -> 42, pos.y +11 (low side kept).
- Shift = 1° turn: real mouse drag with Shift held (`~\orca-android-tools\mdrag.ps1 x1 y1 x2 y2 -Shift`) -> rot.z 17;
  the same drag without Shift -> +15 (32). WSA mirrors the Windows desktop: Android screen coords = Windows pixels.
- Group (Box 1 turned + Box 2, Ctrl+A, Ctrl+G): group shows only the turn dot; turn -> group rot.z 15. Child of the
  unturned group: all handles, X 20 -> 29. Child of the turned group: outline only. Builder Ctrl+Z undid the turn.
- Nested (Group 5 > Group 3 > Box 2): handles shown; Z 20 -> 31 (bottom kept); turn -> rot.z 45.
- Shape list scrolls with a real mouse wheel (adb swipe didn't scroll it). Plate 1 untouched; app closed via window close.
- Draft left as this test design (Group 5 with Group 3 + Box 3).

## OpenSCAD Save/Open via the picker (moved from HANDOFF.md on 2026-10-12)
- r123 (WSA): Save wrote correct code but named the file "Shape design" (no extension; Android adds none for
  `application/x-openscad`); Open worked (code view, same size) but a builder design came back as plain code.
- d87dcdb, verified WSA r124: save name gets `.scad` (`ScadEditor.kt` `saveName`); a shape design saves its JSON as a
  last line `// OrcaStudio shapes: {...}` (`SHAPES_MARK`, `ScadController.save`) and `openFile` reopens it in the
  builder (Shapes tab, same 45 x 20 x 20 mm) only if the code above still equals `toScad()`; an externally edited
  copy opens as code with the edit applied.
- cd4177c, verified WSA r125: an edited file opens without the stale shapes line (draft.scad had 0); a code design
  saves without the line.
- Known: the picker names a 2nd copy `x.scad (1)` (DocumentsUI, unknown MIME).
- Sketch editor + auto-save fixes (4f4697e, bc021f4) and shape-builder handles verified r123 (see "Shape-builder handles").

## .scad projects from zips (moved from HANDOFF.md on 2026-10-12)
- Found in source (r125): `ModelDownloads.modelFiles` flattened zips (`include <lib/x.scad>` broke, import()/surface()
  data dropped); zips from the import picker / "Open with" weren't unzipped (only Models-tab downloads went through
  `openDownloaded`); a nameless download with OpenSCAD text fell through `sniffExtension` to "stl".
- daf9aba (r126): a zip with any .scad unpacks whole into `<zip>_files/` next to it (not `<zip>/`: the import folder
  has its own `openscad/`, `presets/`), zip-slip guarded, `__MACOSX/` and `._*` skipped; sources sorted by folder
  depth (main-design pick in `FileController.openModels` takes the first one nothing includes); model files a source
  names (import data) stay off the plate. `openModels` unzips every zip and owns the "no model files" toast;
  `openDownloaded` only routes presets and calls `openModels`. Sniff: OpenSCAD keywords and no NUL in the first 512
  bytes -> "scad". Manifest VIEW/SEND filter: `application/x-openscad`, `text/x-openscad`, `application/zip`,
  `application/x-zip-compressed`.
- Verified WSA r126: test zip (`scadzip/design.scad` with `include <lib/shapes.scad>`, `use <lib/util.scad>`,
  `import("parts/peg.stl")`, plus a `__MACOSX` entry) via Prepare > Add > Import: editor opened design.scad with the
  folder tree under `cache/models/scadzip-test_files/`, Customizer found `width`, preview 30 x 40 x 10 mm, 116
  triangles (box + hole + peg), temp Plate 2 stayed empty; plate, zip deleted after.
- Note: ScadDeps packs only included .scad text into a plate object, not import() data files (pre-existing).

## Thingiverse .scad download (verified 2026-10-12, WSA r126)
- "Customizable Card Box" (thing with the Customizer badge), Download all files: the zip arrives nameless
  (`cache/downloads/<uuid>.zip`, copied to `cache/models/`), unpacked to `<uuid>_files/` (files/, images/, README,
  LICENSE); the editor opened `Customizable_Card_Box.scad` (Customizer 31 params, preview 68.6 x 68.1 x 96.8 mm,
  18,974 triangles in 1.1 s); its 4 pre-rendered STLs (not referenced by the .scad) went on the plate next to the editor.
  Same flow re-checked on r127 with ad blocking on. Test plates and files deleted.

## Models tab ad blocking (f138655, verified 2026-10-12, WSA r127)
- User asked to block Thingiverse ads. Thingiverse runs Raptive (AdThrive) plus ~40 header-bidding hosts (found
  via CDP `performance.getEntriesByType('resource')`). `net/AdBlock.kt`: `shouldInterceptRequest` in
  `ModelsScreen.kt` returns an empty response for those hosts (parent-domain match); `HIDE_JS` (onPageStarted +
  onPageFinished) adds a `#orca-adblock` style hiding `[class*="AdCard__adCard--"]`, `AdBanner__adBanner--`,
  `Ad__ad--`, `.adthrive-ad`, `.adthrive-comscore`, kargo, `.tv-ad-free-cta`, adsbygoogle / GPT slots.
- Verified r127: only `ads.adthrive.com` is requested (answered empty), no bidders load; home, search and thing
  pages show no ad elements (the one visible iframe on the thing page is its own YouTube embed); Download all
  files still opens the .scad; MakerWorld home and Printables home load normally.

## Preset re-link + nozzle / my filaments verified (moved from HANDOFF.md on 2026-10-12)
- Preset re-link: "Bambu Lab A1 0.4 nozzle -No cut No vibe" was detached (`machine/base/`, `"inherits": ""`), so only
  generic process / filaments were compatible. Fix (user OK'd): `inherits` -> "Bambu Lab A1 0.4 nozzle", `.info`
  `base_id` = the sibling linked preset's (system A1 id GM030), both files moved to `machine/`, `base/` removed
  (`cat >` over the old file keeps owner/SELinux, then `mv`). Backups: `~\orca-android-tools\preset-backup-2026-10-12`
  (WSA) and `...-phone` (phone, `machine.tar` + extracted). Verified WSA (listed, selectable, Bambu PLA, "0.20mm Standard
  @BBL A1", G-code keys identical) and phone r114 (listed with "0.20mm Standard @BBL A1" + Bambu PLA Basic).
- Match the installed nozzle, phone r114, printer online: More shows "Bambu Lab A1: 0.4 mm"; printer picker vendor
  presets 1 (A1 0.4) with the switch on, 4 (0.2/0.4/0.6/0.8) off; selecting A1 0.2 with the switch on shows "The printer
  reports a 0.4 mm nozzle; this preset is for another size." + "Use Bambu Lab A1 0.4 nozzle" (works).
- Use my filament presets, phone r114: picking "_BambuCalibrated PLA Basic @BBL A1 (Sunflower Yellow)" in slot 1 saved
  `my_filaments` {"Bambu PLA Basic @BBL A1": that}; "Sync filaments from printer" kept it in slot 1; picking the system
  preset cleared the map to {}. The one-user-preset fallback filled both PLA Matte slots with the user's only Matte-based
  preset. Switching printer presets runs an AMS sync (plate went 1 -> 4 slots); the plate was restored to 1 slot
  (Bambu PLA Basic, printer "-No cut No vibe", 0.20mm Standard) after the test.
- Merge: Android main = r127 (PR #7, merge commit 5e2a483, branch `claude/merge-r127` at f138655).

## Filament picks per AMS tray + element blocker verified (moved from HANDOFF.md on 2026-10-12)
- **Tray picks (99264ee, 976f605, 54f57c3), verified on the phone r132 + r134.** User (2026-10-12): the "only user
  preset from that system preset" fallback was wrong (put "MATTE BLK+ELEGOO WHITE MIXED" in both Matte slots);
  never-picked trays must get the printer's Bambu preset (RFID is authoritative), the user's own picks stick, and the
  printer overrides them when the spool changes. `FilamentSlot.source` = `TraySource(key = serial/ams/slot,
  loaded = "filament_id #color", synced = sync's preset)`, persisted in `filaments:<printer>`;
  `AppSettings.trayPicks` (pref `tray_picks`) key -> (loaded, preset), set by picking / saving in a synced slot,
  dropped by picking the sync's own preset or when the tray reports something else. `my_filaments` / `myFilament`
  removed. More switch "Keep my filament picks per AMS slot" (pref `prefer_my_filaments`, default on).
  Phone r132: sync -> Basic/Matte/Matte/PETG (no mix); A1 pick kept by sync and after restart + sync; picking the
  sync's preset -> `tray_picks` = {}. Bug: the session 3MF has no tray sources, so a pick right after a restart wasn't
  remembered and saved sources got wiped. 976f605 (`selectPrinterNow` restores the saved source when the slot still
  holds the same preset) had no effect alone: `FileController.openProjectFile` set the slots again afterwards;
  54f57c3 keeps the sources there. **Verified r134:** after restart, slot 2 pick -> `tray_picks` has `/0/1`; sync keeps
  it. Plate restored to 1 slot "Bambu PLA Basic @BBL A1", picks cleared.
  Test notes: a quick HOME + force-stop can kill the onStop session save (leftover `session.tmp.3mf.tmp`) and restore
  an older session. Filament picker: search + `input text` raises the keyboard; tap items by `phone-ui.py find` bounds.
- **Element blocker + ad-block menu (e4d75ab, a7e63d2), verified on the phone r129/r131.** Models tab three-dot menu:
  "Block ads" (`AdBlock.setEnabled`, pref `adblock_enabled`, all WebViews; reloads), "Block an element"
  (`AdBlock.PICKER_JS`: tap picks; long-press / right-click = `contextmenu` picks unless text is selected; bar
  Smaller / Larger / Block / Cancel), "Blocked elements (n)" (per-element X, Undo all). Rules: pref `adblock_rules`
  JSON site -> selectors, applied by `AdBlock.applyJs` in every `AdBlock.Client`; hashed `Foo__bar--hash` classes match
  by prefix. Verified on a Thingiverse banner: pick + Larger + Block hides it live, survives restart; X / Undo all
  bring it back; Block ads off reloads and greys out "Block an element"; long-press on text keeps normal selection.
  Bar fix a7e63d2 (hint "Tap what to hide"): all buttons fit (r131). Thingiverse keeps an empty strip (site layout).

## Shape builder by touch on the phone (verified 2026-10-12 late, phone r134)
- Temp Plate 2, Add -> Shape builder (the phone's old draft was backed up to
  `~/orca-android-tools/phone-draft-backup-2026-10-12` and restored after; Plate 2 deleted via long-press tab).
- Phone layout: preview, Shapes / Code / Customizer tabs, palette row (scrolls by swipe), toolbar and Add to plate all fit.
- Handles by touch (root `input swipe`, checked in `draft.json`): red 20 -> 30 W with pos.x +5 (min side fixed), green
  20 -> 48 D, blue 20 -> 31 H, amber rot.z 45 (15° steps); body drag moved pos 5,0 -> 22,4; Undo x4 back to 20^3 at 0.
  A turned shape shows only the turn dot (by design, `ShapeController.handles`).
- Chamfer 2 mm: pyramid 18 -> 50 tris (with the box), wedge -> 82 tris; both render chamfered inside their 20 mm box.
- One-finger orbit and two-finger pinch zoom via raw events (`sendevent` on the touchpanel, 16 units per px):
  `pinch.sh 640 1000 200 560` zoomed in. A pinch starting at a 160 px span did nothing: Android's minimum scaling span
  (`ScaleGestureDetector`), not an app bug.

## Zip variants off the plate (verified 2026-10-13, phone r136 + r138)
- Item 5's open question (should a Customizer zip's STLs land on the plate?) was left to Claude ("skip the zip").
  Decision: a zip with a .scad design keeps its other mesh files (stl/obj/3mf/step/stp/amf, `VARIANT_EXTENSIONS` in
  `FileController.kt`) off the plate; a message bar (`UiState.messageAction` / `MessageAction`, snackbar Long with an
  action) offers **Add** (`FileController.addVariants`). Loose files imported next to a .scad still go on the plate.
- a423ee3 (r136), phone: `scadzip-test.zip` (`scadzip/design.scad` with a `/* [Size] */` group + unreferenced
  `variant_40mm.stl`) via Add > Import: editor opened, Customizer 2 params, plate stayed 2 objects; Add -> 3. Found:
  editor stayed over the plate on narrow screens; "1 ... models".
- bb127de (r138): `scadHidden` moved from `rememberSaveable` in `AppScaffold` to `UiState` (Add folds the editor,
  `ScadController.openFile` unfolds it, `AppViewModel.setScadHidden`); `plurals/scad_zip_variants` (en, de).
  Phone: message "The zip's ready-made model is not on the plate.", Add -> plate shown with the variant selected,
  OpenSCAD chip reopens the editor; test variants deleted (Delete key = `input keyevent 112` as root), plate back to 2.
- `phone-ui.py tap` falls back to a plain `input tap` where `su` is missing (Chromebook ARC).

## Big-screen pass (moved from HANDOFF.md on 2026-10-13)
- r138 on the Chromebook (ARC, 1920x1080, app area ~1813x948): Prepare, OpenSCAD editor beside the plate, zip open +
  variant Add (lands on the plate, editor stays open beside it), shape builder (whole panel scrolls; cramped at this
  height, "Hide preview" helps), Models tab, More page, filament picker dialog, settings editor: all fine.
- Found: Device page cards stretched across the window; Customizer sliders with >20 steps drew a solid row of tick dots.
- r140 (5410405 "Wide screens", `ui/device/DeviceScreen.kt`, `ui/scad/ScadEditor.kt`: tick colours transparent when
  count > 20): verified on the Chromebook 2026-10-13 (run 38358229970, `install-build.ps1 -Device <ip>:5555`):
  Device page Job/Temperatures left, Loaded filaments right; scadzip-test width slider shows no dot row.

## Thingiverse downloads on the phone (verified 2026-10-13, phone r142 + r143)
- Moved from HANDOFF.md item 5 on 2026-10-13: .scad zips (daf9aba) verified WSA r126; zip variants off the plate
  behind Add (a423ee3 + bb127de) verified phone r138 (section "Zip variants off the plate"); "Open with" a zip (VIEW
  intent, MediaStore URI + read grant) verified on the Chromebook r138.
- Found on r140 (phone): with Block ads on, every Thingiverse download failed ("Error downloading file"). CDP showed
  Thingiverse's Sentry adding `sentry-trace` / `baggage` to the `/download:<id>` XHR; its redirect to
  cdn.thingiverse.com then fails the CORS preflight. With Block ads off no such header is sent (the ad stack's XHR
  wrapping apparently hides Sentry's patch) and the download works. Fix ec86442 (r142): `AdBlock.applyJs` patches
  `XMLHttpRequest.prototype.setRequestHeader` to drop both headers (also skips the style insert when the document has no
  root yet). Verified r142: single STL, single .scad, and "Download all files" all download with Block ads on.
- "Download all files" on Customizable Card Box (thing:2404395), real tap, r142: the page builds the zip itself
  (~20 s), nameless `<uuid>.zip` -> editor `Customizable_Card_Box.scad`, Customizer 31, preview 68.6 x 68.1 x 96.8 mm,
  18,974 tris; the Add offer showed; the 4 STLs stayed off the plate.
- Found on r142: the single .scad (Files page, blob link `<a download="Customizable_Card_Box.scad">`, clicked with
  `a.click()` while connected) was saved as `<uuid>.stl` ("Loading of a model file failed"): the DownloadListener
  gets no download attribute for blobs, and the file's >512-byte comment header defeated the sniff. Fix 4d55156 (r143):
  `BRIDGE_JS` records `a.download` for blob links (`HTMLAnchorElement.prototype.click` hook + capture click
  listener) in `window.__orcaNames`; `orcaNative.blob(url, data, name)`; `sniffExtension` reads 16 KB (NUL check on
  the first 512 bytes). Verified r143: saved as `Customizable_Card_Box.scad`, editor opens, Customizer 31.
- Test tools: `cdpnet.mjs <secs> [url part]` (network / console / failures) and `cdpeval.mjs "<js>"` in the session
  scratchpad (recreate if gone); WebView debugging via `setprop log.tag.OrcaWeb DEBUG` + app restart, then
  `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`. Temp Plate 2 deleted after each run.

## UI batch WSA checks (moved from HANDOFF.md on 2026-10-13)
- **WSA r144 checks passed (2026-10-13, landscape 907x481 window only):** Layout lab options, More order, Smooth PEI
  label, profile update chip installs (user tapped it), filmstrip + label + plate menu, add/delete plate, double-tap
  focus, taps after a tooltip long-press, tool column scroll with an object selected, builder main canvas, editor
  resize, slider draft chip (66 mm mid-drag, 91 mm on release), ghost on plate (translucent at the plate centre).
- **Verified WSA r146:** lab size sliders (text, interface, floating controls; they multiply; reset to 100 %), panel
  drag resize + snap-to-tuck + drag back out, tool column arrows (tap scrolls, up/down show only when needed), pinned +
  after the strip, builder slider follows the finger (6e45548).
- **Verified WSA r149 (0d2bb7b, Models infinite scroll, via CDP):** Thingiverse home (Page 2: 23 cards, images load)
  + search (Pages 2-3); Back from a page-2 model restores both pages + the exact scroll spot; menu switch off = script
  not injected (pref `models_infinite_scroll` false), back on. Printables (search + /model listing) scrolls natively
  with no pager, so the script stays out. CDP: `setprop log.tag.OrcaWeb DEBUG` + restart, forward
  `localabstract:webview_devtools_remote_<pid>` (from `/proc/net/unix`), Node 24 has a built-in WebSocket
  (`cdp.mjs` = Runtime.evaluate on the first page target).

## UI batch builds r144-r152 (moved from HANDOFF.md on 2026-10-13)
**Big-screen pass done** (r138 + r140 "Wide screens" 5410405, verified Chromebook 2026-10-13; archive "Big-screen
pass"). Chromebook has r140. r140 merged to main (PR #10, 2026-10-13).
**UI refinement batch (user feedback 2026-10-13).** r144 = f7f4d57 (run 38382691511). r145 (6e45548) cancelled. r146 = a2d3148 (run 38385668787) installed on WSA. r147 = 6e645cb (run 38387270397, on WSA):
filmstrip + Slice button in one bottom row (column layout) instead of a fixed 190 dp gap; still a sliver of one thumbnail
at a ~330 dp canvas. **r148** = 9f9978a (run 38388490237, installed on WSA): under 360 dp beside the column the strip
gets its own row above Slice: verified WSA (panel widened, 5 plates, scrolls to active, + pinned). WSA now has 3 plates
(plates 2-3 not mine: the user's?). **r149** = 0d2bb7b (run 38407026995, installed on WSA): Models tab infinite scroll (`MORE_JS` in ModelsScreen.kt,
menu switch "Infinite scroll", pref `models_infinite_scroll`, default on): verified WSA (archive "UI batch WSA checks");
untested on the phone. WSA's Recent files has AllPoses.3mf (could test fd71b33 there, but opening it replaces the WSA
project: ask first). **Phone pass 2026-10-13 (r150 = 31f6b58, run 38459048183, on the phone):** verified portrait: tooltip
shows on long press and the next tap still works; tool column fits; tabs / filmstrip (own row above Slice, scrolls to
active, + pinned) / chips (31f6b58 fix: scrolls to the new plate, + pinned, bar colour); fullscreen in portrait hides the
bars (leaves the cutout strip: `safeDrawingPadding`); landscape immersive with rail + canvas + panel. Phone restored: lab
Tabs, fullscreen off, my plate 9 deleted (plates 1-8 were there). Phone auto-rotate restored. Plate label default is off (`plateLabel = false`).
**r151 = 49e4cdf (run 38461067340, untested; user asks 2026-10-13):** plate tabs show only numbers; landscape drops the
cutout padding (rail moves into the camera strip, content pads its End side; check the camera hole vs the rail's
Models icon, hole at y 597-675 px on the left in rotation 1); whole SwitchRow toggles. On the phone: number tabs and
row toggle verified; the rail still kept the strip (its own default insets) -> **0afd458** (r152, run 38462258290,
on the phone: rail is 80 dp now, canvas gained the 47 dp strip). User 2026-10-13: the Models **icon runs into the camera hole**
(label visible) -> **r153 = 4dac2f4** (run 38465859177, untested): `CutoutAwareColumn` (AppScaffold.kt) stacks rail items and
moves any item overlapping a cutout rect below it (hole y 597-675 px, x 0-141 px in rotation 1; phone 1272x2772, density 476).
Check on the phone in landscape both rotations.

## Import/arrange fix verified on WSA r154 (moved 2026-10-13)
Bug (WSA r153): Recent files > AllPoses.3mf (8 poses on plate 3), then VIEW intent on `/sdcard/Download/swatch2.3mf`
(2 plates, 3 beeper-sized objects; MediaStore id 1000460178) into the filled plate 3 -> both beeper3.stl floated right of
plate 3; Duplicate on a filled plate put the copy half off the bottom edge. Cause: libnest2d `finalAlign`
(src-orca nfpplacer.hpp:1069) shifts new items by the centre of the whole pile *including fixed objects*.
Fix **eac2d13**: `params.do_final_align = fixed.empty()` in `arrange_plate` (like upstream FillBedJob). r154 = run 38470262650.
Retest WSA r154: both beepers landed on plate 3 beside the poses, Assembly on new plate 4 (nothing off the plates);
Duplicate of a beeper on filled plate 3 put the copy left of the poses, on the bed, no overlap; Arrange still packs and
centres the plate. WSA session backup (`/data/local/tmp/session-backup.{3mf,json}`) restored afterwards (cmp same).
Gotcha: tapping the tool column's "Scroll up" cue after it reached the top hits Undo (cue sits at the Undo button's edge).

## keep_layout import verified on WSA r154 (moved 2026-10-13)
fd71b33 `load_models` (OrcaScene.cpp:540-586): a single placed file onto an *empty* plate keeps its layout unless an
object lands off every plate, then it arranges. Test: session backup (3 plates), Add plate -> empty plate 4 active, VIEW
intent on `/sdcard/Download/swatch2.3mf` (2-plate 3MF, MediaStore id 1000460178). Result: Assembly + both beeper3.stl
packed on plate 4, nothing beside the plates, no extra plate needed. Session backup restored afterwards (cmp same),
app force-stopped.

## Plate organisation verified on WSA r156/r157 (moved 2026-10-13)
User's ask (2026-10-13): empty plate deletes without asking; long press an inactive plate in tabs / filmstrip -> pick
several, delete (dialog only if one holds objects); Arrange moves floaters onto the nearest plate.
- c315432: native `delete_plates(vector)` (OrcaScene.cpp, one undo step; `deletePlate` call takes `plates` or `plate`),
  `Engine.deletePlates`, `SceneController.deletePlates` keeps the active plate (index minus deleted ones before it).
  `PlateMenu`: empty plate (`scene.objectsOn`) deletes at once. `PlatePicks` + `plateClicks` (combinedClickable, haptic)
  in `OverlayParts.kt`; `PlateRow` holds the picks (cleared when the plate count changes; active plate dropped; Back
  clears). `OrcaEngine::arrange(plate)`: floaters (off every plate) go to the nearest plate by distance to its rectangle;
  other plates get them via `arrange_plate(p, &floaters)` (their objects fixed), the arranged plate takes its own along.
- 8d70911 (r157): r156 put "Delete n plates" + close in the add slot; the wider end shifted the plates under the finger
  and a next tap hit Delete. Now the add slot (kept invisible, `clearAndSetSemantics`) is covered by a same-size close
  button and the delete button floats in a `Popup` (custom `PopupPositionProvider`: above when the row aligns Bottom =
  filmstrip, below otherwise), non-focusable so plate taps pass.
- WSA tests (session backup, temp plates): empty plates deleted without a dialog; picked plates 1+2 -> "Some of the picked
  plates hold objects" dialog, Cancel kept them; Back left picking; active plate kept after deletes; r157 popup tap deleted
  plates 2+3, plates didn't shift on long press. Arrange: MCP `object_duplicate` x2 + `object_transform` (pos [330,128] on
  plate 4, [-80,128] on plate 1) made 2 floaters; `plate_arrange {plate:4}` put one beside copy 1 on plate 1 (copy 1 stayed
  at [128,128]) and one at plate 4's centre. MCP helper: PowerShell job POSTing to `http://127.0.0.1:8765/mcp` with the
  `ai_token` pref as Bearer (never printed). Session backup restored (cmp same), app force-stopped.

## r159 verified on WSA (2026-10-13)
Build r159 = b4a0789 + 0e8f7c2 (run 38481343757). Filmstrip layout: added a cube on plate 2 and a cylinder on plate 3
(Add > Shape), plate 3 active; long press plate 1 + tap plate 2, "Delete 2 plates" (popup above the row's end) ->
"Plate 1 of 1", Objects (3), plate 3's cylinder on the remaining plate. Undo -> "Plate 3 of 3" (0e8f7c2: active plate
restored; was plate 1 before). Redo, Add plate x2 -> each new plate picked up one floater (cube on plate 3, the old
plate-1 object on plate 2), so the two floaters had separate spots. Tabs layout: picking 1+3 put "Delete 2 plates"
right of the stop button, vertically centred, Undo uncovered (b4a0789). OpenSCAD editor (shape builder draft): handle
dragged 1008 -> 850, panel title x 1153 -> 1014; Preview (confirmed "This plate has not been sliced yet") and back to
Prepare kept 1014 (0e8f7c2 `ScadEditorSize`). Filmstrip first plate not clipped at rest (clip seen earlier not
reproduced). Lab back to filmstrip, session backup restored (cmp same), app force-stopped.

## G2b plate badges verified on WSA r160/r161 (moved from HANDOFF.md on 2026-10-13)
Started before the user's lab picks (user's choice, 2026-10-13). Commits 314bbcd (r160, run 38485569529) + d8204a3
(r161, run 38486763547). Textured quads on the bed (`render/PlateBadges.kt`, atlas built in Compose by
`ui/components/PlateBadgeAtlas.kt` from Material icons + bold digits, `PlateRenderer.uploadBadges/drawBadges`, shader
`Shaders.BADGE_VS/FS`, premultiplied blend): per plate the number at its bottom right (active = primary colour) and, in
Prepare only, delete (only with >1 plate) / arrange / menu icons down its right side (desktop layout,
`PartPlate::calc_vertex_for_icons`), size 0.1 x bed depth, gap 0.02. Taps: `PlateView` ACTION_UP -> `renderer.badgeAt`
-> `PlateListener.onPlateBadge` (number = focus plate, delete, arrange = focus + arrange, menu = focus + `PlateMenu` at
the tap). Badge taps within the double-tap timeout are ignored, and `onDoubleTap` does nothing when its first tap hit a
badge (r160 bug: after deleting the last plate the view re-framed and the 2nd tap focused plate 2).
Verified WSA r160: perspective + top view look, numbers on all plates, number tap focuses, delete, arrange, menu at the
tap, Preview = numbers only. Verified r161: fast double tap on the last plate's delete deletes one plate and the new last
plate stays active; badges survive background/resume. Testing notes: `wheel.ps1` zoom didn't work that day; tap the
view then `O`/`I` keys (keyevent 43/37) zoom out/in; View > Top for precise badge taps.

## Phone pass r161/r162 + UI batch details (moved from HANDOFF.md on 2026-10-13)
Phone (portrait 1272x2772, root), r161 then r162 (506e1df), user's PiP video was over the top right at first:
- G2b badges: render beside every plate; menu badge opens the plate menu; real rotation (`settings put system
  user_rotation 1/0`, auto-rotate off) keeps badges + reframes; landscape immersive. Badges are ~70 px (~23 dp) at plate
  zoom: tappable but under the 48 dp guideline (fixed 10 % of the bed, `BADGE_SIZE` in PlateRenderer.kt:15).
- New project (r162) -> 1 plate, arrange + menu badges only (no delete). Same on WSA r161 after deleting plates.
- WSA r161: badges survive Home + return, Device tab round trip, `am task resize` to 412x720 and back. Rotation only calls
  `onSurfaceChanged` (manifest `configChanges`), so it never makes a new EGL context; a true context loss on the same
  renderer had no repro.
- 20 plates in tabs (auto-scroll to the new plate, + pinned, scroll back + select) and filmstrip (row above the Slice
  button, no overlap; plate 1 not clipped after scrolling back from far plates).
- Double tap on an inactive plate focuses it. Group pick (long press 7, tap 8) -> "Delete 2 plates" -> undo restores.
  The "Delete n plates" button is missing from the uiautomator dump (tap by screenshot coords).
- Tooltip: long press shows "Add" (M3 plain tooltip, capture with a background `input swipe x y x y 3000` + screencap),
  doesn't fire the action, next tap works.
- Opacity slider (45 %) makes the controls see-through. Floating controls slider max = 120 %.
- Tool column: fits in portrait even at 120 %; in landscape it scrolls by swipe and by the cue arrows. With the bottom
  sheet pulled up, the sheet covers the column (by design: the sheet overlays the 3D view).
- Default plate type pinning: Textured PEI on plate 6 -> plate 2 kept Smooth PEI, a new plate got Textured PEI; set back
  (pref `preferred_bed_type` = "High Temp Plate").
- OpenSCAD Customizer (scadzip-test.zip via VIEW intent): slow drag -> mid-drag slider 45, draft 44 mm + "Draft while
  dragging" chip; release -> 56 full render.
- Models infinite scroll (0d2bb7b) on the phone: Thingiverse home reached "Page 10".

UI batch builds detail kept from the hot file (lab = `core/LayoutLab.kt`, prefs `layout_lab`): plate switcher tabs /
filmstrip / chips, "Plate n of m" label (default off), opacity slider (`LocalOverlayAlpha`), portrait tool column,
fullscreen in portrait, OpenSCAD view canvas / ghost; a2d3148 text size / interface size (root `LocalDensity` override in
`AppScaffold`) / floating controls sliders applied on release; wide panel edge drag (`lab.panelWidth`, min 300 dp, < 200 dp
= tuck; keep when removing the lab); `ScrollCue` arrows. More order: Layout lab, Recent files, Calibration, Printers &
presets, then collapsed sections; daily profile check (`lastProfileCheck`) -> `UpdatesChip`. "High Temp Plate" shown as
"Smooth PEI Plate" (`Translator.plate`); `PresetController.rememberBedType`. Tooltips (`Tip`); double tap
(`PlateRenderer.plateAt`, `PlateListener.onPlateDoubleTap`); OpenSCAD ghost = type 98 mesh, draft `$fn = 16` after 40 ms,
`ScadController.endDrag`. Plate delete -> upstream behaviour (2db1d53): objects move to free grid spots after the last
plate; b4a0789 tabs delete button; 0e8f7c2 undo restores the active plate.


## Moved from HANDOFF.md on 2026-10-13: UI batch r144-r163 verified + merged (PR #12)
Big-screen pass verified + merged (r140, PR #10). UI batch (r144-r162) verified on WSA and, **2026-10-13, on the phone**
(r161/r162: badges, rotation, 1 plate, 20 plates, filmstrip, double tap, group delete + undo, tooltips, opacity, tool
column, plate-type pinning, Customizer drag, Models infinite scroll; archive "Phone pass r161/r162").
Phone now has **r163**; session = test downloads (contents don't matter, user 2026-10-13); its old 8-plate session is in
`/data/local/tmp/phone-session-backup.{3mf,json}`. Lab + auto-rotate put back after the tests. WSA session backup:
`/data/local/tmp/session-backup.{3mf,json}` (restored). **4b3bd99 (r163) verified on the phone**: MakerWorld "Download 3MF"
now saves `Plier_Racks_Ikea_Skadis_Version.3mf` (was "…3mf_filename__utf-8__….3mf"); Printables STL name clean.
**Merged to main 2026-10-13 (r163, PR #12).** Chromebook (r163 installed, session = 1 cube on 1 plate, restored):
wide layout, plate badge/icons and **fd71b33 add_plate floater skip verified** (cube dropped in plate 2's spot stayed put).
**Next:** rest of the Chromebook checks (switcher variants, Layout lab, many plates), the user's lab picks. Seen: a 3mf
(keep_layout, 3 plates) landed on plate 1 over the STL already there: asked the user 2026-10-13, no answer yet.
Adb on the Chromebook can't zoom the canvas (`input mouse scroll` did nothing); tuck the panel for room.
Chromebook r163 (2026-10-13): Tabs / Filmstrip (numbered boxes, shares the Slice row) / Chips switchers, Layout lab, wide panel,
plate badges and plate icons look right; fd71b33 add_plate floater skip verified (cube dragged into plate 2's future spot,
Add plate left it near plate 2's left edge instead of centring it). Session restored with undo.

## r166 OpenSCAD plate view + plate camera (moved from HANDOFF.md on 2026-10-13, merged PR #13)
**d8f3e98 + bb47717 (r166) verified on the Chromebook 2026-10-13** (wide layout): eye / eye-off; ghost on the empty plate
with other objects hidden; plate view frames the design's plate; zoomed out (key `O` over adb) a tab tap keeps the camera;
editor on a full single plate adds plate 2, removes it on close if empty, keeps it once the design is placed. Session restored
(1 cube, 1 plate). Code: `state.scadOnPlate`, `ScadPlateToggle`, `PlateRenderer.plateInView`, `showPlate`, Viewport
`savedCamera` (restored in `remember(v)`, not the factory), `ScadController.useEmptyPlate()`.
**Phone r166 verified 2026-10-13** (narrow layout): eye button top right in the design view; eye-off sits top right under File in
plate view; ghost on plate 1 replaces the object being edited ("Update on plate" mode); "Show plate" (folds the editor) hides the
ghost and shows the real object, the OpenSCAD pill brings the editor back still in plate view; landscape rotation (wide layout) and
back keep an orbited camera. Session unchanged (1 object, 1 plate); backup `/data/local/tmp/phone-r166-session.3mf`.

## Moved from HANDOFF.md on 2026-10-13 (r168 merge)
- r168 (commit 83820be "OpenSCAD editor: the phone's fold button reads Hide editor"): fold button label "Hide editor",
  icon VerticalAlignBottom (looks like a download arrow; user told). Folds + reopens as before. Verified on the phone
  2026-10-13; merged to main via PR #14 (383b35a) on the user's `/next-merge`.

## Moved from HANDOFF.md on 2026-10-13 (trim before r170)
- Verified history of hot items: nozzle match verified phone r114; filament picks per AMS tray verified phone r134.
  .scad zips + Thingiverse downloads verified WSA r126, phone r138/r142/r143, Chromebook r138 ("Thingiverse downloads on
  the phone"). Ad blocking f138655 (Models tab) verified WSA r127 + phone r134; Thingiverse download fix ec86442 verified
  phone r142; 08bdf0b (all app WebViews use `AdBlock.Client`) verified WSA r128 (Device page, Models tab).
- Merges 2026-10-13: r144-r163 = PR #12, r166 (OpenSCAD eye + plate camera) = PR #13, r168 ("Hide editor") = PR #14
  (main 383b35a from work branch 83820be, user's `/next-merge`).
- WSA phone view (back-burnered 2026-10-13, reference):
   phone, WSA in its normal window as before). Kept for reference: `sh ~/orca-android-tools/phone-view.sh`
   (launches + `am task resize` to 412x720 dp portrait, sw412 "nrml port"); `... land` = 720x412. WSA display is
   1280x720 px at 160 dpi, so 720 dp is the max height (a real phone is ~900 dp). Task ID changes per launch (script finds it).

## r170 section view (verified on the Chromebook 2026-10-13, run 38503903000, Android 2ea77b3)
- Travel lines (LINE shader) and retract/seam markers (POINT shader, moved off screen in the VS) are cut by the section
  plane; beds, measure and cut-plane overlays draw whole (`setSection(program, on = false)` in `PlateRenderer`).
- Picks (tap, long-press menu, paint) go through `SceneController.pick`: the ray starts where it enters the kept side
  (`SectionView.rayStart`) and hits on the cut-away side are dropped (`SectionView.kept`); Viewport passes
  `v.section.current` (the plane last handed to the renderer).
- Chromebook test (1 cube): at 0.50 a tap on the cut cube selected it; at 0.98 (cube fully cut away) the same tap
  deselected (before: selected via the hidden front face). Preview with travels/retractions/seams on: at 0.50 the front
  travel line and near-side markers are gone, far-side ones and the bed stay; at 0.01 all show. Restored after (section
  closed, chips off, session unchanged).
- Earlier test plan text:
  **r170 = Android 2ea77b3 (run 38503903000): section view clips travels + retract/seam markers, picks skip the cut. Built, UNTESTED.**
  Test (WSA or Chromebook; session objects only, no prints): View > Section view ~0.5. Preview: travels and markers on the camera
  side gone, beds + measure line whole. Prepare: tap / long-press on the cut-away half selects what lies behind it (or nothing); a
  tap on a cut face selects that object; painting through the cut paints the far side. Code: `SectionView.rayStart/kept/current`,
  `SceneController.pick`, `setSection(program, on)` in `PlateRenderer`, LINE/POINT shaders. **Not done:** the paint brush (sphere)
  can still reach cut-away triangles next to the hit (the desktop clips the brush too).

## r172/r173 (moved from HANDOFF.md on 2026-10-13, verified on the Chromebook)
**r172 (Android 6861cc1): the sphere brush gets the section plane** (`OrcaTools.cpp` paint, volume coords as desktop
`get_clipping_plane_in_volume_coordinates`). **Verified on the Chromebook 2026-10-13**: strokes along the cut rim, section off ->
paint ends in a straight line at the cut, cut-away side clean (paint cleared after).
**r173 (Android d6abc60, CI run 38506801682): a paint stroke starting off the model gets its own undo step** (the newStroke flag
was dropped with a missed first sample, `SceneController.kt` paintAt). **Verified on the Chromebook 2026-10-13**: 2 strokes each starting off the cube,
1 undo removed only the second (r172: 3 undos also removed the cube); test paint undone after.

## r170/r172 section-view leftovers verified (2026-10-13, Chromebook r173)
- Long-press with the cut on: on the kept part the object menu opens with the cube selected; on a spot whose geometry
  is all cut away (cut 0.96) the empty-space menu opens (nothing picked). Pick path: `SceneController.kt:312-329`.
- Measure line with the cut: both points on faces the 0.50 cut removes; line and markers stay whole (overlays draw with
  the section clip off, `PlateRenderer.kt:505-540`).

## High refresh rate + painting through a mesh boolean (verified 2026-10-13, phone r175, run 38509685106)
- User asked for ColorOS's "165 Hz ultra refresh rate" (system list: Facebook, Instagram). `dumpsys display` showed the app
  held at 60 Hz by an `APP_REQUEST_RENDER_FRAME_RATE_RANGE max=60` vote with no app request (`peak_refresh_rate=165`;
  modes 1-5 = 120/60/90/144/165 Hz). Commit "Display: ask for the fastest refresh mode while the user touches
  the app": `MainActivity.boostRefresh` (dispatchTouchEvent / dispatchGenericMotionEvent) sets `preferredDisplayModeId` to the
  fastest mode at the current size, dropped 2 s after the last input (PlateView renders continuously, so no fixed rate).
  Phone: orbit -> mode 1 / 120 Hz, 3 s later back to 60 Hz. The window asks for mode 5 (`preferredDisplayMode=5`) but
  ColorOS clamps it to 120 for apps off its 165 Hz list; 165 is a system-side list, not reachable from app code.
- Painting through a mesh boolean (item 7): temp Plate 2, 20 mm cube, More > Add part (10 mm box), part X 15 -> 8, Color
  painting with filament 2 (3 strokes, one over the part's corner), More > Mesh boolean > Difference (Cube minus Part 2):
  notch cut, result one 20x20x20 part, the black strokes stayed on the remaining faces (screenshot). Cube deleted, Plate 2
  deleted via its canvas delete badge (tab long-press via root `input` didn't open a menu); phone back to 1 plate, box.stl.

## 2026-10-14: r178 fullscreen portrait camera strip (verified on the phone 2026-10-13)
**Fullscreen portrait camera strip (r178 = bd7c7f0, run 38515400592): verified on the phone 2026-10-13** (installed there now):
3D view fills the strip; Prepare File button and Preview "Line type" bar sit right of the hole (y 24-167, hole x 597-675);
tool column starts at 203; More/Device/Models start at y >= 141; Tabs switcher fits left of the hole (5 plates scroll and
end at x 585); section panel below the bar; swipe shows transient bars over the app; landscape unchanged (rail in the hole
strip as before). Code: `MainActivity.kt` ~70, `components/CutoutRow.kt`. **Untested:** OpenSCAD design view and its plate
toggle in fullscreen portrait. Minor (left as is, tell the user): Tabs pick mode's "Delete n plates" popup is pushed just
below the strip by the system and touches the File button's bottom edge (r179 moves it under the stop button).

## 2026-10-14: r177 Orca Cloud return link verified
- Orca Cloud browser sign-in (Google -> Brave) ends on the app's loopback page `localhost:41172/callback`; its old "Return" link
  (`intent:#...MAIN;LAUNCHER;package=...`) opened the Play Store, because browsers add BROWSABLE (`cmd package resolve-activity`
  MAIN+LAUNCHER+BROWSABLE = "No activity found"). r177 link = `intent://signed-in#Intent;scheme=orcastudio-android;package=<pkg>;end`
  + VIEW/BROWSABLE manifest filter; MainActivity skips the scheme (`OrcaCloudLoopback.kt:66`, `OrcaCloud.RETURN_SCHEME`).
- Verified: Chromebook r177 over adb (resolves to MainActivity, opens Prepare, no file error); the user's real tap in Brave on the
  phone (r178) worked, 2026-10-14.
- Before the design-mode build the plan was: per-category seed colour -> scheme, nested `MaterialTheme` per UI region; the user
  asked if a web app is cheaper (told no: only the panel moves, loses the real-UI preview) and made an HTML mock-up with three
  dark palettes, which became the r180 presets.

## 2026-10-14: design mode on the Chromebook (r182 -> r185)
- Chromebook was in portrait tablet mode (1080x1920, no cutout), so its checks cover portrait but not the phone's camera strip.
- r182 checks: Industrial and Minimal Warm light versions look coherent (Prepare + More); floating design panel sits bottom
  right above the tab bar; OpenSCAD editor layout and eye toggle in portrait (design on a new empty Plate 2, project cube
  hidden; closing the editor removed Plate 2); wallpaper colours on keep the custom overrides.
- Bugs found and fixed: (1) a custom tab bar colour left unselected icons/labels at the default grey (unreadable on dark blue):
  `Slot.on` + `AppColors.color` now derives a readable colour when only the backing slot (bar, pill, badge) is custom (r184
  fb09b88). (2) A dark Background on Device in light mode left dark text: `readableOn` in `AppColors.kt` flips text, cards,
  lines and soft accents when the text no longer reads on the background (r184). (3) The selected tab label sits on the bar,
  not the pill: `navLabel` in `AppScaffold.kt` (r185 3061b3a). Verified on r184/r185: Device tab + Settings editor with a dark
  background in light mode read well; tab labels white on a blue bar.

## 2026-10-14: design mode r182-r187
**Verified on WSA r182:** More > Appearance preset list with swatch dots (Prusa default, Prusa
Industrial, Printables Night, Prusa Minimal Warm); Printables Night applies app-wide in dark and its derived light version;
Design mode opens on Prepare with the floating panel (neutral, no accent tint since r182); hue slider changed the accent live
(plate outline, badge, Slice, titles, chip); kept after an app restart; per-part override (3D view background via hex) changed
only the 3D view; slot reset and "Reset custom colors" (confirm) work; light panel no longer peach (surfaceTint = text colour).
- r187 (fd4361d) on the Chromebook: a dark Background in light mode had blended sheets/menus towards the light cards
  (Preview sheet slate grey #555E6D) because `Role.BACKGROUND` lerped to the receiver's surfaceContainerHigh, not the
  flipped one; fixed. Low-contrast warning, themed OpenSCAD drag strips verified. More/Models/OpenSCAD regions verified on r185.

### 2026-10-14: r187 (merged as Android PR #20)
- **r187 fd4361d (run 38542190492; CI numbered it r187), verified on the Chromebook:** sheets/menus on a dark Background in
  light mode blend towards the flipped cards (Preview sheet now dark; `Role.BACKGROUND` had lerped to the receiver's light
  surfaceContainerHigh); panel warning (`AppColors.lowContrast`, text under 3:1 on background or cards) shows for dark Panels
  and cards alone in light mode; OpenSCAD drag strip follows the editor. **Untested:** snackbar action (`inversePrimary`
  shaded to 4.5:1; the only action snackbar is "Add" for variants in `FileController.kt:59`; test: VIEW intent on
  `scadzip-test.zip`, recipe in the reference "Chromebook"). r187 only shaded it with an Accent override; r189 fixes the rest.
- Snackbar action: verified on the phone with r189 (2026-10-14).

### 2026-10-14: r189 + r190 (merged as Android PR #21, main f223be2)
- **r189 ff9e977 (run 38547994468), verified on the phone:** snackbar action = accent shaded to 4.5:1 for every scheme
  (r187 showed Material purple #6650A4 on "Add" with plain Prusa dark; r189: dark orange on Prusa dark, orange on navy in
  Printables Night light).
- **r190 0170b41 (run 38550070114), verified on the phone:** message bar drawn over the design panel (the folded panel hid
  it); OpenSCAD reopen button above Slice (it covered Slice with the editor hidden); folded panel button "Unfold the panel".
- Phone r189 design-mode checks (fullscreen portrait): floating panel (360 dp, right-aligned) fits above the tab bar;
  Industrial / Printables Night recolour 3D view, OpenSCAD sheet, File menu, tab bar; light mode (Prepare, editor, More)
  reads fine; Quit dialog shows. r179 delete popup and r178 OpenSCAD design view + eye toggle verified on r189.

## 2026-10-14: r192-r194 (selection colour, dialog samples), verified
- r192 04561b3 (run 38553295192) **verified on the Chromebook** 2026-10-14:
  3D selection falls back to the Prusa blue when Status is within 60° of the part orange (`VIEW_SELECTION`/`nearHue` in
  `AppColors.kt`; r190 Minimal Warm selection #BE660C vs part #D66B21 was barely visible, Industrial weak; r192 both
  #868BA9, Printables Night keeps its blue #3676A9). Design mode > "Messages and dialogs" shows a sample dialog + message
  bar at the top (`DialogSamples` in `DesignPanel.kt`); a hex edit (Panels and cards) recoloured the sample live. A real
  error dialog (VIEW intent on a corrupt .stl: "Loading of a model file failed.") took the custom Dialogs #5A2D82
  (Chromebook r192, 2026-10-14; reset after).
- r194 (run 38557528263): on the phone r192 the design panel covered the sample message
  bar. r193 (8 dp under the safe-drawing inset, spacing 8 dp) still did: the camera cutout inset is ~48 dp; r194 uses only
  the status-bar inset. r193 on the Chromebook: both samples clear of the panel. Check r194 on the phone (More >
  Appearance > Design mode > Messages and dialogs: snackbar sample above the panel). Script: `dlgcheck.ps1` idea =
  launch, More, expand Appearance, Design mode, Whole app > Messages and dialogs, screenshot.
- r194 verified on the phone (fullscreen portrait): sample dialog at the top, sample message bar just above the design
  panel. Phone r192 had installed fine; design panel closed, no custom colours left on either device.

## 2026-10-14: r179 delete popup + r178 design view in landscape (Chromebook r193)
Landscape check done on the Chromebook (ARC, 1920x1080, rail layout) instead of WSA (the PC was in use; same wide layout):
- Tabs pick mode: Add plate, Plate 1 active, long-press Plate 2 -> 2 highlighted, add slot = X; "Delete 1 plate" popup sits
  under the stop button reaching right (x ~241-420, y ~100-150), clear of Undo (x 150) and the File button. Tapping it
  deleted Plate 2; back to 1 plate.
- Add > OpenSCAD model: design view on the main canvas with the editor panel on the right (Update preview / Frame / eye
  buttons top right of the canvas). Eye -> design as a ghost on a new empty Plate 2 with tabs, tool column, Slice plate 2;
  eye again -> design view; Close editor -> back to 1 plate (temp plate gone). Nothing left behind.

## 2026-10-14: .scad download 16 KB content sniff verified (Chromebook r193)
- Models tab WebView (debug via `log.tag.OrcaWeb`), CDP-evaluated `<a download="" href=blob:...>` click with a 4.5 KB
  .scad (4.4 KB `//` comment header, then `width = 20; // [10:50]`, `module box`, `cube`). The listener's name had no
  known extension, so `ModelDownloads.sniffExtension` read past 512 bytes: saved as `<uuid>.scad`, OpenSCAD editor
  opened, Customizer · 1. A blob link with **no** `download` attribute does nothing: `shouldOverrideUrlLoading` blocks
  non-http schemes (ModelsScreen.kt:180), so only real downloads reach the sniff (fine).
- Close editor removed the temp plate, but an adb force-stop right after left Plate 2 in the session (60 s auto-save;
  normal exits save in `onStop`, MainActivity.kt:103). Deleted it, HOME + restart: 1 plate. The Chromebook's OpenSCAD
  draft is now that test box. Debug prop cleared.

## 2026-10-14: WSA r194 element blocker right-click + tray picks
- Element blocker (item 0) with a **real mouse right-click** (new `~/orca-android-tools/mclick.ps1 x y -Right`): Thingiverse,
  More > Block an element, right-click on the top notification text picked `p 675×18`, no browser context menu;
  Larger x2 -> `div 834×40` (whole banner outlined); Block hid it live, rule
  `thingiverse.com: div[class*="SiteWideNotification__notification--"]`. Blocked elements > Undo all -> rules `{}`.
- Tray picks (item 2) on WSA (A1 connected, empty plate, no session file): sync -> Basic / Matte / Matte /
  iSANMATE PETG-HF (engine's match for tray 4), `tray_picks` {}. Slot 2 -> "Bambu PLA Basic @BBL A1 (MOSTLY NO FAN)"
  -> `tray_picks` = `<serial>/0/1` (loaded "GFA01 #000000"); re-sync kept it; window close + relaunch + sync kept it
  (slots restored from `filaments:<printer>` with sources); picking the sync's own Matte -> `tray_picks` {}. Prefs end
  equal to the start apart from the slot sources. App closed.

## 2026-10-14: sign-ins + cloud sync (user-tested)
- User reported done: MakerWorld sign-in ticket flow (item 8), MakerWorld downloads with ad blocking on, Bambu sign-in
  WebView (sign-out + password), Orca Cloud login (item 6), and the Bambu/Orca cloud syncs after the preset re-link (item 1).
  No problems reported.
- Same day, user: MakerWorld pinch zoom works; real-keyboard Ctrl+1 is taken by a system hotkey (kept open in the handoff).

## 2026-10-15: handoff trim (verified details moved from the hot file)
### Design mode + colour presets (r180-r194, merged; verified WSA r182, Chromebook, phone r189)
- User 2026-10-14: "code all three as presets, in addition to the customizer; you categorize everything".
- Panel: drag by header, fold, close; preset chips, "Part of the app" dropdown (14 parts), colour chips (• = custom) with the
  slot's hint, HSV sliders + hex + swatches, Reset this part / Reset all. Colours per light/dark mode; sliders save on release.
  Design mode itself isn't kept over a restart (by design).
- Code: `ui/theme/AppColors.kt` (Role/Slot/Region/ThemePreset; `withRoles`; `Themed(region)`; `OrcaTheme`),
  `ui/theme/DesignPanel.kt`, `core/ThemeSpec.kt` (prefs `theme_spec` JSON). Regions wired in `AppScaffold.kt`; `Viewport.kt` +
  `PlateBadgeAtlas.kt` read slots.
- Chromebook portrait checks: Industrial/Minimal light, panel fits above the tab bar, tab bar slots, Device + Settings editor
  regions, wallpaper colours + overrides, Reset all (no confirm in panel; More's "Reset custom colors" confirms); r185 More,
  Models, OpenSCAD editor fine; Preview sheet slate grey fixed r187 (fd4361d, merged). Phone r189: dialogs, 3D selection colour.
- r179 de39e04 delete popup + r178 design view: verified r189 portrait + Chromebook r193 landscape. Slice button = primaryContainer.
### Away orders r196-r202 (merged, verified WSA)
- Numpad 0-7 camera views (Num Lock on: 1 top, 3 front, 7 frame plate; off = nothing). Blind bug hunt on WSA: 3 fuzz runs,
  ~550 random steps, malformed STL/3MF/SVG, hostile OpenSCAD, extreme numbers, lab sliders 50-130 %, portrait 430x900: no crash.
- Fixes: Shape dialog Add off outside 0.1-1000 mm, chips wrap; typed scale clamped 0.1 %-100x; NumberField rejects
  NaN/Infinity/|x|>1e6 (raw NPE dialog before; stack traces logged under `OrcaStore`); "(s)" -> plurals (r203, 897a708);
  Shape builder menu icon = compass. Not bugs: lab sliders, filmstrip digits, tune icon blue with overrides.
- Design polish pass in landscape + phone-portrait sizes found nothing else.
### r208-r213 Models / settings (verified parts)
- Chromebook portrait r208: full-width bottom sheet (Slice button behind the sheet). Camera card (net/BambuCamera.kt,
  ui/device/CameraCard.kt; TLS 6000, login packet + JPEG frames): live frames, snapshot to Downloads, stop (landscape + portrait).
- Models edge swipes verified WSA + phone (user). r211 Chromebook portrait: toolbar layout, white site-tab line, links row
  shade, settings tab swipe, Speed hidden in Simple. User on the phone: toolbar layout (SplitAroundCutout in
  components/CutoutRow.kt), MakerWorld full site "Open in Bambu Studio" -> plate (`ModelsScreen` intercepts
  `bambustudio://open?file=`), so slicer mode, its toggle, links row and trim JS were removed.
### Preview toolpath look r219-r220 (verified by me on the phone, plate 5 box)
- Cause of dashes/moire: each segment box overlapped the next by a quarter width, end caps poked out at joints, flat normals.
- r219: `PlateRenderer.uploadPadded` dummy records before/after, `drawPreview` binds prev/next records as attribs 8-11;
  `PATH_VS` mitres joints (turn < 120 deg, else quarter-width cap), shared bisector normal when turn < 40 deg; `PATH_FS` bead
  normals faded by `fwidth`. Left hairline cracks close up.
- r220: symmetric corner `(right+rn)/(1+c)`, own `right` via `rightOf`, exact z; bead fades in from ~8 px per layer
  (`smoothstep(0.12, 0.25, fwidth)`, amplitude 0.8). Result: no arcs/cracks, smooth curved walls, a few isolated specks (ok).
- r217 stage 1 studio shading (MESH_FS sky/ground ambient, key+fill, spec, rim, `uEye`): user "pretty good" on Prepare.
### Slice crash symbolization (r213, phone)
- SIGSEGV in orca-engine, symbolized with NDK 28.2 `llvm-addr2line` on the WSL `~/orca-build/core/liborca_jni.so` (offsets
  matched although build ids differ): `ToolOrdering::reorder_extruders_for_minimum_flush_volume` (ToolOrdering.cpp:2812)
  memmove from an empty `flush_volumes_matrix`. Not related to 165 Hz.

## 2026-10-15: r221/r224 phone takeover
- r221 measured: contact shadow rendered as a faint ~5 mm rim (max 18/255 darker, on/off screencap diff); AO Full darkened flat
  slanted faces with grain (cause: SSAO spiral samples read NEAREST depth at off-centre uv); AO Soft max 14/255; Preview orbit
  26 fps AO off / 17 fps Full on plate 5's 848-layer slice (Prepare 125 fps either way). Green number top left = system
  refresh overlay, not app FPS (`glfps.sh` = SurfaceFlinger timestats). Models site-tab line missing (fillMaxWidth 0 wide in the
  unbounded scrolling row).
- r224 (d9f1f7f + ca77961): SSAO samples snapped to texel centres (flat faces clean, creases kept); `PlateRenderer.previewAo`
  skips AO while the camera moves and reuses it while view/toolpaths are unchanged (23 fps orbiting with Full, AO drawn when
  still); shadow strength 0.8, blur 10 mm + 3rd round (max 25/255, twice the area); site tab `width(IntrinsicSize.Max)` (line visible).
- Verified on the phone with r221: zoom toward the fingers (plate badge under the pinch stays put); r214 drag follows and stays
  where dropped (undo restored the frame exactly); r218 pill opens from its text; r213 settings page tabs swipe repeatedly both
  ways; r211 object settings editor opens. Preview camera on the phone sits under the bed (already so before the session).

## 2026-10-15: r225-r240 verified items (moved from the hot file)
User 2026-10-15: r231-r240 "everything confirmed working"; r226 shadow strobe confirmed fixed. Original notes, verbatim:
- **Installed 2026-10-15: r242 (run 38834271507, head aafa5f5) on all 4 devices** (carries 2869d46 + aafa5f5; untested items are in Next task 000). Older notes: r238 was installed; r239 (cf1fe95) building. Earlier: **r238 (run 38817423594, head 7d49df9) on all 4 devices**. Before that: r231 (run 38803353110, head 1d4ee77). Before that: **r229 (run 38798417463) on phone + WSA + Chromebook + Shield** (2026-10-15; includes r225-r228 fixes).
00. **Chromebook autopilot 2026-10-15 (landscape, r239 installed there via direct `adb install -r`; the script failed during a ChromeOS update).** Verified by me on the Chromebook: prime tower
   box draws inside the bed (needs 2 filaments USED: paint one face with filament 1 on a filament-2 cube; a cube set to filament 2 alone shows no tower); finger-drag of the tower moves it and
   the panel X/Y follow; Layout-lab fade preview works. **Bug found:** dragging the tower past the bed edge showed X/Y = 214/470 mm (raw, bed is 256) although the box was drawn clamped.
   **FIXED + verified on the Chromebook upright (2026-10-15):** `OrcaScene.cpp` ~513 emits the clamped box x/y as `wipe_tower`; commit 2207858, run 38827920395 = r240, installed on all 4 devices.
   Dragging the tower far past the bed edge now shows X 4.4 / Y 228.9. Also verified upright (portrait, forced via `settings put system user_rotation 1`, restored to auto after): toolbar, plate tab, grip-resized pane,
   Slice button, pane resize. Chromebook test project: 2x Cube (filament 2, one face painted filament 1), 2 filaments, tower at the back-left edge; harmless test data. User has not tested r240.
   Not yet driven: design panel clamp, `!` badge, "Both" dialog, plate switcher on the grip (lab).
0. **2026-10-15 batch, commit 7d49df9, run 38817423594 = r238 (built OK, installed on all 4 devices 2026-10-15), ALL UNTESTED by the user (C++ and Kotlin compiled locally):**
   (a) Prime tower "partially outside" cause: slice/3D clamped the tower with `prime_tower_width` for both axes and no user-position clamp, ignoring the
   real depth + brim + cone. New `OrcaEngine::tower_box` (OrcaScene.cpp, uses `estimate_wipe_tower_footprint` like PartPlate.cpp) feeds both the 3D box
   and `slice()`. (b) Finger-drag the tower: scene JSON `plates[].tower` = x,y,w,d,h; `PlateView.towerBoxes` hit test -> `onTowerMoved` ->
   `setWipeTower`; renderer `dragTower` highlights id -2. (c) Design panel clamped on screen (`DesignPanel.kt`, measured height). (d) Lab: "plate switcher on
   the panel grip" (portrait, `LayoutLab.platesOnHandle`, `DragHandle(header)`) + own size slider (`plateScale`). (e) More > Misc segment "Keep mine" -> "Mine"
   (it wrapped and drew a tall box). **Estimate given to the user (no work done): live Layout-lab designer = ~half a day: the lab controls (`LayoutLabSection`,
   MoreScreen.kt) already apply live via `setLab`, so it is moving them into a floating panel next to `ThemeDesignPanel` with a Theme | Layout tab
   (shared drag/clamp code); collapse to a draggable floating icon ~1-2 h more. Lab items are layout, not colours, so no Region/slot model needed.**
0b. **DONE, UNTESTED (user 2026-10-15): Layout-lab fade preview.** Commit cf1fe95, run 38822428770 = r239 (built OK; installed on phone, WSA, Shield; Chromebook was offline, install later). While More > Layout lab is
   open, Prepare stays composed under More (`AppScaffold.kt` Screen.MORE branch, `LabPeek` in `MoreScreen.kt`); any touch inside the lab section fades More to 0 % (animated)
   and back on release. Scale sliders now apply live; More keeps the sizes from the touch (`LabPeek.frozen`) so the slider doesn't move under the finger.
   Limits: scrolling inside the section also fades; no canvas touch while adjusting; Prepare only. Floating Theme | Layout designer (half a day) stays an option.
1. **r224 (run 38710373932, head ca77961) on all 4 devices.** Phone-verified 2026-10-15 (details: archive "2026-10-15: r221/r224
   phone takeover"): zoom-at-fingers, SSAO, Preview AO caching, site-tab line, r214 drag, r218, r213 tab swipes, r211.
   **User feedback 2026-10-15 (to investigate, nothing changed yet):** **(a) first fix 2026-10-15, pushed as b4531b7, r225 = run 38716572574, installed on all 4 devices (WSA installs are silent, so no need to skip them while the user games; only driving the UI needs focus), NOT tested by the user (user: "glow without direction" matches the diagnosis):** `SceneEffects.kt` blur 10->5 mm, shadow
   shifted (-1.2,+1.8) mm away from the light; `PlateRenderer.kt` ~233 drag area in 20 mm steps (map area grew every frame, so the
   texel grid swam). Needs a build + the user's eye; if still off, try a height-based directional shadow (shift in pass 3).
   **(a) second fix 2026-10-15 (user: r225 "looks a lot better" but a strobe/stepping in the shadow when dragging, visible zoomed in):**
   cause = the 20 mm area steps rescaled the shadow texels at each step. `PlateRenderer.kt` ~233 now widens the map by a FIXED 150 mm for
   the whole drag (one resolution change at drag start, none after). Pushed 37c4058, r226 = run 38719508315 (built, installed on all 4 devices), UNTESTED by the user.
   (a, original) contact shadow strength OK, but it spreads out
   on every side and "looks off" (also while dragging; it does follow the drag). Check from under the plate. Likely cause
   (unverified): a symmetric blurred footprint with no light direction. User may bring opinions from other AIs.
   **(b)+(c) fixes 2026-10-15, pushed (commit after 37c4058), r227 = run 38728513573, installed on all 4 devices, UNTESTED by the user:**
   (b) cause: `Camera.pan` used 0.8*distance/height but FOV 35 gives 0.63; now exact, and `Camera.panGrab` (plate-plane ray before/after)
   makes the plate follow the finger at any pitch/zoom (PlateView 2-finger + mouse pans; MeshPreviewView still uses `pan`).
   (c) cause: `hitsSelection` is an axis-aligned world bbox test; now only a pre-check, and `PlateListener.onDragCheck` ->
   `SceneController.checkDragStart` runs the engine mesh pick async and cancels the drag when the mesh isn't under the touch
   (the touch then orbits). Possible side effect: a drag is cancelled a few ms after DOWN on a miss.
   NEXT: user phone-checks pan + a touch off the mesh near a selected object.
   (b, original) Finger pinch speed is fine. Panning feels strange when zoomed far out (check whether pan scales with distance).
   (c) At some angles and zooms, with an object selected, a touch about one grid square away still drags it: check the
   drag hit-test (bbox vs mesh pick, touch-slop radius in world units vs pixels). Idea (not done): Preview orbits at ~26 fps on big slices even with AO off (4x MSAA at 1272x2772, instanced boxes).1b. **2026-10-15 (user OK'd both):** (i) Back in the OpenSCAD editor = on-plate -> design -> close (`AppScaffold.kt` ~150). (ii) Opened projects ask
   whether to keep the app's filaments or take the project's (`FileController.askFilaments`, `FilamentChoiceDialog`; setting More > Misc
   "Filaments of opened projects": ask/mine/project; `AppSettings.projectFilaments`). Only for user-opened files, not session restore. German strings missing.
   Pushed, run 38792872155 building; install on all 4 when done; Installed on all 3 online devices 13:26 (run 38792872155). **User 2026-10-15: "Dialogue doesn't pop up"** — code path checked, wiring OK (AppScaffold:162 -> FileController:146); dialog only shows for a user-opened project (uri != null) when its filament preset/colour list differs from the app's, setting = ask, and plate empty or append=false (`openModels` line 67: a .3mf onto a non-empty plate is imported as models, no dialog). User also tried project .3mf files (swatch2, stack_8x8 etc., real differing filaments). **Cause:** the busy AlertDialog ("Opening project", `GlobalDialogs`) is composed after it and covered it, so the open awaited forever. Fix 6f6d9e7: the dialog now lives in `GlobalDialogs` and hides the busy dialog while asking. Run 38798417463 = r229, installed on all 4 devices 2026-10-15; UNTESTED by the user (needs a user-opened project with differing filaments). **User: "bug still present" (opened from Recents).** Real cause: those recents (swatch2, stack_8x8, Purge_loop...) were stored `project:false` because they were downloaded/imported onto a non-empty plate -> `openModels` imported them as models (no dialog). Fix c58af9f: `openModels` now asks "Open project / Import models" (`askOpenAsProject`, `UiState.projectOrImport`, dialog in `GlobalDialogs`; en+de strings) when a project .3mf lands on a plate with objects; Open -> `openProjectFile` -> filament dialog. r230 cancelled (superseded). **Also 1d4ee77 (user 2026-10-15 asks):** user filament presets with `default_filament_colour` recolour the slot on pick (`OrcaEngine.cpp` compatible_names "color" -> `PresetRef.color` -> `setFilamentNow`); colour dots for user presets in the filament picker (`PickerItem.color`); portrait Printer/Objects panel is now a grip-resized pane (`AppScaffold.kt` `PanelSize`, 56 dp..85 %, default 132 dp) instead of the 2-position BottomSheetScaffold. Run 38803353110 = r231, installed on all 4 devices 2026-10-15; **user: "everything works good"** (open/import dialog, preset colours, picker dots, resizable panel = verified). **1d52cf3:** additive "Both" choice in the filament dialog + More > Misc (project's slots, then mine it lacks by preset+colour; no extruder remap needed). r232 = run 38806423389, installed on all 4 devices 2026-10-15; UNTESTED. **Open bug (user):** after "Use project's" slot 1 showed "CHCKX PCTG Light Blue..." Phone state: all 4 slots = AMS-sync result, not swatch2's (PLA Basic/PLA Matte/OVERTURE PETG/PETG-CF); `tray_picks` holds tray A1 (PLA Basic yellow) -> CHCKX, which a sync reapplies. Sync is manual only (`syncFilamentsFromPrinter`), so ASKED the user whether they pressed Sync after opening. If not, find what replaced the slots. Drop of 165 Hz check: user said scratch it. **User 2026-10-15 (sync = yes; slot 1 = PCTG vs the tray's PLA, slice error):** pushed (commit after 1d52cf3), run 38809389538 = r233 (building; install on all 4 when done), UNTESTED: (1) red `!` + refresh button on a slot whose type differs from its AMS-synced preset (`PreparePanel.kt` FilamentSlots; refresh = `setFilament(i, source.synced)`; only type mismatches, not other settings of the same filament); (2) colour dots for baked-in preset colours in all filament pickers incl. system presets, Objects>Filament, and the closed field (`presetItems`, `PickerField`); (3) upstream's 'Preferences / Control / Slicing / Remove mixed temperature restriction' error now shows this app's path; new setting More > Printers & presets > "Remove mixed temperature restriction" (`SettingPath.kt` = single source for label+section; `OrcaEngine::m_allow_mixed_temp` -> `print.set_check_multi_filaments_compatibility`). Only that one libslic3r message points at desktop UI (grepped Print/GCode/PrintObject). Answered the user: Opus not needed. **User confirmed 2026-10-15: shadow strobe (r226) fixed.** **BADGE DONE 2026-10-15 (b334626, r234 = run 38811456545, built OK, installed on all 4 devices 2026-10-15 (also: prime-tower-outside-bed fix in `OrcaEngine.cpp` slice, ~line 576: unset tower position is clamped onto the bed; user report 2026-10-15, UNTESTED, C++ not compiled locally; superseded runs 38811117959, 38810195344 failed on unescaped apostrophes in strings.xml, fixed), supersedes r233 which was cancelled, so r233's items above ride in r234; install on all 4 when built; UNTESTED):** `setupProblems()` + `ProblemFocus` in `PreparePanel.kt`; red FAB `!` above the Slice button (`PrepareOverlay.kt`); tap -> `AppScaffold.kt` Workspace raises the portrait pane to >= 360 dp / untucks the wide panel, `PreparePanel` switches to the Presets tab, opens Filaments, scrolls to the slot (350 ms delay); slot row has a red border + red explanation line. Only case so far = slot type mismatch; add more by extending `setupProblems` (SetupProblem is slot-based: mixed-temp etc. would need a non-slot variant). Original idea: a red `!` badge on the main 3D view when a setup problem exists (first case: slot type mismatch; later: mixed-temp, etc.). Tap = open/slide up the Prepare drawer (portrait `PanelSize` pane in `AppScaffold.kt`), expand the Filaments section, scroll to the slot and show a red explanation line next to the problem setting; user suggested a red outline around it (they're unsure it's overkill: keep it subtle, e.g. red border + one-line text). Needs one shared 'problems' list (slot index + message) that feeds both the badge and the slot row; reuse the mismatch check in `PreparePanel.kt` FilamentSlots.

## 2026-10-15: verification session (user went down the list, works/broken)
Verified by the user (r242/r243 on phone): handle pull no longer moves the 3D view; strip/chip plate select does not re-frame
(inactive double tap re-frames, user fine with it; active double tap re-frames); tap on empty bed of an inactive plate activates it;
towers on other plates move independently (panel X/Y of the other plate stays); design panel folds into its title bar; tower
slides around objects and returns; r227 pan + `checkDragStart`; colour dots for system presets in all filament pickers;
r216 projects keep the current printer (toast); r213 import 2 files = own plate each; Layout lab scrolling/canvas-touch limits;
large 20+ plate slice completes plate by plate (replaces the old 325 MB retest).
Tested by Claude on the Chromebook (ARC, r243): "Remove mixed temperature restriction": PLA+ABS slice error text reads
"turn on More > Printers & presets > Remove mixed temperature restriction" (this app's path); with the switch on it slices.
(Setting restored to off; test project's filament 2 is now ABS, harmless.)
Logged observations (NOT acted on, user wants data first): see HANDOFF.md "Logged findings".

## 2026-10-15: pruned verified details
0. Element blocker + ad-block menu (e4d75ab, a7e63d2): verified phone; mouse right-click verified WSA r194. **Untested:**
   rules in other WebViews (Device page etc.; needs a printer with a web UI). User 2026-10-13: MakerWorld, Printables and Orca sign-ins work; a Google-account Orca
   sign-in goes through Brave (not the WebView). Bambu sign-out is Device tab > Connection > Sign out (told the user).
6. Ad blocking (f138655, ec86442, 08bdf0b = `AdBlock.Client` in all app WebViews): verified; MakerWorld sign-in / downloads with
   it on, Bambu sign-in WebView, Orca Cloud login verified by the user 2026-10-14. **Untested:** plugin pages, Device page on
   the phone (needs a printer with a web UI; the phone only has the A1).

## 2026-10-15: r245 phone tests (Claude)
L2: dragging More > Layout lab 'Text size' slider keeps a readout card ('Text size: 95 %' + bar) at the top while More fades; Reset restored 100 %.
L4: beeper plate moved to Plate 1 (X=60) with filament 4 (PETG-HF) + cube on filament 1 (PLA Basic): slice gives the mixed-temp error naming More > Printers & presets > Remove mixed temperature restriction; with it on the slice completes (40 min 51 s) and the summary line shows in the tertiary (blue) colour, not red. Setting turned back off.

## 2026-10-15: slider touch bug (r247) verified
- Cause: Layout lab Column faded/froze More on ANY touch. Fix: fade/freeze only while a slider is pressed (`MoreScreen.kt` LabSlider). Verified on Chromebook by Claude: press-and-hold on Filmstrip and Reset leaves More unfaded; slider drag still fades/scales; Reset restores 100 %.

## 2026-10-15: design panel unfold (r248) verified by user
- Cause: `DesignPanel.kt` clamped the panel with the previous frame's height, so an unfold overshot then snapped back. Fix (9fe3f66): `clamped()` runs in the layout pass with the measured height. User confirmed fixed.
- Opacity: tool buttons were secondaryContainer (almost the canvas grey) so the slider showed nothing; now `surface.copy(alpha)` like the bar (File too). Slice ignores it.
- Opacity on tool buttons/File: user confirmed fixed on r248.

## 2026-10-16 verified (moved from HANDOFF)
- User (r249): opacity readout card works, L1 fold jump fixed, L3 status error card good, all sliders good. Haptics: only Back/forward gestures vibrate (settings tabs, Models edge swipes don't; unfixed, can't test over adb).
- Chromebook autopilot (run 38858438386, head ca2a96d): X2D 0.4 slice OK, no crash (r215/r217 fixes VERIFIED); top-surface bead shading visible when zoomed in (flat zoomed out, by design); printer reset to A1.
- Phone autopilot (r250): Sign out horizontal on its own row VERIFIED (b74e8ea); readout card centred / clear of the tool bar VERIFIED (a853161; opacity left at 100 %). "Remove" beside Import credentials wrapped letter by letter: fixed in 7e569a4 (own rows, untested).
- Merge candidates verified by the user: dca0db0, 9bca6b7, 9fe3f66, 54b4ca6, ca874e4, a853161, b74e8ea.

## 2026-10-16 Shader strobe / Preview AO ghost (details moved from HANDOFF.md)
User: top surface no longer messed up (3299fcc per-role z lift, verified). The glow in the corner hole and ghost shadows were the Preview AO:
f59aed7's fade left a stale AO ghost, and no AO while moving made deep corners bright. Fix ae021be (UNTESTED): Preview recomputes AO every
frame up to `LIVE_AO_MAX_SEGMENTS` (250k); bigger toolpaths keep the fade. Build run 38864792521 installed on all 4 devices. User: phone does
120 fps in both views. Unmerged then: 7e569a4 (Remove row), f59aed7 (AO fade; alone it has the ghost, merge only with ae021be), ae021be.

## 2026-10-16 Gemini offload test (user wants to save usage)
Test 1 (design panel tabs, bundle `gemini-designer-bundle.txt`): 7/10, compiled against the excerpt but my File 2 excerpt was stale (ScaleSlider has a `lab`
param, LabSlider/LabPeek.readout). Not applied. Test 2 (real shadow map, task file in the session scratchpad): 6.5/10; Gemini's draft had 6 runtime bugs
(sampler2D + sampler2DShadow on one unit in the bed shader, mediump depth compare, stale map when shadows off, feedback-loop binding, wrong delete ids,
context-loss delete). Lesson: Gemini 3.1 Pro is good for GL/shader design drafts; always review GL-spec traps; give it current source.

## 2026-10-16 Real shadow map, MCP view tools, light lab: verified parts (moved from HANDOFF.md)
- Real shadow map (wishlist "3D stage 4"), Gemini draft + my fixes: commits b7afd30, 0ffb7ae (sliders), then the receiver-plane PCF commit;
  build r258 (run 38874388859) installed on all 4. Phone-checked with the cube: shadow falls on the bed, soft, no grain (defaults darkness 50 %,
  softness 6 mm, bias 2 mm). Knobs: More > Layout lab > Shadow darkness / softness / bias (the plate shows through while a slider is held).
  Phone-checked (r258): tall pillar next to a box (long soft shadow, fine), "shadows under objects" switch off/on (More > Appearance).
  Top-down: pillar near the bed corner (long shadow fades, no artifacts), Section view at 0.2/0.5 (shadows follow the clipped height).
  Overhang: a table (44 mm slab on stem+cone, built through MCP `shape_add` + `design_to_plate`, plate 2) shows its shadow soft and
  attached from the top view; the iso view hides it behind the slab.
- MCP on the phone: `adb forward tcp:8765 localabstract:orcastudio_mcp`, token read as root from app_settings.xml into a temp file,
  `sh mcptest.sh @file 'shape_add:{...}' design_to_plate`. New tools `view_set` (view/plate/yaw/pitch/distance/target/section/ao/shadows)
  and `view_screenshot` (PixelCopy of the plate view, 256..1280): commits "MCP: view_set..." + "angles given with view fit apply after the
  fit". Verified on the phone with run 38882091450 (installed on phone, SHIELD, WSA; Chromebook offline): `fit` + angles works; the
  screenshot is laid on black (the shadow pass lowers the surface alpha; a plain PixelCopy PNG showed a light halo).
- Light lab (run 38883491004 = r263, installed on phone/SHIELD/WSA): More > Layout lab Light direction (-180..180), height (20..85),
  strength (20..200 %); defaults = the old fixed light. Verified with `labset.py lightAzimuth=120 lightElevation=35 lightStrength=1` +
  `view_screenshot`: shadow moves with the direction, strength brightens.
- Tools: `python -I ~/orca-android-tools/shadowtune.py <strength> <soft_mm> <bias_mm> <out.png>` (env ORBIT=600 turns the view);
  `labset.py key=value ...` sets lab values and restarts. A "performMeasureAndLayout called during measure layout" crash dialog was on
  the phone at the r257 launch (origin unknown; watch for it).
- Blind re-test 2026-10-16 (r263): `NOTES-blind-shader-test-2026-10-16.md`.

## 2026-10-16 Remove row verified
- 7e569a4 (Connection dialog: Import credentials and Remove on own rows): phone r263, ui dump: Remove on its own row, 180x70 px, one line. VERIFIED.

## 2026-10-16 Merge PR #27 (r264): Preview AO, bed shadow clip, section cut verified
- f59aed7 + ae021be (Preview AO live/fade): phone r264, cube engravings (`aopair.sh`): AO off bright, AO 2 shaded, mid-orbit = rest (no bright corners, no ghost). VERIFIED.
- a013cdd (bed shadow clipped to each bed box): light az 90 / el 20, pillar shadow runs to the bed's orange edge and stops there (top view). VERIFIED.
- dc6ef3c (section cut by the active plate's objects): plate 1 at 0.5 cuts pillar and cube halfway, 0.8 removes all, 0.2 nothing yet. VERIFIED.
- Merged the whole work branch (14 commits) as PR #27 -> main 8eadb0f. Phone dropped wireless adb twice; re-paired with `adb pair <ip:port> <code>`.

## 2026-10-16 r266 (run 38930002463, 9badabd): blind-test findings 3-5 fixed, verified on the phone
- Part shadows: lit faces now x (1 - darkness * occlusion * smoothstep(0, 0.2, n.l)) like the bed's shadow (only the key term was dropped
  before, so 50 % looked faint); faces turned from the key light are left alone. Light az -142 / el 20 / soft 6 (pillar's shadow on the
  cube), view yaw -120 pitch 30 dist 200 target [165,118,10]: cube top in full shadow / unshadowed = 0.52 (r264: 0.86), bed 0.70 (same as
  r264). Default light from two sides: no acne, banding or terminator line on the pillar. VERIFIED (test; the user hasn't seen the look yet).
- Bed shadow bias used a fixed n.l of 0.75; now the light's height (`uBedNdl`). Bias knob ~no effect is expected: the 0.4 mm normal
  offset and the receiver-slope taps already stop acne; bias only adds a ~1 mm gap at contact.
- Grid: one AA shader quad per bed (`GRID_VS/FS`, `PlateRenderer.drawBeds`), outline stays GL_LINES. Top, grazing (pitch 8) and wide
  near-top (pitch 86, 420 mm) shots: same line positions as r264, continuous smooth 1 px lines, no breaks. VERIFIED (test).
- Shots: `~/orca-android-tools/shots/{base,new}_*.png`, `cube_compare.png`. Installed on phone, SHIELD, WSA (Chromebook offline).

## 2026-10-16 r267 (verified on WSA)
Run 38934298949, "Settings: a faint line under the inactive tabs" (`Modifier.inactiveTabLine`, Widgets.kt; used on the Process/Filament/Printer tab row and the page-tab row in SettingsEditor.kt). WSA screenshot: grey inset line under the unselected tabs, orange indicator on the selected one. Page-tab row (line 299) not seen: tablet layout shows a left list instead. Installed WSA + SHIELD; phone and Chromebook were offline (install there when back).

## 2026-10-16 MakerWorld printer chip auto-select (verified on WSA)
Runs 38969511771 (first try, chip row not found) then 38975729124 (fix: look up to 4 ancestors for the row starting "All"). `PRINTER_JS` + `ModelBrowser.chipLabel()` in ModelsScreen.kt: on makerworld model pages (`/models/<n>`), clicks the "Print Files" chip matching the current printer preset (PrinterModel.ofPreset; X1C -> "X1 Carbon") once per page. WSA r270 (signed in): A1 chip highlighted on load, list filtered to A1 files. The label is baked in at first injection per page load, so changing printer preset needs a Models reload. Site chip row resets to All on every load (not stored); fragile if MakerWorld changes its DOM. Cloudflare check reappears after each app restart: user gave standing OK to click it (memory).
Follow-up (run 38991526428, r271): if the chip isn't found within 8 s of a model page loading, the page posts `orca_printer_chip_missing` and the app toasts `models_printer_chip_missing` ("Couldn't pick A1 ..."). WSA: success path re-verified (A1 selected on a fresh model page). **Failure toast untested** (needs a model with no file for the printer, or a changed site). Installed WSA + SHIELD.

## 2026-10-16 AO removed (run 38998000561, verified on phone)
Screen-space ambient occlusion removed entirely (setting, More row, SSAO/blur/composite shaders, preview fade, MCP `ao` arg). It caused the jack-o-lantern look and the double-exposure ghost fading out on rotation; only appeared with soft/full, never when off. Shadow map/toggle untouched. `aopair.sh` helper's AO step no longer applies. Installed on phone, SHIELD, WSA (Chromebook offline).
Re-review (Opus, 2026-10-16): the user had been asked "remove or keep" and answered that AO caused the jack-o-lantern look and the ghost, which was taken as a yes. Commit 6b8e5e4 checked: no AO references left (`git grep`), the EGL-comment slip was fixed, shadow path unchanged. MCP: only `view_set`'s `ao` argument and the "ambient occlusion" part of its reply went; `viewSet` reads args with `has()`, so a client still sending `ao` gets it ignored, no error. Only consumer was the local `aopair.sh` test helper (now obsolete). Undo if ever wanted: `git revert 6b8e5e4`.

## 2026-10-16 OpenSCAD Back steps (16aa31d, verified on WSA r266)
Add > OpenSCAD model > Box > eye ("Show the design on the plate", temp Plate 3) > Back = "Back to the design view" state gone, design
view shown > Back = editor closed, temp plate removed (Plate 2 of 2). `AppScaffold.kt` BackHandler ~151. Also found: numpad 0-7 view
keys (backlog item 10 "offered") already exist (`MainActivity.kt:236`, NumLock on); not device-checked. Delete-plates popup colours
are hard-coded on purpose (user 2026-10-14, `OverlayParts.kt:253`), so that "design mode known limit" stays.

## 2026-10-16 Backlog stale-item prune (verified entries moved out of HANDOFF-BACKLOG.md)
User: "you just tested something we already confirmed worked". Cross-checked each backlog line with this archive + git log.
- Former item 000 (2026-10-15 verification session, merged PR #25, r243 on all 4): L1 fold ghost fixed (user r249); L1/L3 + printer-model
  guard 54b4ca6 merged, L3 status card "good" (user r249); L2 readout card verified r245; L4 tertiary colour verified r245; L5 no change wanted.
- Former item 2: r215/r217 X2D slice fixes VERIFIED (Chromebook autopilot, run 38858438386). 165 Hz done.
- r213 haptics: NOT untested any more. User r249 (after fcf1e39 "haptics on swipes"): settings tabs + Models edge swipes don't vibrate,
  only Back/forward gestures do. Open bug, unfixed; kept in the backlog as such.
- Former item 3: A1 chip auto-select done + verified (WSA r270/r271); inactive-tab line done r267 (verified WSA); blind shader findings
  (bed shadow spill a013cdd, section ratio dc6ef3c, part shadow/bias/grid r266) all verified.
- Former item 4: OpenSCAD Back steps done 16aa31d, verified WSA r266 (old analysis: Back = AppScaffold.kt:148; Models back + edge swipes
  ModelsScreen.kt:464/:182; settings tab swipe SettingsEditor.kt:303; optional horizontal swipe between main tabs was never asked for).
- Closed: WSA lighter-background glitch (user: their other PC activity). Former open item 1 ("verified, nothing open": preset re-link,
  section view, shape builder, .scad zips / 16 KB sniff, OpenSCAD phone layout / downloads / painting, MakerWorld ticket flow).
- Former item 10: 325 MB retest replaced by the verified 20+ plate slice; MakerWorld pinch zoom works (user 2026-10-14);
  Ctrl+1 = system hotkey (user 2026-10-14, nothing to do).
- Element blocker / ad blocking verified parts: see "2026-10-15: pruned verified details".
- Closed item 0(a) (strings stub, user 2026-10-16: never test, list or maintain) dropped from the backlog.

## 2026-10-16 Haptics verified (backlog "Open bugs" item closed)
- User: haptics on settings tab swipes / Models edge swipes were already confirmed working; an earlier session didn't log it.
- Phone `dumpsys vibrator_manager` backs this up: the app's `performHapticFeedback(constant=9)` (TEXT_HANDLE_MOVE, settings
  tab swipe, SettingsEditor.kt:313) and `constant=0` (LONG_PRESS, PlatePicks) played on 2026-10-16 21:08 (OnePlus waveforms 1/2).
- Tip: `adb shell dumpsys vibrator_manager` lists recent haptics per app, so haptics can be checked over adb after the user's taps.

## 2026-10-16 r197 NumberField NPE root cause
- Android `JSONObject.toString()` returns null when a nested `JSONArray` holds NaN/Infinity (`JSONArray(Collection)` skips the
  check that `put(name, value)` does; top-level NaN throws "Forbidden numeric value"). Proven with a dex test on WSA
  (`app_process`, scratch `JsonNan.java`). Kotlin's platform-type null check at `Engine.call` then threw the NPE (Vec3 args).
- Fix 6ad69ef (Android work branch): `Engine.call` throws `OrcaException("<method>: a number is not finite ...")` instead.
  Unbuilt; NumberField guard 776f261 already blocks the known UI path.

## 2026-10-16 Numpad 0-7 camera views verified (WSA r272, by test)
- Backlog "Untested" item closed. `MainActivity.kt:236` (Num Lock on): real Windows key presses (`keys.ps1`, VK 0x60-0x67)
  on WSA r272 gave 0 iso, 1 top, 2 bottom, 3 front, 4 rear, 5 left, 6 right, 7 frame plate (contact sheet, all distinct, correct).
- Scripts: `~/orca-android-tools/numpadsheet.sh` (contact sheet), `numpadtest.sh` (hash compare vs Ctrl+N; Ctrl+1..9 from
  keybd_event still never reach WSA, only Ctrl+0, as noted 2026-10-1x). Num Lock off path (arrows nudge) not re-checked.
- r272 = run 38998000561 (6b8e5e4, AO removed): on phone, SHIELD, WSA.

## 2026-10-16 MakerWorld printer-chip "missing" toast verified (WSA r272, by test)
- Backlog "Untested" item closed (da41ad3, r271+). Found a no-A1 model by scanning home-page ids with the in-page
  `/api/v1/design-service/design/<id>` (`instances[].extention.modelInfo.compatibility` + `otherCompatibility[].devProductName`).
- Model with 0 print files: toast "Couldn't pick A1 on this page. Choose your printer in the Print Files row." shown at ~11 s.
  Model with an A1 file: A1 chip selected, no toast. Wording on a 0-file page is slightly off (no row to choose from); left as is.
- CDP eval helper now lives at `~/orca-android-tools/cdp.mjs '<js>'` (needs `log.tag.OrcaWeb DEBUG` + restart, then
  `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`). Cloudflare box after restart: `mclick.ps1 471 603` on WSA.

## 2026-10-16 Moved from HANDOFF.md "User decisions" (implemented, see r166 entry)
- OpenSCAD: design view (main canvas) is the default; an **eye button** shows the design on the build plate and back, with
  the camera the user had on the plates, the project's objects hidden, and new designs on an empty plate.

## 2026-10-16 r273: MCP models_human_check (Cloudflare box) verified on WSA
- r273 = run 39003921743 (bd90c44: 6ad69ef r197 NPE fix + `models_human_check`), installed on WSA, SHIELD, phone (Chromebook offline).
- `ModelBrowser.humanCheck()` (ModelsScreen.kt) + `HUMAN_CHECK_JS`: finds the Turnstile iframe / `.cf-turnstile`, or on the
  full-page check ("Just a moment...", `.ray-id`) the empty closed-shadow host div; waits 2 polls for the spinner, taps
  (left + 21, top + 32) CSS px as a dispatched touch (view px = CSS * web.width / innerWidth), re-taps after 6 s (max 3), 25 s cap.
  Touch taps pass the check (checked first with `adb input tap` on WSA).
- WSA r273: fresh check -> "Passed after 1 tap(s), 12s: now on MakerWorld (makerworld.com/en)"; no check -> "No human check on ...".
- Helper: `~/orca-android-tools/cfcheck.sh [phone|wsa|<serial>]`. Not run on the phone yet (needs the Models tab on screen there).
## 2026-10-16 Android main merged at r273 (PR #29)
- PR #29 (merge commit e5d6173): r267 tab lines, MakerWorld printer chip + toast, AO removed, 6ad69ef NPE guard (no UI repro;
  normal engine path exercised on r273), MCP `models_human_check`. Work branch = main afterwards.
- 2026-10-16 Gemini tool-row test, try 1 (user): stopped with "The model made too many consecutive tool calls. Narrow your
  prompt or use system instructions to limit tool use." No reply to grade. Added a no-tools first line to the prompt.

## Movable / collapsible portrait tool row (verified WSA 2026-10-16, run 39007273736, Android 24fe798)
- Origin: Gemini test (reply + grading in `~/orca-android-tools/gemini-test/`): 0 hallucinated OLD blocks, 1 compile error
  (non-local return out of `withTimeoutOrNull`), drag-delta bug (prevPos in the moving button's old coordinates; fixed with
  `positionChange()`). Cost more tokens than writing it directly (memory `no-external-llm-code-tests`).
- `detectTapDragOrLongPress` on the + (PrepareOverlay.kt); LayoutLab `toolsCollapsed`, `toolsRowX/Y` (dp, -1 = default).
  Clamped in `Modifier.offset` every layout. The + has no tooltip (`ToolButton(tip = false)`; Tip = TooltipBox long-press).
- WSA checks: tap = Add menu; drag follows finger, no menu; clamped at edge; selection widens row -> shifts left, returns on
  deselect; long-press collapses/expands, no tooltip; position survives restart.
- WSA portrait now: display 1920x1080 @200dpi, `am task resize <task> 690 40 1230 1040` (phone-view.sh numbers are stale).

## 2026-10-17 Rotation crash fixed (r280)
- User (phone r278): rotating portrait -> landscape crashed. Cause: AppScaffold's BoxWithConstraints swaps the portrait Column for the
  wide Row in a measure pass; the focused PlateView is removed, Android hands focus to Compose mid-measure ->
  "performMeasureAndLayout called during measure layout". Fix: `ui/FocusGuardLayout.kt` around the ComposeView refuses focus while
  measuring / laying out. Repro: tap the 3D view (`input tap 636 900`), `settings put system accelerometer_rotation 0`, `user_rotation 1`.
- Claude-verified: r278 crashed on the repro (crash_report.txt); r280 (run 39011426374) survived 4 rotation cycles with taps, app alive. VERIFIED.
- CI now uploads `OrcaStudio-Android-mapping-r<N>` (R8 mapping.txt, 90 days); present on r280.

## 2026-10-17 r281 checks (Claude, phone)
- Slice button on the right end of the portrait grip row (plate switcher left), no floating copy: verified by screenshot.
- Grip/edge flicks failed on phone + SHIELD: draggable's onDragStopped speed is relative to the grip, which follows the
  finger. Injected `input touchscreen swipe ... 50` flings a static verticalScroll list, so injection is fine. Fixed in r283
  (`FingerSpeed`, root coordinates). The Slice ExtendedFAB had no text in the a11y tree (floating one too): label in r283.

### 2026-10-17 r283 fling crash (user)
- User, r283 phone: "crash came from 'fling', it's repeatable every time". Crash report: IllegalStateException "A MonotonicFrameClock
  is not available in this CoroutineContext" on main (Dispatchers.Main scope). Fix pending.
- r284 (run 39014187416, 72aa18b, Claude on the phone): portrait grip flick up (swipe 636 1950->1500, 40 ms) glides to
  full height, flick down (350->800) collapses; no crash. Slow 210 px drag only moves ~45 dp (by design). Wide PanelEdge
  and OpenSCAD grip flicks not tested; feel is the user's call.

### 2026-10-17 User reports on r284 (phone)
- "New crash when trying to open layout editor, instacrash." (Layout lab.) Fix pending.
- "object in 'preview' mode is selectable and moveable (this shouldn't be the case)". Fix pending.
- "shaders seem to have disapeared from preview section (post slicing) that may be desired i dont know the convention."
- "zfighting glitch ... internal to the object post slice, visible when you scrub the layer slider down to see inside the
  object at for instance half way, only visible when you scrub the slider and pan/move camera".
- "zoom feels too slow (when zoomed out) might want to add a variable acceleration curve dependent on zoom factor? I haven't
  attempted to adjust zoom sensitivity yet".

### 2026-10-17 r285 fixes (run 39017596686, Android dff57e4; Claude on the phone; installed phone + SHIELD)
- Layout lab crash: logcat UnknownFormatConversionException '=' from `getString` = "100 %" in `lab_pinch_speed`; now `%%`.
  r285: lab opens, label reads "Pinch zoom speed (100 % = ...): 60%". VERIFIED.
- Preview locked: `InteractionMode.VIEW` (PlateView: no tap pick, drag, rectangle, long-press/right-click menu; double tap
  re-frames). r285 Preview, before and after slicing: tap on the object selects nothing, a drag on it orbits (yaw changed),
  view_state "Mode view". VERIFIED.
- Preview cracks: r284 at layer 85 (view_set distance 45 pitch 55 yaw -60 target [138,132,17]) showed single dark pixels
  (T-junction cracks between layers) and a striped / notched seam corner. Path VS: bottom extends 0.15 h into the layer
  below (not under the bed), clip-space depth order (role lift * 2 + segment parity) * 5e-7 w, bottom corners pushed back
  6e-6 w. r285 4 frames at yaw steps: dots and seam glitch gone; left a +-2..6 grey-level regular grid only visible at 8x
  zoom with contrast stretch (GPU dither?). Full-height view normal. Claude-verified; the user's moving-camera look pending.
- Answered in chat: shadows are Prepare-only by design (desktop has none; AO removed r27x), asked if wanted in Preview;
  zoom: user to try the lab's pinch speed slider before a zoom-dependent curve.

## 2026-10-17 r286 printer controls (Claude-tested on phone, idle A1)
- Run 39018606739 (a9ace29). Device tab Controls card renders (Control/XYZ tabs, nozzle/bed, speed chips, light, part fan, camera).
- Light toggle verified on the real printer: the ledctrl `system` payload works over obn (chip follows the printer's lights_report); toggled back off.
- Speed chip (Silent) did NOT change spd_lvl while idle: probably the printer ignores print_speed outside a print (untested during a print).
- Not tested: heating, extrude/retract, jog/home, fan > 0 (need the user's OK).
- 2026-10-17: speed chips now `enabled = status.isActive` (06b958b, unbuilt); mid-print behaviour still untested.

## 2026-10-17 r286 printer controls, active tests (Claude, phone, idle A1, user OK given)
- Nozzle target 180: temp rose 22 -> 75 in 20 s, 180 in ~45 s; Extrude/Retract enabled at >=170. Bed target 40: 25 -> 41 in ~60 s. WORKS.
- Extrude 10 / Retract 10, Home, X+10, X-10 sent without UI error; physical motion NOT confirmed (Claude cannot see the printer): user-only check.
- Part fan slider 50 %: after a tab toggle the label returned to 0 %, so the printer's report shows 0 (command not applied, or the report lags): unresolved.
- Target 0 for nozzle/bed sent twice: nozzle cooled steadily (180 -> 81 in ~3 min) but the tile kept showing "/ 180°" even after Refresh: stale target display or 0 not applied (A1 may auto-cool idle). Unresolved; check BambuReport.kt:82 merge of nozzle_target_temper.
- User verdict 2026-10-17 (r286): "fan works for me"; setting fan to 0 turned it off but the slider jumped to 93 while the fan stayed off (stale/misscaled report). Commands tested by the user all sent (Claude's Home worked); EXTREMELY SLOW: hardware responds in ~2 s min, app takes ~5 s min to update; user suspects the commands aren't going over LAN. Handy has a similar bug (10 % steps, buttons lock while waiting, UI reverts if the message fails): the user finds it very annoying.
- Root causes found 2026-10-17 (source): every control used withHost = connect + disconnect (TLS+MQTT), the 5 s poll reconnected too; the A1 pushes only deltas and ObnHost kept the old snapshot (pushall only if empty), so changes made while disconnected (fan, nozzle target 0) stayed stale. Desktop reads the fan from `fan_gear` low byte (0..255), not cooling_fan_speed (DevFan.cpp:103).
- Fix c38c197 (run 39022527632): DeviceController.pollStatus holds one host while the Device tab polls (1 s); commands reuse it; ObnHost/BambuHost `synced` flag asks for a pushall (`fullCount`, >=20 keys, untested threshold) after each (re)connect; fan from fan_gear; fan slider holds the user's value 5 s. UNTESTED on device.
- r287 (c38c197) Claude-tested on phone 2026-10-17: installed on phone + SHIELD. Stale nozzle target gone after reconnect (pushall resync works); bed target 30 and 0 shown correctly; fan slider 50 / ~2 / 0 stayed put for 13 s with no jump. Latency NOT measured precisely (uiautomator dump = 3.1 s per sample; the change showed within ~1-2 samples): user to judge speed. Extrude/jog motion still unconfirmed.
- User verdict 2026-10-17 on r287: "Printer messages from phone basically immediate, UI on light toggle isn't. Light turns off, UI takes around 3 seconds to update." Command latency fixed; light-switch UI lag ~3 s open (fan 93 glitch and extrude/jog not yet answered).
- r288 (c9299c2, run 39023335180) 2026-10-17: light switch shows the tap at once (5 s hold, clears when the printer report matches). Compiled and built; installed on phone, SHIELD and WSA. Not device-tested (user away from the printer): user to judge.
- r289 (bdc39c9, run 39058968284) 2026-10-17: MCP `slice` + `slice_result` tools (McpTools.kt; slice waits up to 10 min, returns time/filament/layers/per-role/warnings, errors for empty plate, bad plate, no result). Claude-verified on WSA: plate 1 sliced in one call (1h 53m, 24 g, 450 layers, no warnings); error cases ok. Installed WSA + SHIELD (phone offline, user at work, WSA = test device). Remaining MCP ideas: `app_state`, `log_tail`, `perf`.

## 2026-10-17 r291 MCP Rin-demo gaps #1 #2 #4 #5 (a99e13c, run 39091753615; Claude-verified on WSA)
- Installed r291 on WSA, phone, SHIELD. With the design editor open (design_describe), view_state answered at once: plateView()
  (McpTools.kt) switches to Prepare (unless Preview) and sets scadHidden; AppScaffold wide branch now honours scadHidden with
  the OpenSCAD reopen button (seen in the WSA layout dump).
- view_set {view front, object 1}: yaw -90 pitch 0, figure fills 336x588 of the 726 px view; left keeps yaw 180. Errors: object 9
  -> "No object 9", fit + object -> error (d290fd1, pushed, unbuilt: checks before the camera moves).
- design_set name "Rin figure" shows in design_describe. shape_update extra.revolve on a sketch refits (20x20x10 -> 20x20x20);
  revolve 0 keeps the height (same fitSketch as sketch_set); on a cylinder -> "only for sketch shapes". Test sketch deleted.

## 2026-10-17: settings audit + object support greying (r293, Claude-verified on WSA)
- User report (r291): filament/process editors showed bed temp "First layer"/"Other layers" several times; "critical that
  we verify validity of settings... so I don't damage anything". Cause: `extract_settings_layout.py` dropped Tab.cpp's
  multi-option Line labels. Keys were always right (rows bind def.key). 1bfe89f: layout JSON carries `lines` (key -> row
  label); editor, search, tooltip, Compare show "Cool Plate · First layer". 40 shared-row options (overhang speeds,
  bridge, nozzle range, chamber, nozzle, 6 plates, fan thresholds, exhaust, fan speed-up, resonance, input shaping).
  Script audit of all 552 rows (print 367, filament 94, printer 91): every key in PrintConfig.cpp, no key twice; only
  same-label pairs left = tree support normal/organic (toggle_line shows one set, Android uses ConfigManipulation).
  WSA r293: filament editor reads Nozzle / Cool Plate (SuperTack) / Cool Plate / Engineering / Smooth PEI / Textured PEI
  · First/Other layers.
- User report (r291, "Object setting"): support options stay greyed after enabling support. Cause: object/part/range
  editors used the process's option states. 44f68d9: `optionStates` takes the editor's settings and applies them over
  the process (GUI_ObjectSettings.cpp update_config_values). WSA r293: object support off -> rows `off`; on -> editable.

## 2026-10-18 d290fd1 verified on phone r293; Rin gaps #3 #6 #7 (f743435, r294)
- Phone r293 (MCP, user said use phone for adb): view_set {view fit, object 1} -> "Give view fit or object, not both"; {object 9} -> "No object 9 (there are 2)"; {view front, object 2} frames that object. (Arg is `view:"fit"`, not `fit:true`.)
- f743435: design_describe ends with a "Fit check:" line (top-level solids' bounding boxes; holes ignored): separate pieces with the closest gap, lowest point above/below the bed. Boxes of rotated shapes are bigger than the shape, so "touching" can be a false positive, "apart" never. Docs in the tool texts: mirror is about the shape's own pos, repeat angle rules, slice already returns the result (#6 #7). Compiled; build run 39095229019; untested on a device.
- r294 (run 39095229019) Claude-verified on phone: sphere at x 60 z 25 -> "2 separate pieces ... #1, #2; #3; closest gap 39 mm"; moved onto the boxes -> "one connected piece on the bed"; all lifted 5 mm -> "lowest point is 5 mm above the bed". Installed phone, SHIELD, WSA. App force-stopped.
- 2026-10-17 Rin replay (Sonnet 5.5, WSA r294): rin.json replays cleanly via MCP, 2h54m/28.6g/668 layers, no warnings; details DEMO-rin.md "Replay".

## 2026-10-17 User verdicts on r294 (phone)
- Slice button in the drag handle: "confirmed working", resize slider "also works". VERIFIED (user, r294).
- Object editor support greying (44f68d9): "confirmed fixed". VERIFIED (user, r294).
- Settings row labels ("Cool Plate · First layer", 1bfe89f): "confirmed fixed". VERIFIED (user, r294).
- Flicking the handle docks/nests it closed: works (user, r294). Wide PanelEdge + OpenSCAD grip flicks still untested.
- NEW minor bug (user, r294): flicking the portrait Prepare sheet makes the build plate "jello jiggle" right after the resize, then snap back to normal. Backlog.
- Preview zfighting remains (user, r294): two intersecting filament paths show a very fast stroke/flicker where they overlap; only when looking at internals with the layer scrubber and only while the camera moves.
- Light switch: "confirmed fix", near-instant on and off in UI and on the light (user, r294). VERIFIED. Not always low latency but it doesn't need to be.
- Fan slider still laggy (user, r294): rapid up/down sets follow the values but the UI jumps randomly (probably the printer's reports); set 0 -> 100: fan starts after a few s, ~5 s later the slider slides to a random number (hardware unchanged), 1 s after it jumps to the right spot. Handy has the same (user believes). Cause (source, ControlCard.kt): the 5 s hold is a timer from release, then the slider follows whatever the printer reports mid-ramp/stale.
- Extruder controls not found in the printer tab (user, r294): they are the two arrow buttons right of the Nozzle tile on the Control tab, disabled below 170 C / while printing (ControlCard.kt:85), easy to miss.

## 2026-10-18 User verdict on r296 (phone)
- Plate "jello" after a portrait handle flick (fix afdba1a, `steadyThroughGlide` in AppScaffold.kt): "flinging jello bug fixed". VERIFIED (user, r296). Cause: the GL view resized every glide frame; fix keeps its largest size through the glide, resizes once.

## 2026-10-18 r297 preview z-fight fix (run 39113308619, Android c1c9c1f; Claude on the phone)
- Cause: the depth order was role lift + segment parity only (Shaders.kt ~392), so two crossing lines of one role with the same parity tied; the shared top face tore as the camera moved. Fix: order = lift * 6 + direction bin (3 bins of 60 degrees, edges at 7.5 + 60k degrees) * 2 + parity; bottom push 6e-6 -> 1.6e-5 w.
- Check: plate 1 "Shape design" (0.20mm Standard, crosshatch), layer 90 / 170, view_set yaw -60/-59.7/-59.4, pitch 60, distance 40, target [168,102.6,18], then the Preview tab. r296: torn stripes at the sparse-infill X crossing, different each angle. r297: steep line wins cleanly in all three. Claude-VERIFIED; the user's moving-camera look pending. Shot: orca-android-tools/shots/zfight_r296_vs_r297.png.
- Not covered: adjacent parallel lines of one role that overlap (same bin, parity only) could still tie.

## 2026-10-18 r298 preview seams visible (run 39114730093, Android d08415a; Claude on WSA)
- User r297: "'seams' don't show in preview section when toggled". Cause: seam/retract markers sit on the bead's centre line at its top (GCodeProcessor.cpp ~5390), inside the outer wall's lifted box (PATH_VS lift 0.04 mm + depth order), so the point's one depth value lost from every angle.
- Fix: POINT_VS pulls kinds 0-3 toward uEye (xy * 0.35 mm past the half width, z * 0.07 mm past the lift; measurement points unchanged); PlateRenderer sets uEye.
- Check: WSA plate 1 "Shape design" sliced, Preview, view_set rear + object 1: Seams on = white columns on the arm edges and head top (1173 px > 215 grey), off = 0 px. Shots: orca-android-tools/shots/seams_r298_*.png. Claude-VERIFIED. Untested: retraction markers, top view (whether a seam one layer down peeks through). Installed WSA, phone, SHIELD.

## 2026-10-18 pruned from handoff
- Handy decomp (HandyAppExtractDecomp, outside the repos) wrapped up 2026-10-18; Studio fan-sync answer is in the backlog fan-slider line. Rin demo gaps all done (DEMO-rin.md).

## 2026-10-19 user verdicts (r304)
- Preview z-fight (c1c9c1f, r297+): USER VERIFIED r304: "The z fight is fixed."
- Corner-drag resize (r301-r304): still broken r304: "corner drag is still finicky, touch target seems too wide". User wants live resize
  gated behind the long-press menu (no accidental resizes) and Ctrl+T (transform) as the keyboard shortcut.
- Pen input (r290): user: "put pen testing in backlog (super low priority, just wanted it put in project for a later date)".
- New (user r304): object drag should switch horizontal/vertical with the camera angle like upstream Orca; compare upstream drag logic.

## 2026-10-19 r305 resize gating + drag plane (Claude-verified, WSA main window)
- ed8b77e, run 39219555564. Object 1 selected, plate_describe before/after:
  - Resize off, corner drag (corner off the mesh): orbited, no scale change.
  - Iso, mesh-middle drag 100 px right: X 130.6 -> 190.3, box centre moved 98.5 px (follows the finger).
  - Front view (pitch 0), drag 100 px left + 20 px up: X 190.3 -> 128.3, Y unchanged (vertical dropped), box 98.5 px.
  - S: hint "Drag a corner of the box to resize" + Done; corner drag: scale 74.6 % -> 107.8 %. Camera also turned ~1.5 deg
    before the slop (fixed 72dd138, unbuilt).
  - Ctrl+T toggles; Esc ends it; long-press menu lists Resize, tapping it shows the hint; Done hides it; T opens the Text dialog.
- Hidden display on WSA: taps/swipes did not reach the app (no OrcaInput logs) 2026-10-19; user said abort it, use the main screen.
  orca-vd.sh `stop` never killed scrcpy (MSYS_NO_PATHCONV kept `//F`): fixed to `/F /IM` (the cause of the orphaned scrcpy runs).
- (2026-10-19, user, r305) "two finger drag logic glitches out and thinks im trying to zoom sometimes ... it's fluid sometimes,
  othertimes not". Open (backlog).
- (2026-10-19, user, r305) Two-finger pan: "finish it, still broken". Drag plane: "a drag from under 'rin' going off build plate
  in z- direction should drag z+ but it wants to y+ or y-"; "when the camera reaches the plate level, it swaps to draging in
  vertical to horizontal"; clarified: two-finger CAMERA pan, not object drag. Open (backlog).
- (2026-10-19, user, r306) Low-view pan: "it does pan the way i would expect in the directions i would expect" (VERIFIED).
  New: "It misses two finger touches probably 20-30 percent of the time" (pan doesn't start). Open (backlog).
- (2026-10-19, user, r307 phone) Controlled run: "done, 1 failed (number 9)". Log: 8 PAN, 1 PINCH locked at 54 ms (gap 30 vs shift
  15, slop 24; then shift 172 with no pan). Fix e5b975c (r308): PINCH -> PAN once shift > 3 slops and >= 1.5x gap.
- (2026-10-19, Claude, r308 phone) User asked Claude to test pans. Scripted jittery two-finger landings (pan2j.sh): 3/3 forced
  PINCH locks switched to PAN at shift ~80-90 px; 16/16 staggered/wobbled/diagonal/1->2 cases decided PAN. Follow-up 4192099 (r309):
  catch-up pan from the landing point on the deciding move.
- (2026-10-19, user, r308 phone) Two-finger pan: "it feels alot better but still misses sometimes". Still open (backlog).
- (2026-10-19, Claude, r310 = 4f37eb9, run 39228986041, phone) Z-pan stall = long pans flipping PAN -> PINCH near the landing point
  (gap/shift measured from landing). Script `pan2b.sh 650 800 400 200 8` (out and back, gap drifting): r308 flipped 3/3, r310 stays
  PAN 3/3 (switches use the last 250 ms). Landing locks still recover 3/3, pinch in/out stays PINCH. r309 skipped (catch-up replayed
  earlier pan segments). r310 installed on all 4. User feel pending.
- (2026-10-19, user) Asked if the workflow is efficient; said "yes to both": (1) local APK builds instead of 7-min CI runs,
  (2) unit tests for pure logic, starting with the two-finger classifier.
- (2026-10-19, Claude, r311 = 2b9dee5, run 39231442360) Installed on all 4. Phone scripts: `pan2b.sh 650 800 400 200 8` 7/8 stay PAN;
  1/8 flipped PAN -> PINCH at the turnaround (last ~280 ms: shift ~0 while the scripted gap drifts 80 px) and back to PAN 210 ms
  later (synthetic; real fingers rarely spread 80 px while reversing). batch2: base 3/3 PAN, forced landing PINCH locks 3/3 -> PAN
  at shift 79-93 px. No regression vs r310. User feel pending.
- (2026-10-19, user, r311 phone) Two-finger pan: "pans feel fine" (VERIFIED; misses + Z-pan stall closed). New bug: pinch zoom-out
  stops as soon as one finger crosses the object (backlog).
- (2026-10-19, Claude, r312 = run 39233411471, phone) Pinch zoom-out stopping: cause = ScaleGestureDetector ends its gesture
  below its min span (~27 mm, ~500-600 px on the phone). r311 by pinch.sh: gap 800->500 zoomed, 500->240 and 240->80 did nothing.
  Fix: two-finger zoom done in PlateView's ACTION_MOVE (pinchZoom, same pad/speed); the detector only drives double-tap-drag.
  r312: 500->240 x1.65, 240->80 to the 10000 mm cap, 80->720 zooms in, 800->80 over the object x4.25. Installed on all 4. User feel pending.

## 2026-10-19 Android main merged at r312 (PR #31)
- PR #31 (merge commit 1ecdace): 47 commits r277-r312 (crash fixes, portrait grip/flick, preview lock/z-fight/seams, printer controls,
  MCP view/slice/fit check, settings labels + greying, selection styles, gated resize, two-finger classifier + pinch fix).
- User chose "Whole branch r312" over stopping at r289: includes untested pen input (7d3a263), fan slider hold (298ee9f) and
  Extrude/Retract F150 (e13bebb); their backlog lines stay open. Work branch = main afterwards.
- (2026-10-19, user, r312) Zoom feel: "I think 100% is fine, maybe just a tad too slow when zoomed out and a tad too fast zoomed
  really far in. BUT this may be due to Perspective/Orthographic views and what I'm used to." Asked for the missing view (ortho) first,
  zoom fixes after their verdict. Also: "Drive Chromebook, build mcp tools".

## 2026-10-19 r314 orthographic camera (run 39238373782; Claude on the Chromebook)
- Camera.perspective (setting perspective_camera, default true like the desktop): ortho half height = distance * tan(FOV/2) so it
  matches perspective at the target; eye sits ORTHO_BACK 2000 mm behind, near 1 / far eye + 2000. View menu Perspective /
  Orthographic (check mark), K toggles (PrusaSlicer's key; Orca only has the menu), MCP view_set projection, view_state reports it.
- Chromebook r314: Fit plate perspective vs ortho = parallel plate edges in ortho; scroll-zoom 25 in (cube face fills the view,
  no near clip) and 50 out (plate a small diamond, no far clip); K switches back. Fine stripes on the close-up surface exist in both
  modes (not ortho). User's zoom-feel verdict pending.
- MCP app_state / log_tail (own-pid logcat, LogMask masks token/code/password/key/cookie/auth values) / perf (FrameStats, last 240
  frames) built r314; JVM tests McpDebugToolsTest 5/5. NOT run on a device: Chromebook has no root and run-as is disabled in ARC
  (debug APK can't expose its token); needs WSA or the phone (app not running there; launching takes over a screen).
- (2026-10-19, user, r314) Bug: "painting brush very slow/lagging behind stokes over a few seconds gets worse over time." Open (backlog).
- (2026-10-19, user, r314) Feature: "Double clicking directly on an object should reframe it in a focused way zoomed in looking at it head on
  object height. Double tap on object returns to previous view active before double tap. Double tap empty space on build plate remains the same." Open (backlog).
- (2026-10-19, user, r314) Ortho: "I can't tell the difference, is it active?" (Claude had left the Chromebook in perspective after the K test.)
- (2026-10-19, user, r314) "I prefer perspective, but that's nothing to do with zooming and just the looks on plate. Zooming is fine I
  don't want to add complexity where there doesn't need to be any. Keep Ortho if there's legitimate uses for it, if not nest it into the
  appearance setting as a toggle since other slicers use it." Zoom: CLOSED, no curve. Ortho moved from the View menu to a More >
  Appearance switch (r316; K still toggles).

## 2026-10-19: r316 paint batching + double tap focus (Claude-verified, Chromebook)
- Double tap on the cube: head-on front view, zoomed to fit; again: back to the view before; on empty canvas after an orbit: plate framed, selection cleared.
- Seam paint 1.5 s diagonal stroke: whole stroke drawn in the screenshot taken right at stroke end (no lag tail). Color paint with filament 1 on an orange cube shows nothing (same color, expected). Test paint cleared.
- `input tap X Y & sleep 0.12; input tap X Y` in one adb shell = a double tap on ARC.

- r316 More > Appearance "Orthographic camera (K)" switch (Chromebook, 2026-10-19): on = flat head-on cube, plate as a line;
  K in Prepare turns it off and the switch follows. MCP app_state / log_tail / perf answer on WSA r316 (perf without a
  3D view on screen says so; WSA window not in front).
- (user asked "verify the 3d outlines", 2026-10-19) Selection styles on Chromebook r316: outline+box (default), x-ray only,
  rim glow, wireframe all draw; chips toggle each style. X-ray behind another object: hidden part of the back copy shows
  through the front cube with its outline. Copy deleted, styles back to outline+box.
- 2026-10-19: main merged at r316 (PR #32, c47c8a6, merge commit; whole branch, all verified).
- (2026-10-19, user, r316) "Look of seams and selection styles confirmed good." Preview seams toggle and selection styles look: closed.
- (2026-10-19, user, r316) Paint lag NOT fixed: "Fast and fluid at first then the longer I go it's like it's building cache and hitting a bottleneck." Reopened (backlog).
- r318 Chromebook (Claude, 2026-10-19): 40 seam strokes covering the whole cube (3414 tris), then a Block stroke: fully drawn in the screenshot at stroke end (no lag). Timing log silent: Boost log level is warnings only (OrcaEngine.cpp:141), raised to warning in r319. Preview moire gone far out; faint dashes left at medium zoom (blend 2-5 px period), widened to 3-9 px in r319. r318 installed on phone, SHIELD, WSA, Chromebook.
- r319 Chromebook (Claude, 2026-10-19): preview moire gone at 6 zoom levels except faint dotted arcs on a low-angle side face at the closest (top strip < 1 px with layers 9+ px apart) -> r320 also blends when the thinner face is < ~3 px, each face keeping its own shade in the mean. Paint log, Stanford Bunny (52716 tris, sample added to plate 1), 60 seam strokes: select 6-13 ms, paint mesh 13 -> 55-86 ms growing with painted area, scene ~1 ms; one selector reload per stroke start (cause unknown, ~10 ms). r320: skip re-extracting states that were empty and not painted, cache file bytes per copy, one write per copy; log splits extract / bytes / triangles; app logs paint mesh read > 20 ms and GL upload > 10 ms (OrcaRender).
- r320 Chromebook (Claude, 2026-10-19): same 60 seam strokes on the painted bunny (~100k painted tris): paint mesh 15-28 ms (r319: 55-86 ms at fewer tris); extract/bytes mostly 0-7 ms, rest = writing ~10 MB per call; app read ~23 ms, upload ~11 ms. First call after app start: select 250 ms (deserialize). Preview moire: bunny + cube at 4 low-angle zooms clean (faint scattered dark single pixels remain, minor). r321: no file write / version bump when the parts are unchanged.
- r321 Chromebook (Claude, 2026-10-19), bunny: 30 strokes over painted area -> only 30 calls over 30 ms (one per stroke start); 20 strokes on new area (ears, base) -> 143 slow calls: select 12-25 ms, paint mesh 27-44 ms (extract 3-7, bytes 3-11, rest = ~12 MB file write), app upload 12-14 ms; ~50 ms a call. Painted tris 102k -> 127k.
- (2026-10-19, user, Quest, run 39300569740 / apk r323) "poking around everything works very good." Paint: "no longer slows over time during strokes" (lag-over-time FIXED); "paint rate is slight slow, if you paint too fast it results in scattered dots instead of a smooth line." Joystick zoom "VERY sensitive": wants a zoom sensitivity slider in Settings. Bugs: resize touches through the model to far edges; upside-down cone slice error not visible; prime tower selection sticks; paint panel should collapse/move. New: extended paint tools, phone<->Quest companion link (backlog).
- r324 WSA (Claude, 2026-10-19, run 39332259477): fast paint strokes (40 ms swipes over a cone) paint continuous bands, no dots (DoublePointCursor joins). Notices: object off the bed -> card "Slicing plate 1 failed / No object lies completely inside the print volume" at the top of the 3D view; X leaves a red "!" badge; badge reopens it; a good slice clears it. Tool card folds to its title and drags by its header; dragged to a corner it went under the nav rail (window clamp) -> r325 clamps to the 3D view. Prime tower: tap leaves no highlight (r324); tower only appeared after a move following the first colour stroke -> r325 rewrites the scene mesh. Upside-down cone without supports: not reproduced (WSA profile has supports on; MCP can't change process settings).
- r325 WSA (Claude, 2026-10-19, run 39334485418): Clear painting removes the prime tower at once; one colour stroke brings it back without a move; tool card dragged toward the top-left corner stops at the 3D view's corner (not under the rail). r325 installed on WSA, SHIELD, Chromebook, phone, Quest.
- (2026-10-19, user, phone r325) "Paint goes on incredibly smooth, no issues." Paint feel: VERIFIED. "The prime tower fix works perfectly." Tower select/deselect + tower on first stroke: VERIFIED. Moire fix (r317-r320): "worked, isn't 100 percent, may have been too aggressive" (partly verified; tuning open, backlog). New bugs/requests logged in backlog: paint window expands off-screen after collapse + drag to top-left (same as the Design window), tower should have no bounding box / settings and just show an x-ray outline while touched/moved, top-surface lines jiggle while orbiting, z-fight on the prime tower, paint needs one-finger camera orbit when an object is selected, paint edge-detection toggle.
- r326 Chromebook (Claude, 2026-10-19, run 39344743609): colour paint tool on the sphere: one-finger swipe starting on empty space orbits the camera; swipe on the sphere paints a stroke, camera still. Paint card unfold after fold + drag to the top-left corner: FAILED (card grew upward off-screen; boundsInWindow() is clipped to the window so the overhang was invisible). Fixed r327 (positionInWindow + size); tower x-ray outline not tested.
- r327 WSA (Claude, 2026-10-19, run 39346587455): prime tower drag shows the x-ray outline only, no box. Paint card folded, dragged to the top-left corner, unfolded: stays fully inside the 3D view (clamp fix works). Chromebook was in use, so tested on WSA.

## 2026-10-19 Preview wall leak / tower z-fight fix (Claude, phone, local debug build)
- Seen on WSA r327 first: prime tower walls in Preview showed rows of light dots forming contour ellipses that moved with 0.3 degree camera steps.
- Cause: PATH_VS pushed each box's bottom vertices back in depth (1.6e-5 w, so the overlap into the layer below loses there). The push fades linearly up the wall, so right above the joint the wall still sat ~0.06 mm (d 60) to ~1 px (far) behind, and the lower layer's top face (inside, behind the wall) won in a sub-pixel strip: aliased into dotted rows / moire. The old "layer lines" at mid zoom were mostly this strip.
- Fix: box template = 12 shared corners in 3 rings (top, layer bottom, overlap bottom); only the overlap ring is pushed; walls below the top ring take the top direction bin; faces read their normal flat from each triangle's last corner (normal rebuilt in PATH_FS from vRight/vUp/vDir). PATH_FS adds a ~1 px joint groove (fades from 7 to 4 px a layer) so layer lines stay without moire.
- Checked on the phone (debug app, plate 2 tower moved on the bed, 0.3 deg / d 90-260 shots, old vs new): dotted rows and contours gone, grooves at d 90, flat at d 160+; sparse dark pinholes remain (old had them too). Cube walls on plate 1 unchanged. Orbit fps (glfps): plate 2 r327 27 fps -> 35-47; plate 1 at the 165 Hz cap both. A 32-vertex version (rings without sharing) had dropped to 25 / 105 fps.
- Untested: WSA (GL translation and the last-vertex flat rule), Chromebook, the user's eye.
- Top-surface jiggle (r329, Claude, phone hidden display, 2026-10-19): plate 1 cube top, pitch 50, d 30, yaw -60/-59.8/-59.6: r328 showed comb z-fight in the overlap strip of neighbouring top lines, moving per 0.2 deg. Parity XOR direction did nothing; 4x depth steps removed it -> kept with the near plane at distance/50 (min 1) so the order bias costs about the same mm as before. Overview fit and cube section (sparse crossings) look the same as r328.
- (2026-10-19, user, phone r329, 5 screenshots plate 2) Moire blend goes too far: "at this zoom level with the beeper plate, I should see layer lines ... to get layers to show up I have to zoom too far in. Double clicking a selected object frames the object, at that zoom the layer lines should be visible and pristine. If eliminating moire isn't 100 percent possible without degrading the useful visual data (layer lines) then I'd rather deal with it and keep the layer line detail." Spheres show lines only very close; framed beeper plate (top view) shows none. "Z-fighting occurring still": circled a triangular notch where a red support line meets a diagonal one (layer 221/301, close zoom), "only visible when panning camera or zooming", likely others. Tower / top-jiggle not separately judged.

## 2026-10-19 Resize overhaul: desktop scale gizmo (0c35daf, Claude-verified WSA debug app)
- Sphere (unrotated), yaw -60 pitch 40: 3 front corners + red X + green Y + blue Z shown, back corner/midpoints hidden.
- X drag: 60.1 -> 117.7 mm on X only, centre kept, handle ended ~15 px from the finger. Z drag: top under the finger.
- Corner drag: uniform 0.866 on all axes, centre kept. Top view (pitch 89): ring on the box top, all 4 corners + 4 midpoints + Z.
- Level side view (pitch 0): front corners + Y shown; Z hidden by the box top at first, fixed (Z shows whenever the eye is above
  the box bottom); Z drag of 42 px moved the top 42 px. Cylinder rot [90,117,117]: corners only (as designed).
- Known limits: from an exact front view the X handles sit on edge-on faces and hide (orbit a little); handles draw over other objects.

### 2026-10-19 Slice error text (upside-down cone, Quest r323 report)
- Repro WSA debug (Claude): Add > Shape > Cone 20 mm on empty plate 3, `object_transform` rot [180,0,0], slice -> card said only
  "Slicing plate 3 failed / Errors": libslic3r wraps per-object errors in `SlicingErrors` whose own text is "Errors" (Exception.hpp:46).
- Fix 64420c3 (OrcaEngine.cpp catch -> upstream `Print::slicing_errors_message`): card now reads "Cone: One object has an empty
  first layer and can't be printed. Please Cut the bottom or enable supports." Claude-verified WSA (local native build). CI run 39375222936 (r334).

### 2026-10-19 Prime tower "partially outside" on painted plates (user phone screenshot, r333)
- User: plate 1 (painted sphere + box + 2 cylinders), green tower box near the front-right corner, slice -> "Prime Tower is partially
  outside the printable area". Same as the WSA plate 2 "tower drifts off the bed" (wsa-preview2.sh workaround).
- Cause: `OrcaEngine::tower_box` (OrcaScene.cpp) counted all painted colours as one filament (id collided with filament 1). The default
  Rib wall (PrintConfig.cpp wipe_tower_wall_type) squares the tower to the purge count, so the real tower was wider AND deeper than the box.
- Fix d14af27: count `get_extruders_from_multi_material_painting()` per volume. Claude-verified WSA debug: plate 2 slices untouched
  (12h18m). CI run 39376118065 = r335 (also has the slice error text 64420c3). User check pending.

### 2026-10-20 User verdicts, r335 (interactive check, device unstated)
- "Works": prime tower fit on painted plates (d14af27), slice error card text (64420c3), scale gizmo / resize overhaul (0c35daf),
  paint "Stop at edges" (d1355c9). All four VERIFIED.
- Same check: tower drag x-ray outline (r327) "Works"; paint one-finger orbit off the model (r326) "Works": both VERIFIED.
  Quest stick zoom setting (r324): not tested, user: "don't want to hold up merge". Paint card unfold (r327): STILL BROKEN: "doesn't go off
  screen but it collapses to its bottom edge and there's a rubberband effect when expanded to the top left corner. same exact issue we dealt
  with on the design editor window."
- Same check, preview: support z-fight notch (c116b71, r331) "Works"; top-surface jiggle (r329) gone, "Works"; tower wall z-fight /
  joint leak (05d553c, r328) "Works": all VERIFIED. Layer lines at the framing zoom (2d8d6aa, r330): "Still broken" (detail pending).

### 2026-10-20 Merge PR #33 (r335)
- Work branch r317-r335 merged to main as b98e6ad (PR #33, merge commit), on the user's "merge". Open in main: paint card unfold, layer lines at framing zoom; untested: Quest stick zoom.
- (2026-10-20, user, r335, 2 screenshots) Layer lines: orange box walls show sharp lines but "Distracting band of light visible in center of orange object image" (the 2-3 px fade zone); "Moire visible under table top" (beeper/table plate). Asked whether moire-free + sharp lines is possible at all; Claude: not by shader tuning (sampling limit), only supersampling. Item paused for the user's pick.
- (2026-10-20, user) Decision: fix the light band (shared mean brightness) and add supersampling when the camera stops, plus a Settings option to supersample while moving too. r336 (run 39379527770) installed on all devices.

### 2026-10-20 Paint card fold fix verified (r336)
- (2026-10-20, user, r336) Paint card fix 2d35887: "paint card rubberband fix works, collapses in top right correctly." Verified.
- Follow-up idea (user, low priority, logged in backlog): fold direction should depend on where the handle sits; the handle should want to stay near the top or bottom screen edge, so folding never leaves it mid-screen.

### 2026-10-20 Temporal accumulation + MSAA (r338) user check
- (2026-10-20, user, phone r338) "Incredible. Moire gone, layer lines visible." Verified: moire fix + framing-zoom layer lines (backlog "Moire"). One issue: table underside (bottom surface seen from below), left part's lines "completely smoothed away compared to right side" (screenshot in chat). Open, being fixed.
- Implementation (verified r338): 696c5a6 `Accumulator.kt` = 32 jittered 1x frames (Halton 2/3 + 5/7 sums, tent +-1 px) blended 1/n via GL_CONSTANT_ALPHA into RGBA16F (ES 3.2 or EXT_color_buffer_half_float/_float, else 2x `Supersampler`), cached once done, 1/32 refresh each second; `MsaaConfigChooser.kt` 4x MSAA on PlateView, falls back to none. Regime scale below 1 brought cylinder arcs back on WSA (0.7 faint, 0.5 clear, 0.35 strong): kept 1. MCP view_screenshot waits for `renderer.settled`.
- Underside fix cc0af56 (Claude WSA-verified 2026-10-20, r339 for the user): far-mean `wTop` = vWidth * abs(dot(vUp, v)), lTop uses the seen face's normal.

### 2026-10-20 Underside fix (r339) user check
- (2026-10-20, user, phone r339, screenshot plate 1 layer 232 from below/side) "Everything is good except these defects that I've circled in red": underside fix cc0af56 verified. Open: (a) table top (blue top skin) edges, two arrows at the skin border by the wall; (b) lying cylinder end cap near the bed: light streaks/shards. Investigating.

### 2026-10-20 Lit undersides (r340) user check
- (2026-10-20, user, phone r340, 4 shots: plate 2 layer 9 close/mid/fit, plate 1 layer 232) "Previous build did something to the entire output of lines on all layers. They look like blocks being built instead of smooth extrusion lines. Top surface color shifts depending on angle viewed." Cause (Claude): 6b465f8 `discard` on `!gl_FrontFacing` for top/bottom faces, but `uploadBox` winds every face clockwise from outside, so tops were dropped from above (insides of walls/caps showed). User GO: invert the test (keep lit undersides), re-check above + below.
- Underside details (moved from HANDOFF 2026-10-20): cause = the overlap's NDC push (PATH_VS `7.2e-5`, ~distance/550 mm) outgrew a layer zoomed out, so from below the bead's own top showed through. User: undersides "lit like a top". 6b465f8 = discard top/bottom faces seen from inside + bottom faces take `abs(vFace.y)`; its facing test was inverted (r340 blocky). Fix 1-char flip, r341 (run 39482253748): WSA abtop.sh (above, close+mid) clean, abshot.sh (below) no stepped patches vs base2. Rejected earlier: label rank for a bottom ring (no change), no push for it (stripes). Wall rims from below a bit lighter.

### 2026-10-20 r341 user check
- (2026-10-20, user, phone r341) "I've checked and everything looks perfect": tops from above (6b465f8 flip) + lit undersides verified. User asked about camera light: Preview toolpaths use fixed studio lights only (`shade()`), lab light sliders affect the Prepare mesh only; camera-light slider offered (easy), no GO yet.
- (2026-10-20, user, phone r341) on the r339 rim arrows + cylinder end-cap shards: "I've zoomed around and tried finding what that means and I can't notice anything defective in our current implementation" -> closed (not visible in r341).

### 2026-10-20 paint gap fill, support kind (WSA, local debug build)
- (Claude, WSA) Support painting, Enforce, cylinder: 3 mm strokes (about 20 mm2) stay at the max gap area 5.0 mm2; 1.4 mm taps vanish on "Fill gaps". Tapping a shape chip (e.g. Sphere) leaves Gap fill mode, so re-select the "Gap fill" chip before "Fill gaps". "Clear painting" worked. Odd: two green dots on the neighbouring plate stayed after Clear painting (probably painted earlier on another object; not chased).
