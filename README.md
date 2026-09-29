# OrcaSlicer for Android (unofficial)

A native Android port of [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) for tablets. The
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

- Original OrcaSlicer engine and **all printer, filament and process profiles** (66 vendors);
  pick your printers and nozzle sizes, only their profiles are installed.
- Load **STL, 3MF, OBJ, STEP, AMF, DRC**; also via "Open with" / "Share" from other apps.
- Automatic placement and arrangement, copies.
- **Full settings editor** for process, filament and printer with the desktop pages and groups,
  search, simple/advanced/expert modes and saving your own presets. Labels and tooltips use the
  desktop translations (23 languages).
- Slicing with progress and cancel; print time, filament and cost estimates.
- 3D view of the plate and the sliced toolpaths with a layer slider.
- **Device tab** with the printer's web UI (Mainsail, Fluidd, OctoPrint, …) including the live
  camera; send G-code and start prints on Klipper/Moonraker, OctoPrint and PrusaLink.
- Save or share the G-code.
- Light and dark theme, optional Material You colors.

Requires Android 10+ on an arm64 device (virtually all current tablets).

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

**Deutsch:** Inoffizielle Android-Portierung von OrcaSlicer für Tablets. Der originale
Slicing-Kern läuft direkt auf dem Gerät, dazu kommt eine neue Touch-Oberfläche. Die Portierung wurde
mit KI (Claude von Anthropic) erstellt. Die eigentliche Slicer-Arbeit, also Algorithmen, Profile und
Übersetzungen, stammt vollständig vom OrcaSlicer-Projekt und seinen Vorgängern Bambu Studio,
PrusaSlicer und Slic3r. Lizenz: AGPL-3.0.
