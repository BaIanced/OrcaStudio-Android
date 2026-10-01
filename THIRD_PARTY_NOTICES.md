# Third-party software

Orca-Android is distributed under the GNU Affero General Public License v3.0 (see
`LICENSE`). It combines the following components; their licenses are compatible with the AGPL-3.0.
License texts ship with the respective sources (the `src-orca` submodule and the source archives
downloaded by `android/deps`).

## Slicing core

| Component | License |
|---|---|
| [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) (libslic3r, profiles, translations) | AGPL-3.0 |
| … derived from Bambu Studio, PrusaSlicer, SuperSlicer and Slic3r | AGPL-3.0 |

## Native libraries (built by `android/deps`)

| Component | License |
|---|---|
| Boost | BSL-1.0 |
| oneTBB | Apache-2.0 |
| CGAL | GPL-3.0-or-later / LGPL-3.0-or-later |
| GMP | LGPL-3.0-or-later |
| MPFR | LGPL-3.0-or-later |
| Eigen | MPL-2.0 |
| Open CASCADE Technology | LGPL-2.1 with OCCT exception |
| OpenCV | Apache-2.0 |
| OpenVDB | MPL-2.0 |
| OpenEXR / Imath | BSD-3-Clause |
| c-blosc | BSD-3-Clause |
| Assimp | BSD-3-Clause |
| Draco | Apache-2.0 |
| OpenSSL 3 | Apache-2.0 |
| FreeType | FreeType License (FTL) |
| libpng | libpng License v2 |
| libjpeg-turbo | IJG / BSD-3-Clause / Zlib |
| zlib | Zlib |
| Expat | MIT |
| Qhull | Qhull License |
| cereal | BSD-3-Clause |
| NLopt | LGPL-2.1-or-later / MIT |
| libnoise | LGPL-2.1-or-later |

## Sources vendored in OrcaSlicer (`src-orca/deps_src`)

| Component | License |
|---|---|
| admesh | GPL-2.0-or-later |
| Anti-Grain Geometry | Modified BSD |
| ankerl::unordered_dense | MIT |
| Clipper, Clipper2 | BSL-1.0 |
| earcut.hpp | ISC |
| fast_float | Apache-2.0 / MIT / BSL-1.0 |
| GLU libtess | SGI Free Software License B 2.0 |
| libigl | MPL-2.0 |
| libnest2d | LGPL-3.0 |
| MCUT | GPL-3.0 |
| miniz | MIT |
| NanoSVG | Zlib |
| nlohmann/json | MIT |
| QOI | MIT |
| semver | MIT |
| Shiny | MIT |
| stb_dxt | Public domain / MIT |

## Android app

| Component | License |
|---|---|
| Kotlin, kotlinx.coroutines | Apache-2.0 |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 |
| AndroidX Test, JUnit 4 (tests only, not part of the APK) | Apache-2.0 / EPL-1.0 |

## Trademarks

"OrcaSlicer" and printer brand names belong to their respective owners. This project is an
unofficial port and is not affiliated with or endorsed by the OrcaSlicer project, SoftFever or any
printer manufacturer.
