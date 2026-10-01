#!/usr/bin/env bash
# Installs the debug APK on the running emulator/device, loads a model and slices it
# automatically, then saves a screenshot.
#   android/scripts/emulator_test.sh <model file> [screenshot.png] [wait seconds]
# ORCA_PRINTERS="Qidi Q1 Pro|0.4" selects printers first; ADB_SERIAL picks the emulator.
set -euo pipefail

model=$1
shot=${2:-/tmp/orca-test.png}
wait=${3:-120}
root=${ORCA_BUILD_ROOT:-$HOME/build/orca-android}
adb="${ADB:-$HOME/Android/Sdk/platform-tools/adb} ${ADB_SERIAL:+-s $ADB_SERIAL}"
pkg=${ORCA_PACKAGE:-de.cl1x.orca_android.debug}

$adb install -r "$root"/gradle/app/outputs/apk/debug/app-debug.apk >/dev/null
name=$(basename "$model")
$adb push "$model" "/data/local/tmp/$name" >/dev/null
$adb shell "run-as $pkg mkdir -p files/test && run-as $pkg cp /data/local/tmp/$name files/test/$name"
$adb logcat -c
$adb shell am force-stop $pkg
extra=()
[ -n "${ORCA_PRINTERS:-}" ] && extra=(--esa orca.printers "'${ORCA_PRINTERS// /\ }'")
$adb shell am start -n $pkg/app.orcaandroid.ui.MainActivity --es orca.model "/data/data/$pkg/files/test/$name" --ez orca.slice true "${extra[@]}" >/dev/null
sleep "$wait"
$adb exec-out screencap -p > "$shot"
$adb logcat -d | grep -E " Orca|OrcaCore|AndroidRuntime|FATAL|DEBUG   " | tail -20 || true
echo "Screenshot: $shot"
