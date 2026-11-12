# Third-party software

Orca-Android is distributed under the GNU Affero General Public License v3.0 (see
`LICENSE`). It combines the components below. The app shows this file under
More > About > Open-source licences.

How to read the tables: the licence column is an SPDX identifier. `UNCONFIRMED` means the licence
was not checked against the component's own files (most sources are downloaded during the build and
are not in this repository); the identifier before it is the commonly published licence and still
needs checking. Entries without that mark were checked against a licence file or the component's
own metadata in this checkout or in the staged build output. License texts ship with the sources
(the `src-orca` submodule and the archives downloaded by `android/deps`, `android/openscad`,
`android/obn` and `android/scripts/stage_python.py`).

## Slicing core

| Component | Version / commit | License | Upstream |
|---|---|---|---|
| OrcaSlicer (libslic3r, profiles, translations) | commit `f8dd56053c52c729eab3acf871f1b75451eaeabb` (2.5.0-dev), used with `android/patches/src-orca/*.patch` | AGPL-3.0 (`src-orca/LICENSE.txt`) | https://github.com/OrcaSlicer/OrcaSlicer |
| ... derived from Bambu Studio, PrusaSlicer, SuperSlicer and Slic3r | as in OrcaSlicer | AGPL-3.0-only / AGPL-3.0-or-later, UNCONFIRMED | https://github.com/bambulab/BambuStudio |
| open-bamboo-networking (Bambu LAN/cloud networking agent, linked into the native library, with 4 patches from `android/obn`) | v2.2.0, commit `5e6a359c71a0c07476f5d372bf7ad0f2b43efe94` | AGPL-3.0 (as stated in `android/obn/obn.cmake`; the licence file itself is fetched at build time) | https://github.com/ClusterM/open-bamboo-networking |
| ... libcurl, vendored by open-bamboo-networking | version UNCONFIRMED | curl, UNCONFIRMED | https://curl.se |

## Bundled data

| Component | Version / commit | License | Upstream |
|---|---|---|---|
| Printer, filament and process profiles, printer images, shapes, calibration and handy models (`src-orca/resources`, packed into the assets) | same commit as OrcaSlicer | AGPL-3.0 as part of OrcaSlicer; per-file origin of third-party models and images UNCONFIRMED | https://github.com/OrcaSlicer/OrcaSlicer |
| Build plate decal images (`assets/plate_decals`) | this repository | AGPL-3.0, author UNCONFIRMED | this repository |
| Liberation fonts (OpenSCAD text()) | 2.00.1 | OFL-1.1 (`Liberation-2.00.1/LICENSE`) | https://github.com/liberationfonts/liberation-fonts |
| OpenSCAD MCAD library | submodule of the pinned OpenSCAD commit | LGPL-2.1 (`MCAD/lgpl-2.1.txt`; some files allow more permissive terms) | https://github.com/openscad/MCAD |
| Bundled machine learning models | none | not applicable | |

## Modelling and scripting runtime

| Component | Version / commit | License | Upstream |
|---|---|---|---|
| OpenSCAD (libopenscad, with this project's bridge in `android/openscad`) | commit `c0ac8289f1c9db6f70891da5df3b4085b7eaf8b6` | GPL-2.0-or-later, UNCONFIRMED | https://github.com/openscad/openscad |
| ... manifold (OpenSCAD submodule) | pinned by the OpenSCAD commit | Apache-2.0, UNCONFIRMED | https://github.com/elalish/manifold |
| ... Clipper2 (OpenSCAD submodule) | pinned by the OpenSCAD commit | BSL-1.0, UNCONFIRMED | https://github.com/AngusJohnson/Clipper2 |
| utf8proc | 2.10.0 | MIT, UNCONFIRMED | https://github.com/JuliaStrings/utf8proc |
| double-conversion | 3.3.1 | BSD-3-Clause, UNCONFIRMED | https://github.com/google/double-conversion |
| libzip | 1.11.4 | BSD-3-Clause, UNCONFIRMED | https://github.com/nih-at/libzip |
| libxml2 | 2.14.6 | MIT, UNCONFIRMED | https://github.com/GNOME/libxml2 |
| HarfBuzz | 11.5.1 | MIT, UNCONFIRMED | https://github.com/harfbuzz/harfbuzz |
| Fontconfig | 2.17.1 | HPND-sell-variant, UNCONFIRMED | https://gitlab.freedesktop.org/fontconfig/fontconfig |
| CPython (staged for the plugin runtime) | 3.12.12 | PSF-2.0, UNCONFIRMED | https://www.python.org |
| Chaquopy CPython target builds (the Android builds of CPython and its libraries fetched from Maven Central by `android/scripts/stage_python.py`) | 3.12.12-0 | MIT (as stated in `stage_python.py`); the bundled libraries libssl, libcrypto and libsqlite3 keep their own licences, UNCONFIRMED | https://github.com/chaquo/chaquopy |

## Native libraries (built by `android/deps`)

| Component | Version | License | Upstream |
|---|---|---|---|
| Boost | 1.84.0 | BSL-1.0, UNCONFIRMED | https://github.com/boostorg/boost |
| oneTBB | 2021.5.0 | Apache-2.0, UNCONFIRMED | https://github.com/oneapi-src/oneTBB |
| CGAL | 5.6.3 | GPL-3.0-or-later / LGPL-3.0-or-later, UNCONFIRMED | https://github.com/CGAL/cgal |
| GMP | 6.2.1 | LGPL-3.0-or-later / GPL-2.0-or-later, UNCONFIRMED | https://gmplib.org |
| MPFR | 4.2.2 | LGPL-3.0-or-later, UNCONFIRMED | https://www.mpfr.org |
| Eigen | 5.0.1 | MPL-2.0, UNCONFIRMED | https://gitlab.com/libeigen/eigen |
| Open CASCADE Technology | 8.0.1 | LGPL-2.1-only with OCCT exception, UNCONFIRMED | https://github.com/Open-Cascade-SAS/OCCT |
| OpenCV | 4.6.0 | Apache-2.0, UNCONFIRMED | https://github.com/opencv/opencv |
| OpenVDB (fork by tamasmeszaros) | commit `a68fd58d0e2b85f01adeb8b13d7555183ab10aa5` | MPL-2.0, UNCONFIRMED | https://github.com/tamasmeszaros/openvdb |
| OpenEXR / Imath | 2.5.5 | BSD-3-Clause, UNCONFIRMED | https://github.com/AcademySoftwareFoundation/openexr |
| c-blosc (fork by tamasmeszaros) | branch `v1.17.0_tm` | BSD-3-Clause, UNCONFIRMED | https://github.com/tamasmeszaros/c-blosc |
| Assimp | 5.4.3 or 5.3.1, chosen in `src-orca/deps/Assimp` (which one is used: UNCONFIRMED) | BSD-3-Clause, UNCONFIRMED | https://github.com/assimp/assimp |
| Draco | 1.5.7 | Apache-2.0, UNCONFIRMED | https://github.com/google/draco |
| OpenSSL | 3.5.9 | Apache-2.0, UNCONFIRMED | https://github.com/openssl/openssl |
| FreeType | 2.12.1 | FTL / GPL-2.0-only, UNCONFIRMED | https://freetype.org |
| libpng | 1.6.35 | libpng-2.0, UNCONFIRMED | https://github.com/glennrp/libpng |
| libjpeg-turbo | 3.0.1 | IJG / BSD-3-Clause / Zlib, UNCONFIRMED | https://github.com/libjpeg-turbo/libjpeg-turbo |
| zlib | 1.2.13 | Zlib, UNCONFIRMED | https://github.com/madler/zlib |
| Expat (`src-orca/deps_src/expat`) | in tree | MIT (`COPYING`) | https://github.com/libexpat/libexpat |
| Qhull | 8.0.2 | Qhull license, UNCONFIRMED | https://github.com/qhull/qhull |
| cereal | 1.3.0 | BSD-3-Clause, UNCONFIRMED | https://github.com/USCiLab/cereal |
| NLopt | 2.5.0 | LGPL-2.1-or-later / MIT, UNCONFIRMED | https://github.com/stevengj/nlopt |
| libnoise (fork by SoftFever) | 1.0 | LGPL-2.1-or-later, UNCONFIRMED | https://github.com/SoftFever/Orca-deps-libnoise |
| SolveSpaceLib (fork by JacobStoren) | commit `4d8704523e4bf212fadf5189f92484244f670fea` | GPL-3.0-or-later, UNCONFIRMED | https://github.com/JacobStoren/SolveSpaceLib |

## Sources vendored in OrcaSlicer (`src-orca/deps_src`)

All are in the pinned OrcaSlicer commit. Version is the one in tree (none is tagged there).

| Component | License | Upstream |
|---|---|---|
| admesh | GPL-2.0-or-later, UNCONFIRMED | https://github.com/admesh/admesh |
| Anti-Grain Geometry (`agg/copying`) | Modified BSD (BSD-3-Clause-like, version 2.4 terms) | http://antigrain.com |
| ankerl::unordered_dense | MIT, UNCONFIRMED | https://github.com/martinus/unordered_dense |
| Clipper2 | BSL-1.0, UNCONFIRMED | https://github.com/AngusJohnson/Clipper2 |
| earcut.hpp (`earcut/LICENSE`) | ISC | https://github.com/mapbox/earcut.hpp |
| fast_float | Apache-2.0 / MIT / BSL-1.0, UNCONFIRMED | https://github.com/fastfloat/fast_float |
| GLU libtess | SGI-B-2.0, UNCONFIRMED | https://github.com/ValveSoftware/GLU-libtess-legacy |
| libigl | MPL-2.0, UNCONFIRMED | https://github.com/libigl/libigl |
| libnest2d (`libnest2d/LICENSE.txt`) | LGPL-3.0 | https://github.com/tamasmeszaros/libnest2d |
| MCUT (`mcut/LICENSE.txt`, `LICENSE.GPL.txt`) | GPL-3.0 (offered as GPL or commercial; used under the GPL) | https://github.com/cutdigital/mcut |
| miniz (`miniz/LICENSE`) | MIT | https://github.com/richgel999/miniz |
| NanoSVG | Zlib, UNCONFIRMED | https://github.com/memononen/nanosvg |
| nlohmann/json | MIT, UNCONFIRMED | https://github.com/nlohmann/json |
| QOI | MIT, UNCONFIRMED | https://github.com/phoboslab/qoi |
| semver | MIT, UNCONFIRMED | https://github.com/h2non/semver.c |
| Shiny | MIT, UNCONFIRMED | https://sourceforge.net/projects/shinyprofiler |
| stb_dxt | MIT / Public domain, UNCONFIRMED | https://github.com/nothings/stb |
| Qhull (copy in `deps_src/qhull`) | Qhull license, UNCONFIRMED | https://github.com/qhull/qhull |

## Android app (Gradle dependencies in the APK)

| Component | Version | License | Upstream |
|---|---|---|---|
| Jetpack Compose (BOM), Material 3, material-icons-extended | BOM 2026.09.00 | Apache-2.0, UNCONFIRMED | https://developer.android.com/jetpack/androidx |
| AndroidX core-ktx | 1.19.1 | Apache-2.0, UNCONFIRMED | https://developer.android.com/jetpack/androidx |
| AndroidX activity-compose | 1.13.0 | Apache-2.0, UNCONFIRMED | https://developer.android.com/jetpack/androidx |
| AndroidX lifecycle (viewmodel-compose, runtime-compose) | 2.11.0 | Apache-2.0, UNCONFIRMED | https://developer.android.com/jetpack/androidx |
| kotlinx.coroutines (Android) | 1.11.0 | Apache-2.0, UNCONFIRMED | https://github.com/Kotlin/kotlinx.coroutines |
| Kotlin standard library | Gradle plugin version | Apache-2.0, UNCONFIRMED | https://github.com/JetBrains/kotlin |
| XZ for Java | 1.10 | 0BSD / public domain, UNCONFIRMED | https://tukaani.org/xz/java.html |

AndroidX Test and JUnit 4 are used for tests only and are not part of the APK.

## Trademarks

"OrcaSlicer" and printer brand names belong to their respective owners. This project is an
unofficial port and is not affiliated with or endorsed by the OrcaSlicer project, SoftFever or any
printer manufacturer.
