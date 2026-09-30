# Orca-Android

**Orca-Android** is an unofficial, native Android port of [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) for tablets and phones. The
original slicing engine (`libslic3r`) is cross-compiled for arm64 and driven by a new touch UI
written in Kotlin / Jetpack Compose. Everything runs on the device – no server needed.

> **This port was created with AI.** The Android build system, the JNI bridge and the entire app
> were written by an AI coding assistant (Anthropic's Claude) under the direction of the
> maintainer, who reviews, tests and decides what goes in. Expect rough edges, test your G-code
> before long prints, and please report problems here – not to the OrcaSlicer team.

> Unofficial project, not affiliated with the OrcaSlicer team or any printer manufacturer.

## All credit to OrcaSlicer

Everything that actually makes a good print – the slicing algorithms, Arachne walls, supports,
seams, calibration know-how, the hundreds of carefully tuned printer, filament and process
profiles, and the translations – is the work of the **OrcaSlicer project, led by SoftFever, and its
many contributors**. OrcaSlicer itself stands on the shoulders of **Bambu Studio**, **PrusaSlicer**
and **Slic3r** (Alessandro Ranellucci and contributors). This port only repackages that work for
Android tablets; it would not exist without years of open-source effort by these communities.

If this app is useful to you, please support the original project:
[OrcaSlicer on GitHub](https://github.com/SoftFever/OrcaSlicer) ·
[OrcaSlicer website](https://www.orcaslicer.com) ·
[sponsor SoftFever](https://github.com/sponsors/SoftFever).

## Features

**Printers and profiles**
- Original OrcaSlicer engine and **all printer, filament and process profiles**; pick vendor →
  model → nozzle, only the chosen vendors' profiles are installed. Profile updates from GitHub.
- **Full settings editor** for process, filament and printer with the desktop pages and groups,
  search, simple/advanced/expert modes and the desktop's rules for greyed-out/hidden options.
  Save, delete, export, import (incl. `.orca_printer`/`.orca_filament` bundles) and compare presets.
  Labels and tooltips use the desktop translations (23 languages).
- Several filaments with colours, flushing volumes, prime tower position, plate types.

**Prepare**
- Load **STL, 3MF (projects with plates and settings), OBJ, STEP, AMF, DRC, SVG** – also via
  "Open with" / "Share". Add shapes, **embossed text**, SVGs and the Orca sample models.
- Multiple **plates**, arrange, auto-orient, lay on face, move/rotate/scale/mirror, copies,
  **cut**, split, simplify, repair, measure, undo/redo.
- Parts, modifiers, support blockers/enforcers and negative volumes; **per-object, per-part and
  height-range settings**; filament per object.
- **Painting**: supports, seams, fuzzy skin and multi-colour.
- **Variable layer height** (adaptive, smooth, manual).
- Pauses, filament changes and custom G-code at any layer.
- **Calibration tests** from the desktop: temperature tower, flow rate, pressure advance
  (line/tower), retraction, max volumetric speed, VFA, input shaping and cornering.
- Save/open projects (3MF, compatible with the desktop), export STL.

**Preview and printing**
- Slicing in the background with notification, cancel and "slice all plates".
- Toolpath preview with layer and move sliders, colour schemes (line type, speed, height, width,
  fan, temperature, flow, filament), travels/retractions/seams and the matching **G-code lines**.
- Print time, filament, cost and per-type statistics; open external G-code files.
- **Device tab** with the printer's own web UI (Mainsail, Fluidd, OctoPrint, …) and job status
  with pause/resume/cancel; upload and start prints on **Klipper/Moonraker, OctoPrint, PrusaLink,
  Duet, Repetier, ESP3D, MKS and Bambu Lab (LAN mode)**; printer discovery in the network;
  print progress notification.
- Save or share G-code and sliced `.gcode.3mf` plates.

**App**
- Tablet layout with side panel, phone layout with bottom sheet; keyboard/mouse shortcuts.
- Light and dark theme, optional Material You colours; English and German UI.

Not ported: SLA printing, the CAD tab and Python plugins.

Requires Android 10+ on an arm64 device (virtually all current tablets and phones).

## Repository layout

| Path | Content |
|---|---|
| `src-orca/` | OrcaSlicer upstream, unmodified (git submodule, see `android/UPSTREAM.md`) |
| `android/deps/` | CMake superbuild of the native dependencies for Android arm64 |
| `android/core/` | Builds `libslic3r` + the JNI bridge `liborca_jni.so` |
| `android/app/` | The Android app (Kotlin, Jetpack Compose, OpenGL ES 3) |
| `android/scripts/` | Build, resource packing and test helpers |

## Building

Requirements: Linux, Android SDK with NDK 28.2, CMake ≥ 3.20, Ninja, JDK 17+, Python 3,
autotools and perl (for GMP/MPFR/OpenSSL).

```bash
git clone --recursive <this repository>
cd OrcaSlicer-Android/android
scripts/build.sh debug      # or: release
```

The first run builds all native dependencies (about an hour); outputs go to
`~/build/orca-android` (override with `ORCA_BUILD_ROOT`). The APK ends up in
`~/build/orca-android/gradle/app/outputs/apk/`.

## License

AGPL-3.0, like OrcaSlicer itself – see `LICENSE` and `THIRD_PARTY_NOTICES.md`.

---

**Deutsch:** Orca-Android ist eine inoffizielle Android-Portierung von OrcaSlicer für Tablets und Handys. Der originale
Slicing-Kern läuft direkt auf dem Gerät, dazu kommt eine neue Touch-Oberfläche. Die Portierung wurde
mit KI (Claude von Anthropic) erstellt. Die eigentliche Slicer-Arbeit, also Algorithmen, Profile und
Übersetzungen, stammt vollständig vom OrcaSlicer-Projekt und seinen Vorgängern Bambu Studio,
PrusaSlicer und Slic3r. Lizenz: AGPL-3.0.
