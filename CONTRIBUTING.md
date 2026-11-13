# Contributing

Thanks for looking. This is a personal project, so reviews can be slow. Forks are just as welcome.

## Reporting a bug
Use the bug report form. The three things that make a report usable: the build (About shows it, for example `0.1.2-r522`), the
device and Android version, and the steps. If the app crashed, paste the crash text the app offers to copy on the next start.
Remove printer serial numbers, IP addresses and access codes from anything you paste.

Wrong toolpaths or profile problems that also happen in desktop OrcaSlicer belong upstream:
<https://github.com/OrcaSlicer/OrcaSlicer/issues>.

## Code changes
- The app is Kotlin and Jetpack Compose in `android/app`, with our own C++ bridge in `android/core/jni`.
- `src-orca/` is upstream OrcaSlicer and stays unmodified. Engine changes go in as patch files under `android/patches/src-orca/`.
- Native code is built in CI only. Kotlin compiles locally; see "Building" in the README.
- Run the unit tests before opening a pull request: `cd android && ./gradlew testDebugUnitTest`.
- Keep pull requests small and say how you tested: device, Android version, what you tapped.
- No personal details in commits or screenshots (serials, IPs, account names).

## License
Contributions are accepted under AGPL-3.0, the license of this repository.
