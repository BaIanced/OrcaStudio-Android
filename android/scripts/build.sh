#!/usr/bin/env bash
# Builds the Android app end to end: deps (once), native core, APK.
#   android/scripts/build.sh [debug|release]
# Build outputs go to $ORCA_BUILD_ROOT (default ~/build/orca-android), outside the source tree.
set -euo pipefail

variant=${1:-debug}
here=$(cd "$(dirname "$0")/.." && pwd)
root=${ORCA_BUILD_ROOT:-$HOME/build/orca-android}
ndk=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}
toolchain="$here/android-toolchain.cmake"

if [ ! -f "$root/deps/OrcaSlicer_dep/usr/local/lib/libopenvdb.a" ]; then
    cmake -G Ninja -S "$here/deps" -B "$root/deps" -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DCMAKE_BUILD_TYPE=Release
    # Each dependency builds in parallel internally; keep the top level narrow.
    cmake --build "$root/deps" --target deps -- -j4
fi

# ORCA_CORE_PREBUILT=1: CI restored liborca_jni.so for unchanged native sources; skip the core.
if [ "${ORCA_CORE_PREBUILT:-0}" = 1 ] && [ -f "$root/jniLibs/arm64-v8a/liborca_jni.so" ]; then
    echo "Native core unchanged: using the cached liborca_jni.so"
else
    launcher=()
    if command -v ccache >/dev/null; then launcher=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache); fi
    cmake -G Ninja -S "$here/core" -B "$root/core" -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DCMAKE_BUILD_TYPE=Release "${launcher[@]}" >/dev/null
    cmake --build "$root/core" --target orca_jni

    mkdir -p "$root/jniLibs/arm64-v8a"
    "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
        -o "$root/jniLibs/arm64-v8a/liborca_jni.so" "$root/core/liborca_jni.so"
fi

task=assemble${variant^}
(cd "$here" && ./gradlew --console=plain -q "$task")
echo "APK: $(ls "$root"/gradle/app/outputs/apk/"$variant"/*.apk)"
