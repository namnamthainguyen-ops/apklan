#!/usr/bin/env bash
set -euo pipefail

mkdir -p emulator-evidence
apk=$(find tested-apk -name app-debug.apk -print -quit)
test -n "$apk"
adb install -r "$apk"
adb logcat -c
adb shell am start -W -n com.lanpower.standalone/.MainActivity | tee emulator-evidence/launch.txt
sleep 5
python3 tests/android-ui-smoke.py | tee emulator-evidence/ui-test.txt
adb shell pidof com.lanpower.standalone | tee emulator-evidence/pid.txt
adb shell uiautomator dump /sdcard/lanpower-ui.xml
adb pull /sdcard/lanpower-ui.xml emulator-evidence/ui.xml
adb exec-out screencap -p > emulator-evidence/screenshot.png
adb logcat -d > emulator-evidence/logcat.txt
if grep -q 'FATAL EXCEPTION' emulator-evidence/logcat.txt; then
  echo 'Application crashed during launch.' >&2
  exit 1
fi
grep -q 'com.lanpower.standalone' emulator-evidence/ui.xml
