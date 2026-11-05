#!/usr/bin/env bash
set -euo pipefail

mkdir -p emulator-evidence
apk=$(find tested-apk -name app-debug.apk -print -quit)
test -n "$apk"

verify_app() {
  local evidence="emulator-evidence/$1"
  mkdir -p "$evidence"
  adb shell am start -W -n com.lanpower.standalone/.MainActivity | tee "$evidence/launch.txt"
  sleep 5
  python3 tests/android-ui-smoke.py "${@:2}" | tee "$evidence/ui-test.txt"
  local pid
  pid=$(adb shell pidof com.lanpower.standalone | tr -d '\r')
  test -n "$pid"
  printf '%s\n' "$pid" > "$evidence/pid.txt"
  adb shell uiautomator dump /sdcard/lanpower-ui.xml
  adb pull /sdcard/lanpower-ui.xml "$evidence/ui.xml"
  adb exec-out screencap -p > "$evidence/screenshot.png"
  adb logcat -d > "$evidence/logcat.txt"
  adb logcat -d --pid="$pid" > "$evidence/app-logcat.txt"
  if grep -q 'FATAL EXCEPTION' "$evidence/app-logcat.txt"; then
    echo 'Application crashed during launch.' >&2
    exit 1
  fi
  grep -q 'com.lanpower.standalone' "$evidence/ui.xml"
}

adb install -r "$apk"
verify_app built-from-source
adb uninstall com.lanpower.standalone

# Exercise the exact published APK, including upgrading from the previous release.
adb install release/LanPowerStandalone-1.0.0-debug.apk
adb shell am start -W -n com.lanpower.standalone/.MainActivity
python3 tests/android-ui-smoke.py --prepare-key-only
adb install -r release/LanPowerStandalone-1.0.1-debug.apk
verify_app published-upgrade --existing-key
