#!/usr/bin/env bash
set -euo pipefail

mkdir -p emulator-evidence
sudo python3 tests/mock-lan-agent.py > emulator-evidence/mock-agent.log 2>&1 &
fixture_pid=$!
cleanup() {
  local result=$?
  curl -fsS http://127.0.0.1:17841/state > emulator-evidence/mock-state.json || true
  if [ "$result" -ne 0 ]; then
    timeout 15s adb shell uiautomator dump /sdcard/lanpower-failed.xml || true
    adb pull /sdcard/lanpower-failed.xml emulator-evidence/failed-ui.xml || true
    adb exec-out screencap -p > emulator-evidence/failed-screenshot.png || true
    adb logcat -d > emulator-evidence/failed-logcat.txt || true
  fi
  sudo kill "$fixture_pid" 2>/dev/null || true
  return "$result"
}
trap cleanup EXIT
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:17841/state > /dev/null; then break; fi
  sleep 0.25
done
curl -fsS http://127.0.0.1:17841/state > /dev/null
apk=$(find tested-apk -name app-debug.apk -print -quit)
test -n "$apk"

verify_app() {
  local evidence="emulator-evidence/$1"
  mkdir -p "$evidence"
  if [ "${2:-}" = '--existing-key' ]; then
    curl -fsS -X POST -H 'Content-Type: application/json' -d '{"devices":{"00000000-0000-4000-8000-000000000004":{"online":false}}}' http://127.0.0.1:17841/set > /dev/null
  fi
  adb shell am start -W -n com.lanpower.standalone/.MainActivity --ez lanpower_ci true | tee "$evidence/launch.txt"
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
adb install -r release/RoomTN20-1.2.0.apk
verify_app published-upgrade --existing-key
