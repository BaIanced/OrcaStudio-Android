<div align="center">
  <img src="docs/icon.svg" alt="Logo" width="80" height="80">
  <h2>OrcaStudio-Android</h2>
  <p>
    A touch-first 3D-printing studio for Android tablets and phones, built around the real
    <a href="https://github.com/OrcaSlicer/OrcaSlicer">OrcaSlicer</a> engine running on the device.
  </p>
  <p>
    <a href="https://github.com/BaIanced/OrcaStudio-Android/releases">Download</a>
    ·
    <a href="https://github.com/BaIanced/OrcaStudio-Android/issues">Report a bug</a>
  </p>
</div>

> **Built on [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer) by SoftFever and the OrcaSlicer
> contributors (AGPL-3.0).** The slicing engine (`libslic3r`), the printer, filament and process
> profiles, the settings model and the calibration know-how are their work and are used with the patches in
> `android/patches/src-orca`. This app adds an Android interface, modelling tools and printer connections around
> it. It is not affiliated with OrcaSlicer or any printer manufacturer: please report problems
> with this app here, not upstream. OrcaSlicer's source is at
> <https://github.com/OrcaSlicer/OrcaSlicer>; the exact commit this app builds is in
> [android/UPSTREAM.md](android/UPSTREAM.md).

> **A personal project, shared as is.** Pre-release builds, no support and no promise of
> maintenance, no warranty. Check your G-code before long prints and use it at your own risk (see
> [License](#license)).

## What it is

Slice on a tablet or phone with the same profiles and results as OrcaSlicer on the desktop, model
and repair parts on the same device, and send the job to the printer. No server is involved:
OrcaSlicer's engine is compiled for Android (arm64) and runs locally. The desktop interface
(wxWidgets) cannot run on Android, so everything you touch is a new interface written in Kotlin
and Jetpack Compose, with OpenGL ES 3 for the plate and preview.

## Status: what is tested

- **Tested by the developer** on a phone, other Android devices and the Android subsystem for
  Windows: slicing, preview, the editors and the features below, mostly by scripted checks on a
  hidden virtual display. Newer features were checked this way only; they are not yet tested by
  anyone else.
- **Printing:** signed Bambu Lab printing (see below) has been used on a real printer. Moonraker
  and MKS hosts were tested against mock hosts only. The other host types (OctoPrint, PrusaLink,
  Duet, Repetier, ESP3D) and Bambu LAN mode are untested on real hardware.
- A JVM and instrumented test suite covers the engine bridge and much of the app logic
  (`android/app/src/test`, `android/app/src/androidTest`).

## Features

### Printers, profiles and settings
- **All of OrcaSlicer's printer, filament and process profiles.** Pick printers by vendor, model
  and nozzle; only those vendors' profiles are unpacked. Profile updates download only the files
  that changed.
- **Full settings editor** with the desktop's pages and groups, search, simple/advanced/expert
  modes, the desktop's rules for greyed-out and hidden options, per-field help, "changed only"
  view, compare, import/export (`.orca_printer`, `.orca_filament` bundles) and user presets.
- Several filaments with colours, flushing volumes, prime tower and plate types; filament tiles,
  a colour picker, a Bambu Studio-style filament mixer and bulk filament editing.
- Setup problems (for example a filament slot whose type differs from the loaded tray) show a
  badge that takes you to the slot; slicer errors appear on the 3D view with fix links.

### Prepare
- Load **STL, 3MF (projects with plates and settings), OBJ, STEP, AMF, DRC and SVG**, also via
  "Open with" and "Share". Add shapes, embossed text, SVGs and OrcaSlicer's sample models.
- Multiple plates, arrange, auto-orient, lay on face, move/rotate/scale/mirror (with gizmos and
  snapping), copies, cut, split, simplify, repair, measure, undo/redo, mesh check with markers for
  open edges and self-intersections.
- Parts, modifiers, support blockers/enforcers and negative volumes; per-object, per-part and
  height-range settings.
- **Paint shop** for supports, seams, fuzzy skin and colours, with controllable paint depth
  (uses a small patch to the engine, see *Building*).
- Variable layer height; pauses, colour changes and custom G-code at any height.
- All of the desktop's calibration tests (temperature tower, flow, pressure advance, retraction,
  max volumetric speed, VFA, input shaping, cornering).
- A touch-friendly mesh-editing set (sculpt, hollow, center/align, push/pull, bend/twist/taper,
  smooth blend, hull) and a **dimensions overlay** for sizes, holes and slots.

### Modelling (CAD)
- **Shape builder, sketch editor and OpenSCAD** on the device: parametric shapes, sketches with
  extrude/twist/loft/sweep, an asset library (peg and hole, hinge, gear, …) and an OpenSCAD editor
  with live preview, running OpenSCAD natively in the app.
- Optional **AI tools**: a local MCP server (off by default, token-protected) lets an AI client
  inspect the plate, edit designs and slice; every risky action asks for confirmation on screen.

### Preview and printing
- Background slicing with notification, cancel and "slice all plates"; automatic re-slice after
  edits (optional).
- Toolpath preview with layer and move sliders, colour schemes, travels, retractions, seams,
  G-code of the current move, statistics and external G-code files; realistic render with
  filament finishes and a section view.
- **Print doctor**: checks the plate and settings for common causes of failed prints.
- Upload and start prints on **Klipper/Moonraker, OctoPrint, PrusaLink, Duet, Repetier, ESP3D,
  MKS and Bambu Lab**; discovers printers on the network; the printer's own web interface (Mainsail,
  Fluidd, OctoPrint, …) in the Device tab; progress notification, a home-screen widget and print
  history.
- **Bambu Lab, including printers with LAN mode and Developer Mode off:** signed printing through
  [open-bamboo-networking](https://github.com/ClusterM/open-bamboo-networking) (AGPL-3.0), compiled
  into the app. You import your own slicer credentials (`slicer_cert.pem`, `slicer_key.pem`,
  `slicer_crl.pem`); they stay on the device and are never part of this repository or the APK.
  Device tab: temperatures, jog/home, fans, lights, AMS status and filament sync, camera.
- **Models tab:** browse MakerWorld, Printables and Thingiverse in the app and import models
  from them; a built-in 3D Filament Profiles page with "Log print".
- Orca Cloud sign-in and the OrcaSlicer plugin runtime (Python, started on demand after sign-in).

### App
- Tablet layout with side panel, phone layout with bottom sheet; keyboard, mouse, touch and pen
  input; a first-run coach strip and launcher shortcuts.
- The plate, presets and setting changes survive closing the app.
- Light and dark theme, optional Material You colours. The app's own interface is English; option
  names and tooltips use OrcaSlicer's translations.

### Not included
SLA printing, the desktop's CAD tab and anything that needs the desktop's network plugin.

## Installation

Download the APK from the [releases](https://github.com/BaIanced/OrcaStudio-Android/releases)
(currently pre-releases) and open it; allow installing apps from this source when asked. Requires
**Android 10 or newer on an arm64 device**.

On first start choose your printers. To send prints, open the **Device** tab and add the printer
(Android 17 asks for local network access).

Updates: add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) (turn on "include pre-releases") to be told
about new builds.

## Building

Releases are built by CI; there is no local native build.

- **CI:** `.github/workflows/android-apk.yml` cross-compiles the native libraries with the NDK
  (cached after the first run), compiles the app, signs it and publishes a pre-release. It runs on
  pushes to `main` and on manual dispatch. Release signing keys live in a protected GitHub
  environment; without them the APK is signed with a debug key.
- **OrcaSlicer stays unmodified.** `src-orca/` is a git submodule. Native changes are kept as
  patches in `android/patches/src-orca/*.patch` and applied by a CI step before the build; a patch
  that stops applying after an upstream bump fails the build. The same applies to
  `android/obn/` (open-bamboo-networking) and `android/openscad/`.
- **Kotlin only (fast loop):** with a debug build of the native libraries available,
  `cd android && ./gradlew assembleDebug`. A full local native build needs Linux, the Android
  SDK with NDK 28, CMake, Ninja, JDK 17+, Python 3, autotools and perl
  (`android/scripts/build.sh`; first run about an hour).
- **Tests:** `cd android && ./gradlew testDebugUnitTest` (JVM) and
  `./gradlew connectedDebugAndroidTest` (device).

Further reading: [docs/porting-notes.md](docs/porting-notes.md) (how the port works),
[android/UPSTREAM.md](android/UPSTREAM.md) (updating OrcaSlicer),
[docs/mcp-tool-layer.md](docs/mcp-tool-layer.md), [docs/PERFORMANCE.md](docs/PERFORMANCE.md) and
[CHANGELOG.md](CHANGELOG.md).

## Architecture in short

| Path | Content |
|---|---|
| `src-orca/` | OrcaSlicer, unmodified (git submodule) |
| `android/patches/` | Patches CI applies to OrcaSlicer |
| `android/deps/` | CMake superbuild of the native dependencies for Android arm64 |
| `android/core/` | Builds `libslic3r` and the JNI bridge `liborca_jni.so` |
| `android/obn/`, `android/openscad/` | Bambu signed-printing library and OpenSCAD, built for Android |
| `android/app/` | The app: Kotlin, Jetpack Compose, OpenGL ES 3 (`core`, `net`, `render`, `scad`, `ui`, `ai`, `plugins`) |
| `android/scripts/` | Build, resource packing and extraction helpers |
| `docs/` | Design notes |

The app talks to the engine through a JNI bridge (`OrcaEngine.cpp`); the settings layout and the
rules for greyed-out options are extracted from OrcaSlicer's own sources at build time, so the
editor follows upstream.

## Safety notes

- Slice, then check the G-code and preview before any long print; test new profiles with small
  parts first.
- Printer credentials (access codes, API keys, certificates) are stored on the device only and
  must never be shared or committed.
- Never leave a printer running unattended on an untested setup.

## Origins and credits

This project started from **[Orca-Android](https://github.com/cl1x/Orca-Android)** by cl1x, an
Android port of OrcaSlicer (a small app: tablet UI, settings editor, preview and printing over
the network, AGPL-3.0). That app provided the Android build of the native libraries and the first
interface; this repository has since grown far beyond it (a new rendering and editing layer,
modelling tools, printer integrations and more), but the foundation and the idea are cl1x's work.

**[OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer)** is the heart of it: SoftFever and the
contributors built Arachne walls, tree and organic supports, seams, the calibration tests, the
hundreds of tuned profiles and the translations. OrcaSlicer builds on
**[Bambu Studio](https://github.com/bambulab/BambuStudio)**,
**[PrusaSlicer](https://github.com/prusa3d/PrusaSlicer)** and
**[Slic3r](https://github.com/slic3r/Slic3r)** by Alessandro Ranellucci and contributors. Also:
[open-bamboo-networking](https://github.com/ClusterM/open-bamboo-networking) for signed Bambu
printing, and the libraries listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). If you
want to support the slicer itself, please do so upstream:
[OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer) ·
[orcaslicer.com](https://www.orcaslicer.com) ·
[sponsor SoftFever](https://github.com/sponsors/SoftFever).

## Contributing

Pull requests are welcome, but please don't expect quick reviews. You are just as welcome to fork
it. Problems of the slicing itself (wrong toolpaths, profile issues) are best reported
[upstream](https://github.com/OrcaSlicer/OrcaSlicer/issues) after checking that they also occur in
the desktop version.

## License

Licensed under the [GNU Affero General Public License v3.0](LICENSE), like OrcaSlicer. This
program contains OrcaSlicer 2.5.0-dev, used with the patches in `android/patches/src-orca`, combined
with an Android user interface and build system. The bundled components and their licenses are
listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This program is distributed in the hope that it will be useful, but **without any warranty**;
without even the implied warranty of merchantability or fitness for a particular purpose. See the
license for details.

"OrcaSlicer" and printer brand names belong to their respective owners.
