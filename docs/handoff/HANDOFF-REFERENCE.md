# Handoff reference: devices, tools, recipes (read on demand)

How-to material moved out of `HANDOFF.md` on 2026-10-13 so the hot file holds only rules, state and open work.
Read the matching section before driving a device or building; grep the rest (`grep -n "Chromebook" HANDOFF-REFERENCE.md`).
Nothing pending lives here: open / untested items stay in `HANDOFF.md`.

## Test devices
**Phone** = main test device (root). **Chromebook's Android (ARC)** = big-screen layout checks (user wants the app there).
**WSA** when both are away. The user drives sessions from the phone: ask before taking over its main screen; use the
headless display below instead (no takeover needed). Don't drive WSA's own window while the user uses the PC.

### Headless display (`~/orca-android-tools/orca-vd.sh`, verified 2026-10-19 on the old phone)
- 2026-10-19 current phone: first `start` failed (scrcpy "Device disconnected"), the retry worked (with `PKG=...debug`); retry once before falling back to the main screen. Pass `PKG` for the debug app.
- `sh orca-vd.sh start|stop|status|shot <out.png> [w] [crop]|tap X Y|swipe x0 y0 x1 y1 [ms]|ui [out.xml]`. `start` opens a
  scrcpy `--new-display` (1080x2340/420, hidden, no PC window), launches the app there, retries until resumed, and saves
  ids to `.vd-phone.state`. The user keeps the phone screen and the PC. `SHOW=1` = scrcpy window on the PC for
  shared viewing (user can click in it too; untested). The display lives only while scrcpy runs; `stop` after testing and
  check `tasklist | grep scrcpy` is empty (2026-10-19: two hand-started WSA scrcpy runs were left running ~30 min).
- No PC window (user, 2026-10-19): scrcpy starts hidden via PowerShell with `--no-window` (`-N` still opened a small control
  window that took focus). Debug installs: `adb install -r -g` (+ `pm grant ... POST_NOTIFICATIONS`) so no permission dialog lands
  on the phone's main screen. WSA: launch with `wsa-start.ps1` (parks the WSA windows off-screen, unfocused; minimized = no
  drawing; `-Show` brings them back); avoid `mdrag.ps1` there (moves the user's real cursor), use `adb shell input`.
- `shot.sh` / `phone-ui.py` target it with `. ./.vd-phone.state; export VD=$LOGICAL,$SFID`. `mcpao.sh` works unchanged.
- Layout differs from the real screen: get coordinates from `view_state` (3D) or a regex-cut `phone-ui.py layout` (2D),
  not a shot. No pinch yet (`pinch.sh`/`drag1.sh`/
  `draghold.sh` hit the physical panel only).
- `TARGET=wsa`: **don't use** (2026-10-19 taps/swipes never reached the app; user: use WSA's main window). It also had **no screenshot** (Android 13 SurfaceFlinger
  hides the display); `TARGET=wsa start window` = old windowed mode. Old research notes: `headless-display-notes.md`.

### Chromebook (ARC)
- Before launching: `dumpsys window | grep mCurrentFocus`; another app in front = the user is on it, don't take over (2026-10-19). Run the check alone, never chained with `am start` (2026-10-19 slip: launched over the user's app, then put it back).
- `<chromebook-ip>:5555` (memory `test-on-phone`), Android 13, arm64, 1920x1080 (app area ~1813x948, shelf below), **no root**.
- Install: `install-build.ps1 -Run <id> -Device <ip>:5555`. Launch:
  `am start -n io.github.baianced.orcastudio_android/app.orcaandroid.ui.MainActivity`
  (ARC draws only while unlocked: focus = MainActivity, not ARC's PlaceholderActivity). `ui.ps1 -Device <ip>:5555` works.
- Zoom the canvas: `input mouse scroll <x> <y> --axis VSCROLL,1` (repeat; works over adb). One-finger `input swipe` orbits
  (or paints with the paint tool on). Tuck the panel for room.
- Section view panel: X only hides the panel; the reset button (history icon) turns the cut off.
- The system file picker is a ChromeOS dialog (adb can't drive it); open files with a VIEW intent on the MediaStore URI
  (`content query --uri content://media/external/file --where "_display_name='x'"` -> `-d content://media/external/file/<id>
  -t application/zip --grant-read-uri-permission`), which works without root.

### Phone
- Serial: `sh ~/orca-android-tools/target.sh phone` (2026-10-19 the phone is an `ip:5555` entry and the Quest also shows as `adb-..._adb-tls-connect` + `ip:port`; target.sh probes: Oculus = Quest, feature `org.chromium.arc` = Chromebook, else phone; cache `.kinds`, delete it if a serial gets reused).
- Debug app on the phone: copy `files/session/*` + `shared_prefs/app_settings.xml` from the release app via su (chown to the debug uid), force-stop the release app (it holds the MCP socket name), run tools with `PKG=io.github.baianced.orcastudio_android.debug` (mcpao.sh, orca-vd.sh). First start shows the printer picker: Apply, then copy the session again. **Uninstall it (`adb uninstall ...debug`) when done** (user, 2026-10-19: two apps on the phone confused them).
- Setup: archive "Phone test setup", memory `test-on-phone`; `phone-ui.py` needs `PYTHONIOENCODING=utf-8`;
  pull binaries with `adb exec-out "su -c '...'"`; Delete key = `su -c 'input keyevent 112'`.
- **MCP on a real phone:** the `localabstract` forward works over wireless adb; the socket binds only after an
  app restart with AI tools on (archive "MCP on a real phone").
- Raw multi-touch: `~/orca-android-tools/pinch.sh` / `drag1.sh` / `pan2.sh` (two-finger pan) in /data/local/tmp, run via su
  (push with `MSYS_NO_PATHCONV=1` and a `C:/` source path).
- Shots: `sh ~/orca-android-tools/shot.sh <name.png> [width] [crop l,t,r,b]` -> `~/orca-android-tools/shots/` (screencap + crop/downscale).
- **GL frame rate:** the green number top left is the system refresh-rate overlay, not app FPS. Measure with
  `sh ~/orca-android-tools/glfps.sh [x0 y0 x1 y1]` (3 s orbit swipe, SurfaceFlinger timestats of the GL SurfaceView).
- Raw `sendevent` gestures are slow (a one-finger drag turns into a long press): use `input swipe x0 y0 x1 y1 <ms>`
  in the background and screencap mid-way. Pinch fingers must start off any button/badge (they eat the touch).

### SHIELD TV (USB serial in `adb devices`, the user's, rooted)
- Only touch it when asked / for installs. Copy the phone's settings: `reinstall-with-data.ps1`-style tar via su, excluding `files/session`.

### WSA
- adb `127.0.0.1:58526` (root via `su -c '...'`, Android 13 / API 33; `run-as` fails, release build); an Android TV box is
  also attached, so always pass `-s`. Close the app when done:
  `adb -s 127.0.0.1:58526 shell am force-stop io.github.baianced.orcastudio_android`.
- WSA stops drawing when its window isn't in front (taps lost, empty ui dump): focus it first (Alt + SetForegroundWindow
  trick from `wheel.ps1:17` / `tapf.sh`). WSA wedged (no window): stop WsaClient, `Start-Service WsaService`,
  `Start-Process wsa://<package>`. Lost DNS: `wsaclient /shutdown`.
- Real mouse drags (+Shift): `mdrag.ps1 x1 y1 x2 y2 [-Shift]`, real clicks: `mclick.ps1 x y [-Right]` (Android coords = Windows pixels on WSA); the builder's
  shape list scrolls only with a real mouse wheel.
- WSA 1080p: the editor panel is short; collapse the preview ("Hide preview") to reach buttons below the Customizer notice.
- **MCP on WSA:** never `adb forward tcp:X tcp:Y` (WSA routes Android loopback to Windows; the forward loops and wedges
  adb: kill adb.exe). Use `adb forward tcp:8765 localabstract:orcastudio_mcp`. Token: `su -c 'cat .../shared_prefs/app_settings.xml'`
  into a variable, never print it. Run adb calls in PowerShell jobs with timeouts while testing sockets.
- WSA -> LAN printer: run `wsa-lan.ps1 <printer-ip>` after each WSA start. Safe print-dialog test:
  `iptables -I OUTPUT -d <ip> -p tcp --dport 990 -j REJECT` (root), Print, Cancel, `iptables -D ...`.
- Closing the WSA window (`CloseMainWindow()` on the "OrcaStudio-Android" window) runs onStop (saves the session).
- WSA Preview close-ups: `view_set` (object/yaw/pitch/distance) then tap the Preview tab (52,165) keeps the camera; layer handle drag on y 833 (track x 450-1195); WSA `input` has no mouse scroll, and double tap doesn't frame in Preview.
- Phone view on WSA (`phone-view.sh`): back-burnered (user, 2026-10-13: "didn't look right"); notes in the archive.

### All devices
- The app restores `files/session/session.3mf` on start and saves it in `onStop` (and by auto-save every 60 s when
  changed). `am force-stop` skips the onStop save.
- Draft of the open OpenSCAD design: `files/openscad/draft.scad|json` (read with `adb exec-out "su -c 'cat ...'"`; the Code
  tab text isn't in the ui dump).
- Shape-builder data = CSG JSON in the object's `module` metadata (`scad/ShapeModel.kt`).
- System file picker (phone/WSA): plain `adb input tap` (focus.ps1 finds no app window while it is up), scroll with `input swipe`, avoid its Search (list broke); delete test
  files from `/sdcard/Download` after. Close with `adb shell am force-stop com.android.documentsui`.
- adb `input` has no Shift/Alt state: use `keys.ps1 -Keys <vk codes>` (WSA; real key presses; tap a row first for focus).
- Action snackbar ("Add"): VIEW intent on `scadzip-test.zip` (MediaStore id via `content query`, see "Chromebook");
  it shows ~10 s, screenshot 3 s after the intent.
- Test with `ui.ps1` text dumps; screenshots only when the look matters. After launching the app, wait until `ui.ps1`
  lists the tabs before tapping (the first tap after start is often lost).

## Build and tools
- WSA shader loop (2026-10-20): the debug app (`...debug`) holds the phone's session; `~/orca-android-tools/wsa-cycle.sh <prefix>`
  = build + install + slice + Preview + 3 sphere shots (`wsa-preview2.sh` alone skips the build). Black screencaps / MCP
  "No 3D view is on screen" = the PC display is asleep: `wake-display.ps1` (1 px mouse nudge; wsa-preview2.sh runs it).
  WSA 3D view crop (Preview, wide panel): 105,38,745,938.
- **Local APK (2026-10-19, ~7 s incremental, 3.5 min cold):** `sh ~/orca-android-tools/stage-from-apk.sh` (native libs + OpenSCAD/
  Python assets from the newest CI APK in `builds/`; redo after native changes), then the compile command below with
  `:app:assembleDebug` -> `~/build/orca-android/gradle/app/outputs/apk/debug/app-debug.apk` (`adb install -r "$(cygpath -w ...)"`).
  Package `io.github.baianced.orcastudio_android.debug`: side by side with the CI app, own (empty) data, different signing key,
  debuggable (`run-as` works). Use it for Claude's iteration; the user's installs stay CI builds. Needs `orca.python=python` in
  `~/.gradle/gradle.properties` (set).
- **JVM unit tests** (`android/app/src/test`, 2026-10-19): same env as the compile command plus `PATH="${PATH//\"/}"` (the system
  PATH's PowerToys entry ended in a stray `"` (user fixed it 2026-10-19; only shells started before need the strip) - it broke the test JVM launch: "main class Files"; `./gradlew --stop` once after
  changing PATH), then `./gradlew -q :app:testDebugUnitTest`. Results: `~/build/orca-android/gradle/app/test-results/`.
- Compile Kotlin locally before every push (12 s warm):
  `cd OrcaStudio-Android/android && JAVA_HOME="/c/Program Files/Java/jdk-22" ANDROID_HOME='C:\android_sdk' ./gradlew --console=plain -q :app:compileReleaseKotlin`
  Add `:app:mergeReleaseResources` when strings change, and read the exit code (a grep on the output hid a failure once).
- Local native build in WSL: `MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/Users/Vince/orca-android-tools/wsl/corebuild.sh`
  (25 min cold, seconds incremental; details in the archive).
- Native change on the debug app without CI (2026-10-19, ~3 min): `corebuild.sh`, then in WSL `llvm-strip -o /mnt/c/Users/Vince/build/orca-android/jniLibs/arm64-v8a/liborca_jni.so ~/orca-build/core/liborca_jni.so` (NDK 28.2 llvm-strip) over the staged CI libs, `:app:assembleDebug`, `adb install -r -g`.
- CI (`android-apk.yml`, dispatch only: `gh workflow run android-apk.yml --ref <branch>`; cache details in the archive):
  checks **19** `ObnNative` JNI exports (update when adding one). Release-signed since build 32.
- `~\orca-android-tools`: `install-build.ps1 -Run <id> [-Device <serial>]` (WSA default), `ui.ps1 [-Device] [-Shot png]`,
  `step.ps1`, `wheel.ps1` (real mouse wheel / drags), `drag.ps1`, `reinstall-with-data.ps1`.
- Debug logs / Models WebView CDP: archive "Logs and CDP (moved 2026-10-12)".
  CDP eval: `node ~/orca-android-tools/cdp.mjs '<js>'` (first page on localhost:9222; awaits promises).
- Cloudflare "Verify you are human" (r273+): `~/orca-android-tools/cfcheck.sh [phone|wsa|<serial>]` = MCP
  `models_human_check` via curl (taps the box, waits up to 25 s, prints the result). Models tab must be on screen.
  `mcpao.sh` takes `MCP_DEV=<serial>` for rooted devices other than the phone.
- MCP `view_state` (94bb1e6): camera, mode/tool, selection, screen-px boxes of plates/copies/towers (tap a box centre to select). Phone MCP: `~/orca-android-tools/mcpao.sh 'tool:{json}'` (token never printed). `adb pair` needs the code on the command line.
- Cost (measured 2026-10-17, WSA Prepare tab): `view_state` ~600 B (~150 tokens, 3D view only); `phone-ui.py layout`
  ~4.5 KB (~1.1k tokens, 2D UI only, cut it with a regex); a screenshot ~1.5-2k tokens. They cover different things.
- `gh run watch/view` needs `-R BaIanced/OrcaStudio-Android` when the cwd is outside OrcaStudio-Android (wrong repo otherwise).
- GLSL check: `python -I ~/orca-android-tools/extract_shaders.py <Shaders.kt> <outdir>` (.vert/.frag as 310 es), then in WSL
  `~/Android/Sdk/ndk/28.2.13676358/shader-tools/linux-x86_64/glslc --target-env=opengl -fauto-map-locations -fauto-bind-uniforms -o /dev/null <file>`.
- Native crash symbols: NDK 28.2 `llvm-addr2line` on the WSL `~/orca-build/core/liborca_jni.so` (offsets match across build ids).

## Environment and repos
- Working dir `C:\Users\Vince\OrcaProjectsGithub` with `OrcaStudio` (Flatpak; holds the handoff) and `OrcaStudio-Android`
  (`src-orca` submodule = OrcaSlicer). gh CLI logged in.
- The user's Chromebook Linux (Crostini, aarch64) runs the Flatpak; A1 printer is cloud-bound (Developer Mode off); the
  app is signed in to their Bambu account with PEMs imported.

| Repo | Notes |
|---|---|
| `BaIanced/OrcaStudio-Android` | cl1x/Orca-Android + signed Bambu printing via obn (static in `liborca_jni.so`, `android/obn`). Bot branch `upstream/orca-main` (never commit). |
| `BaIanced/OrcaStudio` | Flatpak fork; ours: `arm-build/` (README, NOTES-ci/obn/updates), `.github/workflows/arm-*.yml`. Handoff docs moved to OrcaStudio-Android/docs/handoff (2026-10-19). Latest release r9 (MakerLab tab). Details in the archive. |
| `ClusterM/open-bamboo-networking` | obn pinned v2.2.0 + local patches 0001 (cloud callbacks), 0002 (start_print try LAN first), 0003 (MQTT CA from `SSL_CERT_FILE`). |

- Handoff docs moved here from `OrcaStudio/arm-build` 2026-10-19; that repo is Flatpak-only now, untouched unless asked.
